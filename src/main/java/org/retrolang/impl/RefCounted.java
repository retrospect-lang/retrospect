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

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.retrolang.code.CodeValue;
import org.retrolang.code.Op;
import org.retrolang.util.SizeOf;

/**
 * RefCounted objects maintain a per-instance reference count to enable accurate memory use tracking
 * and copy-on-write. Each RefCounted object with a positive reference count should have its memory
 * footprint accounted for in a ResourceTracker; when its reference count goes to zero the
 * ResourceTracker's total-in-use will be reduced accordingly, and any RefCounted objects it links
 * to will have their reference counts reduced.
 *
 * <p>RefCounted objects may also be marked as "uncounted"; uncounted objects are not accounted for
 * in any ResourceTracker, they will never be considered freed, and any RefCounted objects they link
 * to must also be uncounted.
 */
public abstract class RefCounted {

  static final Op ADD_REF_OP = RcOp.forRcMethod(RefCounted.class, "addRef", Object.class).build();

  /**
   * A magic CodeValue that when pushed on the top of the stack actually pops the top of the stack
   * and calls {@link #addRef(Object)} on it.
   */
  static final CodeValue ADD_REF_TOP_OF_STACK =
      // We use the ADD_REF_OP emitter, which assumes that its arg has been pushed on the stack,
      // but tell Op.Simple that we have no arguments.  Yes, it's a hack.
      Op.simple("addRefTOS", RefCounted.ADD_REF_OP.opEmit, void.class).build().result();

  static final Op IS_NOT_SHARED_OP =
      RcOp.forRcMethod(RefCounted.class, "isNotShared")
          .withConstSimplifier(
              x -> {
                assert !RefCounted.isNotShared(x);
                return CodeValue.ZERO;
              })
          // TODO: uncommment when PtrInfo is available
          //          .withOpSimplifier(
          //              (args, infos) -> {
          //                ValueInfo info = args.get(0).info(infos);
          //                return (info instanceof PtrInfo.IsNotShared) ? CodeValue.ONE : null;
          //              })
          .build();

  /**
   * The number of live references to this object; must include all links from other RefCounted
   * objects, and may also include references from in-progress method calls.
   *
   * <p>If {@code refCnt} is greater than 1, this object has multiple references and should not be
   * modified in any value-affecting way.
   *
   * <p>If {@code refCnt} is 1, this object has a single reference; a running method that has a
   * counted reference can infer that it has the only reference, and hence that it may modify the
   * object if desired.
   *
   * <p>If {@code refCnt} is 0, this object should now be unreachable.
   *
   * <p>If {@code refCnt} is -1, this object is uncounted; {@link #addRef} and {@link
   * #dropRefInternal} will have no effect.
   *
   * <p>If {@code refCnt} is -2, this object is transient; {@link #addRef} and {@link
   * #dropRefInternal} should never be called on it.
   */
  private volatile int refCnt = 1;

  /** A VarHandle for our {@link #refCnt} field. */
  private static final VarHandle REF_CNT_VAR =
      Handle.forVar(MethodHandles.lookup(), RefCounted.class, "refCnt", int.class);

  /**
   * The total size of the fields declared in this class; for use by subclass, when calculating
   * their total size.
   */
  protected static final long BASE_SIZE = SizeOf.INT;

  /**
   * Calls {@code visitor.visit()} on each RefCounted, byte[], or Object[] held by this object, and
   * then returns the size of this object plus any other objects it holds that were not visitable.
   *
   * <p>Usually called with a MemoryHelper as the visitor when this object's reference count has
   * gone to zero, but may also be called with other visitors for debugging purposes.
   */
  protected abstract long visitRefs(RefVisitor visitor);

  /**
   * If {@code isRefCounted(x)} is true, increments its reference count.
   *
   * <p>Throws an error if {@code x}'s reference count is zero.
   */
  public static void addRef(Object x) {
    assert !(x instanceof Value.NotStorable);
    assert x == null || !x.getClass().isArray();
    if (x instanceof RefCounted rc) {
      rc.updateRefCnt(1);
    }
  }

  /**
   * If {@code isRefCounted(x)} is true, increments its reference count by the given amount.
   *
   * <p>Throws an error if {@code x}'s reference count is zero or {@code increase} is negative.
   */
  public static void addRef(Object x, int increase) {
    assert !(x instanceof Value.NotStorable);
    if (increase < 0) {
      throw new IllegalArgumentException();
    } else if (x instanceof RefCounted rc && increase != 0) {
      rc.updateRefCnt(increase);
    }
  }

  /**
   * If {@code isRefCounted()} is true, increments this object's reference count.
   *
   * <p>Throws an error if this object's reference count is zero.
   */
  public final void addRef() {
    updateRefCnt(1);
  }

