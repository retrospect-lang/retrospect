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
import java.util.function.IntFunction;
import java.util.function.Predicate;
import org.retrolang.code.CodeValue;
import org.retrolang.code.CodeValue.Const;
import org.retrolang.code.Register;
import org.retrolang.code.TestBlock;
import org.retrolang.code.ValueInfo;
import org.retrolang.impl.Template.Compound;
import org.retrolang.impl.Template.NumVar;
import org.retrolang.impl.Template.RefVar;
import org.retrolang.impl.Template.Union;
import org.retrolang.util.Bits;

/**
 * RValue is a Value implementation that is only used during code generation; an RValue depends on
 * the contents of one or more registers, so its value will only be determined when the generated
 * code is executed.
 */
public class RValue implements Value {

  /**
   * A Template that determines the value of this RValue; each NumVar or RefVar in the template
   * refers to the contents of the register with the same index:
   *
   * <ul>
   *   <li>A NumVar must have encoding INT32 or FLOAT64, and the corresponding register must have
   *       type int or double (respectively).
   *   <li>The register corresponding to a RefVar must have type Object, Value, or (for RefVars with
   *       a non-null FrameLayout) Frame.
   * </ul>
   *
   * <p>May not be EMPTY or a Constant.
   */
  public final Template template;

  private RValue(Template template) {
    assert !Template.isConstant(template);
    this.template = template;
  }

  /**
   * Returns an RValue based on the given template. If the template is constant, just returns the
   * template's value.
   */
  public static Value fromTemplate(Template t) {
    return Template.isConstant(t) ? Template.toValue(t) : new RValue(t);
  }

  /**
   * If {@code v} is an RValue, returns its template; otherwise returns a constant template for
   * {@code v}.
   *
   * <p>{@code v} must not be reference-counted.
   */
  public static Template toTemplate(Value v) {
    return (v instanceof RValue r) ? r.template : Template.Constant.of(v);
  }

  /**
   * Wraps an RValue so that it can be examined without any chance of generating code. If {@code v}
   * is not an RValue, returns it unchanged; otherwise returns a Value that only implements the
   * {@link Value#baseType}, {@link Value#numElements}, and {@link Value#peekElement} methods, where
   *
   * <ul>
   *   <li>{@code baseType()} will return null if the value's baseType may vary at runtime; and
   *   <li>{@code numElements()} will return -1 if the value's size may vary at runtime.
   * </ul>
   */
  public static Value exploreSafely(Value v) {
    return (v instanceof RValue rv) ? SafeExplorer.exploreTemplate(rv.template) : v;
  }

  /**
   * Returns true if the given Value resulted from a call to {@link #exploreSafely} and does not
   * provide complete information about the value.
   */
  public static boolean isExplorer(Value v) {
    return v instanceof SafeExplorer;
  }

  @Override
  public Condition isa(VmType type) {
    return typeTest(type::contains);
  }

  @Override
  public Condition isa(BaseType type) {
    return typeTest(bt -> bt == type);
  }

  @Override
  public Condition is(Singleton singleton) {
    return typeTest(bt -> bt == singleton.baseType);
  }

  @Override
  public Condition isArrayOfLength(int length) {
    return isa(Core.VARRAY)
        .ternary(
            () -> {
              CodeGen codeGen = TState.get().codeGen();
              return Condition.intEq(CodeValue.of(length), codeGen.vArrayLength(this));
            },
            () -> isa(Core.FixedArrayType.withSize(length)));
  }

  @Override
  public Value verifyInt(Err err) throws Err.BuiltinException {
    Err.INVALID_ARGUMENT.unless(isa(Core.NUMBER));
    Template template = this.template;
    if (template instanceof Union u) {
      template = u.choice(u.indexOf(Core.NUMBER.sortOrder));
      if (template instanceof Template.Constant c) {
        return NumValue.verifyInt(c.value, err);
      }
    }
    NumVar nv = (NumVar) template;
    if (nv.encoding != NumEncoding.FLOAT64) {
      // Usually we're just returning this, but if this.template was a union above we have to
      // construct a new RValue for the numeric choice.
      return (template == this.template) ? this : RValue.fromTemplate(template);
    }
    // TODO: emit block to test if double is an int
    throw new UnsupportedOperationException();
  }

