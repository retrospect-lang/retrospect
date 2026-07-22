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

import org.objectweb.asm.Opcodes;
import org.retrolang.code.CodeBuilder;
import org.retrolang.code.CodeBuilder.PrintOptions;
import org.retrolang.code.CodeValue;
import org.retrolang.code.CodeValue.Const;
import org.retrolang.code.ConditionalBranch;
import org.retrolang.code.Emitter;
import org.retrolang.code.FutureBlock;
import org.retrolang.code.Register;
import org.retrolang.code.TestBlock;
import org.retrolang.code.ValueInfo;
import org.retrolang.util.Bits;

/**
 * A ValueInfo that represents information about a pointer-valued register. The implementations of
 * PtrInfo include
 *
 * <ul>
 *   <li>BaseType.NonCompositional: when used as a PtrInfo represents that the pointer is an
 *       instance of the baseType's java class.
 *   <li>FrameLayout: when used as a PtrInfo represents that the pointer is a Frame with that
 *       layout.
 *   <li>IsNotShared: the pointer is a Frame (with a specified layout) for which {@link
 *       RefCounted#isNotShared()} returns true.
 *   <li>Non-array BaseType with size &gt; 0: when used as a PtrInfo represents that the pointer is
 *       a Frame using a RecordLayout with that BaseType.
 *   <li>Array BaseType with size &gt; 0: when used as a PtrInfo represents that the pointer is a
 *       Frame representing an array (but due to layout evolution it may no longer be the same
 *       baseType).
 * </ul>
 */
public interface PtrInfo extends ValueInfo {

  BaseType baseType();

  static boolean isPtrInfo(ValueInfo info) {
    if (info instanceof PtrInfo) {
      return true;
    } else if (info instanceof Const c) {
      if (c.value instanceof Value v) {
        assert v instanceof Frame || !v.baseType().usesFrames();
        return true;
      }
    }
    return false;
  }

  // isPtrInfo(info) must be true
  static long sortOrder(ValueInfo info) {
    return baseType(info).sortOrder;
  }

  static BaseType baseType(ValueInfo info) {
    if (info instanceof PtrInfo pi) {
      return pi.baseType();
    } else {
      return ((Value) ((Const) info).value).baseType();
    }
  }

  // isPtrInfo() should be true of each argument
  static ValueInfo union(ValueInfo info1, ValueInfo info2) {
    if (info1 == CodeValue.NULL) {
      return info2;
    } else if (info2 == CodeValue.NULL) {
      return info1;
    }
    long o1 = sortOrder(info1);
    long o2 = sortOrder(info2);
    if (o1 == o2) {
      return unionSameOrder(info1, info2);
    } else {
      // TODO: implement Multi
      return ANY;
    }
  }

  // isPtrInfo() should be true of each argument
  static ValueInfo intersection(ValueInfo info1, ValueInfo info2) {
    assert sortOrder(info1) == sortOrder(info2);
    // Prefer Const or IsNotShared over FrameLayout over CurrentFrame over BaseType, and prefer
    // info1 over info2
    if (info1 == info2 || info2 instanceof BaseType) {
      return info1;
    } else if (info1 instanceof BaseType) {
      return info2;
    } else if (info2 instanceof FrameLayout) {
      // The only surprising case here is that we could end up with two distinct FrameLayouts; that
      // should only be possible if we've raced against another thread that's evolving them as we
      // generate code.
      assert info1 instanceof Const
          || info1 instanceof IsNotShared
          || ((FrameLayout) info1).hasEvolved()
          || ((FrameLayout) info2).hasEvolved();
      return info1;
    } else if (info1 instanceof FrameLayout) {
      return info2;
    } else {
      assert info1.equals(info2);
      return info1;
    }
  }

  // isPtrInfo() should be true of each argument
  static boolean intersects(ValueInfo info1, ValueInfo info2) {
    if (info1 == info2) {
      return true;
    } else if (info1 instanceof Const && info2 instanceof Const) {
      return info1.equals(info2);
    } else {
      return sortOrder(info1) == sortOrder(info2);
    }
  }

  // isPtrInfo() should be true of each argument
  static boolean containsAll(ValueInfo info1, ValueInfo info2) {
    return intersection(info2, info1) == info2;
  }