  /**
   * Decrements this object's reference count and returns true if it is now zero.
   *
   * <p>This should only be called by MemoryHelper; anyone else that wants to decrease an object's
   * reference count should be calling {@link MemoryHelper#dropReference(RefCounted)} to ensure that
   * proper cleanup happens when the reference count gets to zero.
   *
   * <p>Throws an error if this object's reference count was zero before calling {@code
   * dropRefInternal()}.
   */
  final boolean dropRefInternal() {
    return updateRefCnt(-1) == 0;
  }

  /**
   * Makes this object uncounted. May only be called on newly-allocated objects, before they have
   * been shared.
   *
   * <p>If you call this on an object that holds counted objects, they'll never be released. Don't
   * do that.
   */
  final void setUncounted() {
    if (!REF_CNT_VAR.compareAndSet(this, 1, -1)) {
      throw new IllegalStateException();
    }
  }

  /**
   * Makes this object transient. May only be called on newly-allocated objects, before they have
   * been shared.
   *
   * <p>If you call this on an object that holds counted objects, they'll never be released. Don't
   * do that.
   */
  final void setTransient() {
    if (!REF_CNT_VAR.compareAndSet(this, 1, -2)) {
      throw new IllegalStateException();
    }
  }

  /**
   * If this object is transient, sets its reference count to 1 and returns true; otherwise returns
   * false.
   */
  final boolean unsetTransient() {
    return REF_CNT_VAR.compareAndSet(this, -2, 1);
  }

  /**
   * Returns true if {@code x} is a RefCounted and {@link #setUncounted()} has not been called;
   * throws an IllegalStateException if this object is transient.
   */
  public static boolean isRefCounted(Object x) {
    assert !(x instanceof Value.NotStorable);
    return (x instanceof RefCounted rc) && rc.isRefCounted();
  }

  /**
   * Returns true if {@link #setUncounted()} has not been called; throws an IllegalStateException if
   * this object is transient.
   */
  public final boolean isRefCounted() {
    int cnt = refCnt;
    if (cnt == 0 || cnt == -2) {
      throw new IllegalStateException();
    }
    return cnt != -1;
  }

  /** Returns true if {@code x} is a transient RefCounted. */
  public static boolean isTransient(Object x) {
    return (x instanceof RefCounted rc) && rc.isTransient();
  }

  /** Returns true if this object is transient. */
  public final boolean isTransient() {
    int cnt = refCnt;
    if (cnt == 0) {
      throw new IllegalStateException();
    }
    return cnt == -2;
  }

  /**
   * Returns true if {@code x} is a RefCounted and {@link #setUncounted()} has not been called, or
   * {@code x} is a {@link Value.NotStorable}.
   */
  public static boolean isRefCountedOrTransient(Object x) {
    return (x instanceof RefCounted rc)
        ? rc.isRefCountedOrTransient()
        : x instanceof Value.NotStorable;
  }

  /** Returns true if {@link #setUncounted()} has not been called. */
  public final boolean isRefCountedOrTransient() {
    int cnt = refCnt;
    if (cnt == 0) {
      throw new IllegalStateException();
    }
    return cnt != -1;
  }

  /**
   * Returns true if {@code x} is a RefCounted and its reference count is one.
   *
   * <p>Uncounted objects are assumed to be shared.
   */
  public static boolean isNotShared(Object x) {
    return (x instanceof RefCounted rc) && rc.isNotShared();
  }

  /** Returns true if this object's reference count is one. */
  public final boolean isNotShared() {
    int cnt = refCnt;
    if (cnt == 0 || cnt == -2) {
      throw new IllegalStateException();
    }
    // A replaced frame should never be updated in place
    return cnt == 1 && !(this instanceof Frame f && f.isReplaced());
  }

  /**
   * Returns this object's reference count. Since this is inherently racy, should only be used for
   * debugging.
   */
  final int getRefCntForDebugging() {
    return refCnt;
  }

  /**
   * Returns true if the given object has a valid reference count or is not refcounted. Only used
   * for assertions.
   */
  static boolean isValidForStore(Object x) {
    if (x instanceof RefCounted rc) {
      int refCnt = rc.refCnt;
      return refCnt > 0 || refCnt == -1;
    }
    return !(x instanceof Value.NotStorable);
  }

  /**
   * Adjusts this object's reference count and returns the new value of refCnt. The object may not
   * be transient.
   *
   * <p>If this object is uncounted it is unchanged. Otherwise {@code delta} is added to refCnt, and
   * the result must be non-negative.
   */
  @CanIgnoreReturnValue
  private int updateRefCnt(int delta) {
    for (; ; ) {
      int cnt = refCnt;
      if (cnt == -1) {
        return cnt;
      }
      int newCnt = cnt + delta;
      if (cnt <= 0 || newCnt < 0) {
        throw new IllegalStateException();
      } else if (REF_CNT_VAR.compareAndSet(this, cnt, newCnt)) {
        return newCnt;
      }
    }
  }
}