  /**
   * Returns an RValue that is False if the given register is 0, True if it is 1.
   *
   * <p>The result is unspecified if the register is not 0 or 1.
   */
  public static RValue intToBoolean(Register r) {
    assert r.type() == int.class;
    return new RValue(
        new Template.Union(
            NumVar.INT32.withIndex(r.index), null, Core.FALSE.asTemplate, Core.TRUE.asTemplate));
  }

  /**
   * Returns a possibly-simplified version of this RValue, assuming the given ValueInfos for each
   * register it references.
   */
  Value simplify(IntFunction<ValueInfo> infos) {
    Value simplified = simplify(template, infos);
    return (simplified != null) ? simplified : this;
  }

  /** Simplifies the given template; returns null if no simplification is possible. */
  private static Value simplify(Template t, IntFunction<ValueInfo> infos) {
    if (t instanceof NumVar nv) {
      ValueInfo info = infos.apply(nv.index);
      if (info instanceof Const c) {
        return NumValue.of((Number) c.value, Allocator.UNCOUNTED);
      }
    } else if (t instanceof RefVar rv) {
      ValueInfo info = infos.apply(rv.index);
      if (info instanceof Const c) {
        return (Value) c.value;
      }
    } else if (t instanceof Union union) {
      Template choice = null;
      if (union.tag != null) {
        ValueInfo info = infos.apply(union.tag.index);
        if (info instanceof Const c) {
          choice = union.choice(c.iValue());
        }
      } else {
        ValueInfo info = infos.apply(union.untagged.index);
        if (info instanceof Const c) {
          return (Value) c.value;
        } else if (PtrInfo.isPtrInfo(info)) {
          long sortOrder = PtrInfo.sortOrder(info);
          choice = union.choice(union.indexOf(sortOrder));
        }
      }
      if (choice != null) {
        Value simplifiedChoice = simplify(choice, infos);
        return (simplifiedChoice != null) ? simplifiedChoice : fromTemplate(choice);
      }
    } else if (t instanceof Compound compound) {
      int size = compound.baseType.size();
      Template[] newElements = null;
      for (int i = 0; i < size; i++) {
        Template e = compound.element(i);
        Value v = simplify(e, infos);
        if (v != null) {
          e = toTemplate(v);
          if (newElements == null) {
            newElements = new Template[size];
            for (int j = 0; j < i; j++) {
              newElements[j] = compound.element(j);
            }
          }
        } else if (newElements == null) {
          continue;
        }
        newElements[i] = e;
      }
      if (newElements != null) {
        return RValue.fromTemplate(Compound.of(compound.baseType, newElements));
      }
    }
    // Not simplifiable (including Constant or EMPTY)
    return null;
  }

  /** Returns a Condition that is true if the given predicate is true of this RValue's baseType. */
  Condition typeTest(Predicate<BaseType> type) {
    // Unless our template is a Union, the baseType is known so we're done.
    if (!(template instanceof Union union)) {
      return Condition.of(type.test(template.baseType()));
    }
    int numChoices = union.numChoices();
    Bits bits = Bits.fromPredicate(numChoices - 1, i -> type.test(union.choice(i).baseType()));
    // If the predicate is true for all possible choices or false for all possible choices, we're
    // done.
    if (bits.isEmpty()) {
      return Condition.FALSE;
    } else if (bits.count() == numChoices) {
      return Condition.TRUE;
    }
    CodeGen codeGen = TState.get().codeGen();
    if (union.tag != null) {
      return Condition.fromTest(
          () -> new TestBlock.TagCheck(codeGen.register(union.tag), numChoices, bits));
    } else {
      return new PtrInfo.TypeTest(union, bits, codeGen.register(union.untagged));
    }
  }

  /**
   * If the given template is a union and the tag register's value is known, return the
   * corresponding choice; otherwise just return the template.
   */
  private static Template resolveUnion(Template template) {
    if (template instanceof Union union) {
      CodeGen codeGen = TState.get().codeGen();
      if (union.tag != null) {
        ValueInfo info = codeGen.cb.nextInfoResolved(union.tag.index);
        if (info instanceof Const c) {
          return union.choice(c.iValue());
        }
      } else {
        ValueInfo info = codeGen.cb.nextInfoResolved(union.untagged.index);
        if (PtrInfo.isPtrInfo(info)) {
          long sortOrder = PtrInfo.sortOrder(info);
          return union.choice(union.indexOf(sortOrder));
        }
      }
    }
    return template;
  }

