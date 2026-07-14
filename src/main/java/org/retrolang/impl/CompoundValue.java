/*
 * Copyright 2025 The Retrospect Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.retrolang.impl;

import java.util.Arrays;
import org.retrolang.util.ArrayUtil;
import org.retrolang.util.SizeOf;

/**
 * An implementation of Value for BaseTypes with {@code size() > 0}, i.e. those that are
 * compositional but not singletons.
 */
public final class CompoundValue extends RefCounted implements Value {
  private static final long OBJ_SIZE = SizeOf.object(RefCounted.BASE_SIZE + 2 * SizeOf.PTR);

  /**
   * Returns the number of bytes of memory required for a CompoundValue with the specified number of
   * elements.
   */
  static long sizeOf(int numElements) {
    return OBJ_SIZE + SizeOf.array(MemoryHelper.chooseCapacityObjects(numElements), SizeOf.PTR);
  }

  private BaseType baseType;

  /**
   * The elements of this value. {@code element.length} must be greater than or equal to {@code
   * baseType.size()}; if it is greater, the remaining elements must be null.
   */
  @RC.Counted private Object[] elements;

  /** Creates a new CompoundValue with the given type and elements. */
  @RC.Out
  public CompoundValue(Allocator allocator, BaseType baseType, @RC.In Object[] elements) {
    assert baseType.size() > 0 && Value.containsValues(elements, baseType.size());
    assert Arrays.stream(elements).noneMatch(e -> e instanceof RValue);
    // If this Compound will be uncounted, all of its elements must also be uncounted.
    assert allocator.isCounted() || Arrays.stream(elements).noneMatch(RefCounted::isRefCounted);
    this.baseType = baseType;
    this.elements = elements;
    allocator.recordAlloc(this, OBJ_SIZE);
  }

  /**
   * If {@code baseType.size()} is zero, just returns the corresponding {@link Singleton}. Otherwise
   * creates a new CompoundValue with the given type and elements.
   *
   * <p>Calls {@code elements} once for each element, up to {@code baseType.size()}.
   */
  @RC.Out
  public static Value of(Allocator allocator, BaseType baseType, RC.RCIntFunction<Value> elements) {
    if (baseType.isSingleton()) {
      return baseType.asValue();
    }
    Object[] elementArray = allocator.allocObjectArray(baseType.size());
    for (int i = 0; i < baseType.size(); i++) {
      elementArray[i] = elements.apply(i);
    }
    return new CompoundValue(allocator, baseType, elementArray);
  }

  @Override
  public BaseType baseType() {
    return baseType;
  }

  @Override
  @RC.Out
  public Value element(int i) {
    return Value.addRef(Value.fromArray(elements, i));
  }

  /**
   * If true, {@link #peekElement} will return specially-constructed objects to verify that callers
   * are using them appropriately (e.g. not calling {@link Value#addRef} on them). This has a
   * non-zero performance cost, so only intended for use during development.
   */
  private static final boolean VERIFY_TRANSIENT_USE = CompoundValue.class.desiredAssertionStatus();

  @Override
  public Value peekElement(int i) {
    Value result = Value.fromArray(elements, i);
    if (VERIFY_TRANSIENT_USE && RefCounted.isRefCounted(result)) {
      if (result instanceof CompoundValue cv) {
        return asTransient(cv);
      } else if (result instanceof NumValue nv) {
        return nv.asTransient();
      }
    }
    return result;
  }

  /**
   * Creates a transient copy of the given CompoundValue; only used when {@link
   * #VERIFY_TRANSIENT_USE} is true.
   */
  private static Value asTransient(CompoundValue cv) {
    return new Value.NotStorable() {
      @Override
      public BaseType baseType() {
        return cv.baseType;
      }

      @Override
      @RC.Out
      public Value element(int index) {
        return cv.element(index);
      }

      @Override
      public Value peekElement(int index) {
        return cv.peekElement(index);
      }

      @Override
      public Value makeStorable(TState tstate) {
        return Value.addRef(cv);
      }

      @Override
      public boolean equals(Object obj) {
        return cv.equals(obj);
      }

      @Override
      public String toString() {
        return cv.toString();
      }
    };
  }

  /**
   * Implements {@link TemplateBuilder.CompoundBase#castImpl}. Done here so that we can access
   * {@link #elements} directly.
   */
  @RC.Out
  Value castImpl(TState tstate, TemplateBuilder.CompoundBase templateBuilder) {
    @RC.Counted Object[] newElements = null;
    for (int i = 0; i < baseType.size(); i++) {
      Value e = Value.fromArray(elements, i);
      Value e2 = templateBuilder.elementBuilder(i).cast(tstate, e);
      if (e2 == null) {
        tstate.dropReference(newElements);
        return null;
      } else if (newElements != null) {
        newElements[i] = e2;
      } else if (e2 != e) {
        newElements = tstate.allocObjectArray(baseType.size());
        newElements[i] = e2;
        getElements(i, newElements, 0);
      } else {
        tstate.dropValue(e2);
      }
    }
    if (newElements != null) {
      return new CompoundValue(tstate, baseType, newElements);
    }
    addRef();
    return this;
  }

  @Override
  @RC.Out
  @RC.In
  public Value replaceElement(TState tstate, int index, @RC.In Value newElement) {
    if (isNotShared() && !(newElement instanceof RValue)) {
      // There's no benefit to calling Value.fromArray() for a Value that we're going to drop.
      tstate.dropValue((Value) elements[index]);
      elements[index] = newElement;
      return this;
    } else {
      Object[] newElements = tstate.allocObjectArray(baseType.size());
      for (int i = 0; i < baseType.size(); i++) {
        newElements[i] = (i == index) ? newElement : element(i);
      }
      tstate.dropReference(this);
      return tstate.asCompoundValue(baseType, newElements);
    }
  }

  @Override
  protected long visitRefs(RefVisitor visitor) {
    visitor.visitObjArray(elements);
    return OBJ_SIZE;
  }

  boolean canUpdateInPlace(int newSize) {
    return isNotShared() && MemoryHelper.isOkForSize(elements, newSize);
  }

  @Override
  public boolean equals(Object other) {
    return other == this || (other instanceof Value v && baseType.equalValues(this, v));
  }

  @Override
  public int hashCode() {
    // hashCode() is only enabled for non-refcounted CompoundValues, such as the ones in
    // constant Templates.
    if (isRefCounted()) {
      throw new AssertionError(VALUES_ARENT_HASHABLE);
    }
    return ArrayUtil.hashCode(elements, baseType.size()) * 31 + baseType.hashCode();
  }

  @Override
  public String toString() {
    return baseType.compositionalToString(this);
  }
}
