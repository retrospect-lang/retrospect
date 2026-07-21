package org.retrolang.impl;

import org.retrolang.code.CodeBuilder;
import org.retrolang.code.CodeValue;
import org.retrolang.code.FutureBlock;
import org.retrolang.code.Op;
import org.retrolang.code.Register;
import org.retrolang.code.TestBlock;

public class StoreEmitter {
  /**
   * {@code src} is a Value object; {@code dst} was built from a ValueMemo. If harmonizing {@code
   * src} with the ValueMemo would not change the ValueMemo, emit instructions that set the
   * registers in {@code dst} to match {@code src}; otherwise escape.
   */
  public static void store(CodeGen codeGen, Template dst, CodeValue src, FutureBlock onFail) {
    switch (dst) {
      case Template.Constant c -> {
        codeGen.testEqualsObj(CodeValue.of(c.value), src, true, onFail);
      }
      case Template.NumVar nv -> {
        Op op;
        if (nv.encoding == NumEncoding.FLOAT64) {
          new PtrInfo.TestClass(src, Core.NUMBER).setBranch(false, onFail).addTo(codeGen.cb);
          op = NumValue.AS_DOUBLE_OP;
        } else {
          Condition.isNonZero(NumValue.IS_INT_OP.result(src)).addTest(codeGen, onFail);
          op = NumValue.AS_INT_OP;
        }
        codeGen.emitSet(codeGen.register(nv), op.result(src));
      }
      case Template.RefVar rv -> {
        testType(rv, src).setBranch(false, onFail).addTo(codeGen.cb);
        codeGen.emitSet(codeGen.register(rv), src);
      }
      case Template.Compound compound -> {
        // src must not be a Frame, and must have the right baseType
        new PtrInfo.TestClass(src, null).setBranch(true, onFail).addTo(codeGen.cb);
        testEq(compound.baseType, Value.BASE_TYPE.result(src))
            .setBranch(false, onFail)
            .addTo(codeGen.cb);
        int n = compound.baseType.size();
        for (int i = 0; i < n; i++) {
          CodeValue element = Value.PEEK_ELEMENT.result(src, CodeValue.of(i));
          element = codeGen.materialize(element, Value.class);
          Template t = compound.element(i);
          store(codeGen, t, element, onFail);
        }
      }
      case Template.Union union -> {
        FutureBlock success = new FutureBlock();
        for (int i = 0; i < union.numChoices(); i++) {
          Template choice = union.choice(i);
          if (union.tag == null) {
            TestBlock test =
                switch (choice) {
                  case Template.RefVar rv -> testType(rv, src);
                  case Template.Constant c -> {
                    assert c.value instanceof Singleton;
                    yield testEq(c.value, src);
                  }
                  default -> throw new AssertionError();
                };
            test.setBranch(true, success).addTo(codeGen.cb);
          } else {
            FutureBlock next = new FutureBlock();
            store(codeGen, choice, src, next);
            codeGen.emitSet(codeGen.register(union.tag), CodeValue.of(i));
            codeGen.cb.branchTo(success);
            codeGen.cb.setNext(next);
          }
        }
        codeGen.cb.branchTo(onFail);
        codeGen.cb.setNext(success);
        if (union.tag == null) {
          codeGen.emitSet(codeGen.register(union.untagged), src);
        }
      }
      default -> {
        assert dst == Template.EMPTY;
        codeGen.cb.branchTo(onFail);
      }
    }
  }

  private static TestBlock testEq(Object x, CodeValue src) {
    return new TestBlock.IsEq(CodeBuilder.OpCodeType.OBJ, CodeValue.of(x), src);
  }

  private static TestBlock testType(Template.RefVar rv, CodeValue src) {
    if (rv instanceof Template.RefVar.ForFrame ff) {
      return testEq(ff.frameLayout(), Value.LAYOUT.result(src));
    } else {
      return new PtrInfo.TestClass(src, (BaseType.NonCompositional) rv.baseType);
    }
  }

  /** Returns {@code src} as a Value-typed CodeValue. */
  public static CodeValue toCodeValue(CodeGen codeGen, Template src) {
    return switch (src) {
      case Template.Constant c -> CodeValue.of(c.value);
      case Template.NumVar nv -> {
        Op op = (nv.encoding == NumEncoding.FLOAT64) ? NumValue.OF_DOUBLE_OP : NumValue.OF_INT_OP;
        yield op.result(codeGen.register(nv), codeGen.tstateRegister());
      }
      case Template.RefVar rv -> codeGen.register(rv);
      case Template.Compound compound -> {
        int n = compound.baseType.size();
        Register array = codeGen.cb.newRegister(Object[].class);
        codeGen.emitSet(
            array, TState.ALLOC_OBJ_ARRAY_OP.result(codeGen.tstateRegister(), CodeValue.of(n)));
        for (int i = 0; i < n; i++) {
          RcOp.SET_OBJ_ARRAY_ELEMENT
              .block(array, CodeValue.of(i), toCodeValue(codeGen, compound.element(i)))
              .addTo(codeGen.cb);
        }
        yield CompoundValue.CREATE.result(
            codeGen.tstateRegister(), CodeValue.of(compound.baseType), array);
      }
      case Template.Union union -> {
        if (union.untagged != null) {
          yield codeGen.register(union.untagged);
        }
        Register result = codeGen.cb.newRegister(Value.class);
        Register tag = codeGen.register(union.tag);
        FutureBlock done = new FutureBlock();
        int last = union.numChoices() - 1;
        for (int i = 0; ; i++) {
          FutureBlock next = null;
          if (i < last) {
            next = new FutureBlock();
            new TestBlock.IsEq(CodeBuilder.OpCodeType.INT, tag, CodeValue.of(i))
                .setBranch(false, next)
                .addTo(codeGen.cb);
          }
          codeGen.emitSet(result, toCodeValue(codeGen, union.choice(i)));
          if (next == null) {
            break;
          }
          codeGen.cb.branchTo(done);
          codeGen.cb.setNext(next);
        }
        codeGen.cb.mergeNext(done);
        yield result;
      }
      default -> throw new AssertionError();
    };
  }
}