  /** Converts an IsNotShared or Const to a FrameLayout or (for non-Frame constants) BaseType. */
  private static ValueInfo widen(ValueInfo ptrInfo) {
    if (ptrInfo instanceof IsNotShared) {
      return ((IsNotShared) ptrInfo).layout;
    } else if (ptrInfo instanceof Const) {
      Value v = (Value) ((Const) ptrInfo).value;
      if (v == Core.EMPTY_ARRAY) {
        return ptrInfo;
      }
      if (v instanceof Frame) {
        return v.layout();
      }
      assert !(v.baseType().isCompositional() || v.baseType().isArray());
      return v.baseType();
    } else {
      assert ptrInfo instanceof FrameLayout;
      return ptrInfo;
    }
  }

  private static ValueInfo unionSameOrder(ValueInfo info1, ValueInfo info2) {
    // Const and IsNotShared override equals(), for everything else this is an == check
    if (info1.equals(info2) || info1 instanceof BaseType) {
      return info1;
    } else if (info2 instanceof BaseType) {
      return info2;
    }
    info1 = widen(info1);
    info2 = widen(info2);
    if (info1 == info2 || info2 instanceof Const) {
      return info1;
    } else if (info1 instanceof Const) {
      return info2;
    } else {
      // We can get here due to unions, or possibly because FrameLayouts are evolving while we
      // generate code.
      return ValueInfo.ANY;
    }
  }

  /** Returns true if the given ValueInfo implies that the value is unshared. */
  public static boolean isUnshared(ValueInfo info) {
    return info instanceof IsNotShared;
  }

  /**
   * A PtrInfo indicating that the pointer is a reference to a Frame with the given layout and for
   * which {@link RefCounted#isNotShared()} returns true.
   */
  class IsNotShared implements PtrInfo {
    final FrameLayout layout;

    IsNotShared(FrameLayout layout) {
      this.layout = layout;
    }

    @Override
    public BaseType baseType() {
      return layout.baseType();
    }

    @Override
    public boolean containsValue(Object value) {
      // Constant values must be uncounted, so can't be unshared.
      return false;
    }

    @Override
    public ValueInfo unionConst(CodeValue.Const constInfo) {
      return layout.unionConst(constInfo);
    }

    // We create a single canonical IsNotShared instance for each FrameLayout, so we don't need to
    // implement equals()

    @Override
    public String toString() {
      return String.format("notShared(%s)", layout);
    }
  }

  /**
   * Tests the Java class of a CodeValue, looking either for Frame or for the Java class of a {@link
   * BaseType.NonCompositional}.
   */
  static class TestClass extends TestBlock {
    final BaseType.NonCompositional baseType;

    /** If baseType is null, test for Frame; otherwise test for the corresponding java type.. */
    TestClass(CodeValue value, BaseType.NonCompositional baseType) {
      super(value, null);
      assert baseType == null || baseType.javaType != null;
      this.baseType = baseType;
    }

    private boolean testPtrInfo(ValueInfo info) {
      BaseType ptrBaseType = baseType(info);
      if (ptrBaseType.usesFrames()) {
        return baseType == null;
      } else {
        return baseType == ptrBaseType;
      }
    }

    @Override
    protected Boolean test(ValueInfo info, ValueInfo info2) {
      assert info2 == null;
      if (isPtrInfo(info)) {
        return testPtrInfo(info);
      }
      return null;
    }

    @Override
    protected void updateInfos(ValueInfo info, ValueInfo info2) {
      assert info2 == null;
      if (value1 instanceof Register r) {
        if (baseType != null) {
          next.info.updateInfo(r, baseType);
        }
      }
    }

    @Override
    public ConditionalBranch emitTest(Emitter emitter) {
      Class<?> type = (baseType == null) ? Frame.class : baseType.javaType;
      value1.push(emitter, Object.class);
      emitter.mv.visitTypeInsn(Opcodes.INSTANCEOF, org.objectweb.asm.Type.getInternalName(type));
      return ConditionalBranch.IFNE;
    }

    @Override
    public String toString(PrintOptions options) {
      String className = (baseType != null) ? baseType.javaType.getSimpleName() : "Frame";
      return String.format("test %s instanceof %s", value1.toString(options), className);
    }
  }

  /**
   * A TestBlock that checks if a Frame-typed CodeValue has the specified FrameLayout.
   *
   * <p>Note that although this test takes two values ({@code frame} and {@code layoutValue}) it is
   * only testing one of them; {@code layoutValue} must be the result of {@link
   * Frame#GET_LAYOUT_OR_REPLACEMENT} applied to {@code frame}. (We emit code that references {@code
   * layoutValue}, but any info is attached to {@code frame}).
   */
  static class TestLayout extends TestBlock {
    final FrameLayout layout;