  @Override
  public BaseType baseType() {
    // If our template is a union, this method should only be called when we have enough information
    // to resolve it.
    return resolveUnion(template).baseType();
  }

  @Override
  public Value element(int index) {
    // We're not concerned with refCounts here, so element() and peekElement() are equivalent.
    return peekElement(index);
  }

  @Override
  public Value peekElement(int index) {
    Template template = resolveUnion(this.template);
    if (!(template instanceof RefVar refVar)) {
      return fromTemplate(resolveUnion(template.element(index)));
    }
    FrameLayout layout = refVar.frameLayout();
    CodeGen codeGen = TState.get().codeGen();
    Register register = codeGen.register(refVar);
    codeGen.ensureLayout(register, layout);
    // Allocate registers to hold the element's NumVars and RefVars
    Template element = layout.elementTemplate(index);
    int registersStart = codeGen.cb.numRegisters();
    Template result = element.toBuilder().build(codeGen.newAllocator());
    int registersEnd = codeGen.cb.numRegisters();
    // Create a (very simple) plan to copy elements out of the frame into the
    // corresponding registers, and emit it.
    CopyPlan plan = CopyPlan.create(element, result);
    plan = CopyOptimizer.toRegisters(plan, registersStart, registersEnd, result);
    layout.copyFrom(register, index).emit(codeGen, plan, codeGen.escapeLink());
    return new RValue(result);
  }

  @Override
  public FrameLayout layout() {
    Template template = resolveUnion(this.template);
    return (template instanceof RefVar rv) ? rv.frameLayout() : null;
  }

  @Override
  public Value replaceElement(TState tstate, int index, Value newElement) {
    Template template = resolveUnion(this.template);
    if (!(template instanceof RefVar rv)) {
      BaseType baseType = template.baseType();
      Template[] newElements = new Template[baseType.size()];
      Arrays.setAll(newElements, i -> (i == index) ? toTemplate(newElement) : template.element(i));
      return RValue.fromTemplate(Compound.of(baseType, newElements));
    }
    CodeGen codeGen = tstate.codeGen();
    Register result =
        rv.frameLayout()
            .emitReplaceElement(codeGen, codeGen.register(rv), CodeValue.of(index), newElement);
    return RValue.fromTemplate(rv.withIndex(result.index));
  }

  /** Instances of SafeExplorer are created by calls ot {@link #exploreSafely}. */
  private record SafeExplorer(Template template) implements Value {

    /** A SafeExplorer that has no information about its Value. */
    static final SafeExplorer UNKNOWN = new SafeExplorer(null);

    /** Returns a SafeExplorer for an RValue with the given Template. */
    static SafeExplorer exploreTemplate(Template t) {
      // If t is RefVar with a RecordLayout, we want the template of that RecordLayout instead
      if (t instanceof Template.RefVar rv
          && rv.baseType().isCompositional()
          && rv.frameLayout() instanceof RecordLayout layout) {
        t = layout.template;
      }
      assert !Template.isConstant(t);
      return (t instanceof Union) ? UNKNOWN : new SafeExplorer(t);
    }

    @Override
    public BaseType baseType() {
      return (template == null) ? null : template.baseType();
    }

    @Override
    public int numElements() {
      if (template == null) {
        return -1;
      }
      BaseType baseType = template.baseType();
      assert baseType.isArray();
      return baseType.isCompositional() ? baseType.size() : -1;
    }

    @Override
    public Value peekElement(int index) {
      if (template == null) {
        return UNKNOWN;
      } else if (template instanceof RefVar rv) {
        VArrayLayout layout = (VArrayLayout) rv.frameLayout();
        return exploreTemplate(layout.template);
      }
      Template element = template.element(index);
      return Template.isConstant(element) ? Template.toValue(element) : exploreTemplate(element);
    }
  }
}
