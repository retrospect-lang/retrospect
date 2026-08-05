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

import java.util.function.Function;
import org.retrolang.code.CodeValue;
import org.retrolang.code.Register;
import org.retrolang.impl.Condition.ValueSupplier;

/// ConditionalValue are used only during code generation, and only for a few purposes. A
/// ConditionalValue can be used with setResults(), startCall()/saving(), or jump(), but almost
/// nowhere else.
public abstract class ConditionalValue implements Value {

  /// Converts a ConditionalValue to an RValue, by allocating registers to match the given template
  /// and emitting blocks to store this value in them. Escapes if this value does not fit in the
  /// template.
  public Value materialize(CodeGen codeGen, Template t) {
    return materialize(codeGen, t.toBuilder()::build);
  }

  /// Converts a ConditionalValue to an RValue, by allocating registers using the given builder and
  /// emitting blocks to store this value in them.
  Value materialize(CodeGen codeGen, Function<TemplateBuilder.VarAllocator, Template> builder) {
    int registerStart = codeGen.cb.numRegisters();
    Template dst = builder.apply(codeGen.newAllocator());
    emitStore(codeGen, dst, registerStart, codeGen.cb.numRegisters());
    return RValue.fromTemplate(dst);
  }

  /// Emits blocks to set the registers in `dst` (whose indices must be in the range
  /// `registerStart..registerEnd`) from this ConditionalValue.
  abstract void emitStore(CodeGen codeGen, Template dst, int registerStart, int registerEnd);

  static class Simple extends ConditionalValue {
    private final Condition condition;
    private final ValueSupplier ifTrue;
    private final ValueSupplier ifFalse;

    Simple(Condition condition, ValueSupplier ifTrue, ValueSupplier ifFalse) {
      this.condition = condition;
      this.ifTrue = ifTrue;
      this.ifFalse = ifFalse;
    }

    @Override
    public BaseType baseType() {
      throw new AssertionError();
    }

    @Override
    void emitStore(CodeGen codeGen, Template dst, int registerStart, int registerEnd) {
      condition.test(
          () ->
              codeGen
                  .escapeOnErr(ifTrue)
                  .ifPresent(v -> codeGen.emitStore(v, dst, registerStart, registerEnd)),
          () ->
              codeGen
                  .escapeOnErr(ifFalse)
                  .ifPresent(v -> codeGen.emitStore(v, dst, registerStart, registerEnd)));
    }
  }

  static class Compound extends ConditionalValue {
    private final BaseType baseType;
    private final Object[] elements;

    Compound(BaseType baseType, Object[] elements) {
      this.baseType = baseType;
      this.elements = elements;
    }

    @Override
    public BaseType baseType() {
      return baseType;
    }

    @Override
    void emitStore(CodeGen codeGen, Template dst, int registerStart, int registerEnd) {
      if (dst instanceof Template.Union uDst) {
        int i = uDst.indexOf(baseType.sortOrder);
        if (i < 0) {
          codeGen.escape();
          return;
        }
        dst = uDst.choice(i);
        if (uDst.tag != null) {
          codeGen.emitSet(codeGen.register(uDst.tag), CodeValue.of(i));
        }
      } else if (dst.baseType().sortOrder != baseType.sortOrder) {
        codeGen.escape();
        return;
      }
      if (dst.baseType() != baseType && dst.baseType() != Core.VARRAY) {
        // Different-sized arrays
        codeGen.escape();
        return;
      }
      int n = baseType.size();
      if (dst instanceof Template.RefVar.ForFrame dstVar) {
        FrameLayout frameLayout = dstVar.frameLayout();
        Template[] srcTemplates = new Template[n];
        for (int i = 0; i < n; i++) {
          Value e = (Value) elements[i];
          if (e instanceof ConditionalValue cv) {
            int start = codeGen.cb.numRegisters();
            Template saved =
                frameLayout.elementTemplate(i).toBuilder().build(codeGen.newAllocator());
            cv.emitStore(codeGen, saved, start, codeGen.cb.numRegisters());
            srcTemplates[i] = saved;
          } else {
            srcTemplates[i] = RValue.toTemplate(e);
          }
        }
        Register dstRegister = codeGen.register(dstVar);
        if (frameLayout instanceof RecordLayout layout) {
          codeGen.emitSet(dstRegister, layout.emitAlloc(codeGen));
          CopyPlan copyPlan =
              layout.storePlan(Template.Compound.of(baseType, srcTemplates), layout.template);
          layout.copyTo(dstRegister).emit(codeGen, copyPlan, codeGen.escapeLink());
        } else {
          VArrayLayout layout = (VArrayLayout) frameLayout;
          codeGen.emitSet(dstRegister, layout.emitAlloc(codeGen, CodeValue.of(n)));
          for (int i = 0; i < n; i++) {
            layout.emitSetElement(
                codeGen, dstRegister, CodeValue.of(i), srcTemplates[i], codeGen.escapeLink());
          }
        }
      } else {
        Template.Compound compound = (Template.Compound) dst;
        for (int i = 0; i < n; i++) {
          Template element = compound.element(i);
          Template.IndexBounds bounds = new Template.IndexBounds(element);
          codeGen.emitStore((Value) elements[i], element, bounds.minIndex, bounds.maxIndex + 1);
        }
      }
    }
  }
}