    TestLayout(CodeValue frame, CodeValue layoutValue, FrameLayout layout) {
      super(frame, layoutValue);
      assert layout != null;
      this.layout = layout;
    }

    @Override
    protected Boolean test(ValueInfo info1, ValueInfo info2) {
      return test(info1, layout);
    }

    static Boolean test(ValueInfo info, FrameLayout layout) {
      if (info == layout) {
        return true;
      } else if (info instanceof IsNotShared ins && ins.layout == layout) {
        return true;
      } else if (info instanceof CodeValue.Const c) {
        return ((Value) c.value).layout() == layout;
      } else {
        return null;
      }
    }

    @Override
    protected void updateInfos(ValueInfo info1, ValueInfo info2) {
      if (value1 instanceof Register r) {
        next.info.updateInfo(r, layout);
      }
    }

    @Override
    public ConditionalBranch emitTest(Emitter emitter) {
      value2.push(emitter, Object.class);
      emitter.pushX(layout, FrameLayout.class);
      return ConditionalBranch.IF_ACMPEQ;
    }

    @Override
    public String toString(PrintOptions options) {
      return String.format("test %s is %s", value1.toString(options), layout);
    }
  }

  private static boolean isFrame(Template t) {
    return (t instanceof Template.RefVar rv) && rv.baseType.usesFrames();
  }

  /**
   * A condition that is true if a register holding an untagged union is one of a specified subset
   * of the union's choices.
   */
  static class TypeTest extends Condition {
    final Template.Union union;
    final Bits bits;
    final Register register;

    TypeTest(Template.Union union, Bits bits, Register register) {
      assert union.untagged != null
          && bits.max() < union.numChoices()
          && bits.count() < union.numChoices();
      this.union = union;
      this.bits = bits;
      this.register = register;
    }

    @Override
    public void addTest(CodeGen codeGen, FutureBlock elseBranch) {
      FutureBlock pass = new FutureBlock();
      // Split the choices we're checking for into frames and non-frames
      Bits frames = Bits.fromPredicate(union.numChoices() - 1, i -> isFrame(union.choice(i)));
      Bits frameCheck = Bits.Op.INTERSECTION.apply(bits, frames);
      Bits nonFrameCheck = Bits.Op.DIFFERENCE.apply(bits, frames);
      if (nonFrameCheck.count() + frames.count() < union.numChoices()) {
        // Some non-frame choices are excluded, so we need to explicitly test for those that are in.
        for (int i : nonFrameCheck) {
          Template choice = union.choice(i);
          if (choice instanceof Template.Constant c && c.value instanceof Singleton) {
            // Singletons can be checked with an IF_ACMPEQ instruction
            new TestBlock.IsEq(CodeBuilder.OpCodeType.OBJ, register, CodeValue.of(c.value))
                .setBranch(true, pass)
                .addTo(codeGen.cb);
          } else {
            // If it's not a singleton and not a frame it must be a noncompositional type (like
            // String or Future), which we can check with an INSTANCEOF instruction
            BaseType.NonCompositional baseType =
                (BaseType.NonCompositional) ((Template.RefVar) choice).baseType;
            new TestClass(register, baseType).setBranch(true, pass).addTo(codeGen.cb);
          }
        }
        if (!frameCheck.isEmpty()) {
          // Any remaining non-frame cases are excluded
          new TestClass(register, null).setBranch(false, elseBranch).addTo(codeGen.cb);
        }
      } else if (!nonFrameCheck.isEmpty()) {
        // All non-frames are good
        new TestClass(register, null).setBranch(false, pass).addTo(codeGen.cb);
      }
      if (!frameCheck.isEmpty()) {
        // At this point we've ensured that the register points to a frame
        if (frameCheck.equals(frames)) {
          // All frames are good
          codeGen.cb.mergeNext(pass);
          return;
        }
        // Some frames are in and some are out, so we need to check the layout
        Register layoutRegister = codeGen.cb.newRegister(FrameLayout.class);
        codeGen.emitSet(layoutRegister, Frame.GET_LAYOUT_OR_REPLACEMENT.result(register));
        for (int i : frameCheck) {
          FrameLayout layout = ((Template.RefVar.ForFrame) union.choice(i)).frameLayout();
          new PtrInfo.TestLayout(register, layoutRegister, layout)
              .setBranch(true, pass)
              .addTo(codeGen.cb);
        }
      }
      codeGen.cb.branchTo(elseBranch);
      codeGen.cb.setNext(pass);
    }
  }
}
