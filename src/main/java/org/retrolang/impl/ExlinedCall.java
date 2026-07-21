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

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.function.Consumer;
import org.retrolang.code.Block;
import org.retrolang.code.CodeBuilder;
import org.retrolang.code.CodeValue;
import org.retrolang.code.CodeValue.Const;
import org.retrolang.code.ConditionalBranch;
import org.retrolang.code.Emitter;
import org.retrolang.code.FutureBlock;
import org.retrolang.code.Op;
import org.retrolang.code.Register;
import org.retrolang.code.ValueInfo;
import org.retrolang.impl.Template.NumVar;
import org.retrolang.impl.Template.RefVar;

/**
 * A custom Block type for an exlined call to a code-generated method.
 *
 * <p>In addition to the call itself (just a call to a MethodHandle) this emits two additional
 * steps:
 *
 * <ul>
 *   <li>It calls {@link TState#checkExlinedResult} in case the generated code escaped, and to
 *       distinguish "returned results using the expected templates" (we continue to the next block)
 *       from "errored, blocked, or returned unexpected results" (we take the alternate link, which
 *       unwinds).
 *   <li>After calling checkExlinedResult it calls {@link TState#takeStackRest} and stores the
 *       result in a register; if it returned non-null we will need to fill the stack entry to
 *       describe this call.
 * </ul>
 */
class ExlinedCall extends Block.Split {
  /** An Op.Result that calls the exlined generated code with appropriate args. */
  private Op.Result call;

  /** The expected representation for the exlined method's results. */
  final ImmutableList<Template> expectedResultsTemplate;

  /**
   * The register in which we will store the result of {@link TState#takeStackRest}, or null if this
   * is a detached call.
   */
  final Register stackRest;

  ExlinedCall(
      Op.Result call,
      ImmutableList<Template> expectedResultsTemplate,
      Register stackRest,
      FutureBlock unwind) {
    assert call.type() == void.class;
    this.call = call;
    this.expectedResultsTemplate = expectedResultsTemplate;
    this.stackRest = stackRest;
    alternate.setTarget(unwind);
  }

  @Override
  public int numInputs() {
    return 1;
  }

  @Override
  public CodeValue input(int index) {
    assert index == 0;
    return call;
  }

  @Override
  public void setInput(int index, CodeValue input) {
    assert index == 0;
    // The arguments might have been simplified but the top-level op should be unchanged
    assert call.op == ((Op.Result) input).op;
    call = (Op.Result) input;
  }

  @Override
  public void forEachModifiedRegister(Consumer<Register> consumer) {
    if (stackRest != null) {
      consumer.accept(stackRest);
    }
  }

  @Override
  protected PropagationResult updateInfo() {
    simplifyInputs(next.info.registers());
    if (stackRest != null) {
      // Our only output register (stackRest) could have any value, on either branch.
      next.info.updateForAssignment(stackRest, ValueInfo.ANY, cb());
      next.info.modified(stackRest, containingLoop());
      alternate.info.updateForAssignment(stackRest, ValueInfo.ANY, cb());
      alternate.info.modified(stackRest, containingLoop());
    }
    return PropagationResult.DONE;
  }

  @Override
  protected SubstitutionOutcome trySubstitute(Register register, CodeValue value) {
    if (next.isLive(register) || alternate.isLive(register)) {
      return SubstitutionOutcome.NO;
    }
    SubstitutionOutcome result = call.couldSubstitute(register, value);
    if (result == SubstitutionOutcome.YES) {
      call = (Op.Result) call.substitute(register, value);
    }
    return result;
  }

  @Override
  public Block emit(Emitter emitter) {
    call.push(emitter, void.class);
    Register tstate = CodeGen.tstateRegister(emitter.cb);
    // Call checkExlinedResult() and just leave the boolean it returns on the stack
    TState.CHECK_EXLINED_RESULT_OP
        .result(tstate, Const.of(expectedResultsTemplate))
        .push(emitter, int.class);
    // Before we check whether that succeeded, take stackRest from TState and save it
    if (stackRest != null) {
      TState.TAKE_STACK_REST_OP.result(tstate).push(emitter, Object.class);
      emitter.store(stackRest);
    }
    // Now take the alternate link if checkExlinedResult() returned false (aka 0)
    return emitter.conditionalBranch(ConditionalBranch.IFEQ, alternate, next);
  }

  @Override
  public String toString(CodeBuilder.PrintOptions options) {
    String stackUpdate =
        (stackRest == null)
            ? " (detached)"
            : String.format("; %s ← stackRest", stackRest.toString(options));
    return String.format("%s ← %s%s", expectedResultsTemplate, call.toString(options), stackUpdate);
  }

  @Override
  public String linksToString(CodeBuilder.PrintOptions options) {
    String nextLink =
        options.isLinkToNextBlock(next) ? "" : String.format(" %s,", next.toString(options));
    return String.format(";%s unwind:%s", nextLink, alternate.toString(options));
  }

  /** Emit the code to call an exlined method. */
  static void emitCall(CodeGen codeGen, CodeGenTarget target, Object[] args) {
    int numArgs = target.args.size();
    CodeBuilder cb = codeGen.cb;
    // First allocate registers for each arg.
    // (Do them all before any calls to emitStore() to ensure that they are contiguous.)
    TemplateBuilder.VarAllocator alloc = codeGen.newAllocator();
    Template[] argTemplates = new Template[numArgs];
    int[] argRegisterStart = new int[numArgs + 1];
    int firstArgRegister = cb.numRegisters();
    argRegisterStart[0] = firstArgRegister;
    for (int i = 0; i < numArgs; i++) {
      argTemplates[i] = target.args.get(i).toBuilder().build(alloc);
      argRegisterStart[i + 1] = cb.numRegisters();
    }
    // Then copy the argument values into those registers...
    for (int i = 0; i < numArgs; i++) {
      codeGen.emitStore(
          (Value) args[i], argTemplates[i], argRegisterStart[i], argRegisterStart[i + 1]);
    }
    // ... and emit the call.
    CodeValue[] argRegisters = new CodeValue[1 + argRegisterStart[numArgs] - firstArgRegister];
    argRegisters[0] = codeGen.tstateRegister();
    for (int i = 1; i < argRegisters.length; i++) {
      argRegisters[i] = cb.register(firstArgRegister + i - 1);
    }
    CodeGen.CurrentCall currentCall = codeGen.currentCall();
    // This is the codegen version of these lines in TState.finishBuiltin():
    //     if (caller.isDetached()) {
    //       // If the called code unwinds or traces, its stack ends here.
    //       setStackRest(TStack.BASE);
    //     }
    if (currentCall.stackRest() == null) {
      TState.SET_STACK_REST_OP.block(codeGen.tstateRegister(), Const.of(TStack.BASE)).addTo(cb);
    }
    new ExlinedCall(
            (Op.Result) target.op.result(argRegisters),
            target.results,
            currentCall.stackRest(),
            currentCall.handleUnwind())
        .addTo(cb);
    codeGen.invalidateEscape();
    // If we fall through, the exlined method returned values with the expected template;
    // copy them into the destination registers.
    FromFnResults resultEmitter = new FromFnResults(codeGen, target.results);
    for (int i = 0; i < target.results.size(); i++) {
      CopyPlan plan = currentCall.done.createCopyPlan(codeGen, i, target.results.get(i));
      resultEmitter.emit(codeGen, plan, currentCall.handleUnwind());
      if (!codeGen.cb.nextIsReachable()) {
        // This call has never returned, so we can't generate any of the following code.
        return;
      }
    }
    resultEmitter.clearResults(codeGen);
    currentCall.done.addBranch(codeGen);
  }

  /** A CopyEmitter that reads source NumVars and RefVars from the TState's function results. */
  private static class FromFnResults extends CopyEmitter implements Template.VarVisitor {
    private boolean hasNumVar;
    private int maxRefVar = -1;

    final Register fnResultBytes;
    final Register[] fnResults;

    FromFnResults(CodeGen codeGen, List<Template> templates) {
      for (Template t : templates) {
        Template.visitVars(t, this);
      }
      // Copy each of the Value results into a register before we start, since doing so involves an
      // addRef.
      if (maxRefVar < 0) {
        fnResults = null;
      } else {
        fnResults = new Register[maxRefVar + 1];
        for (int i = 0; i <= maxRefVar; i++) {
          Register r = codeGen.cb.newRegister(Value.class);
          fnResults[i] = r;
          codeGen.emitSet(r, TState.FN_RESULT_OP.result(codeGen.tstateRegister(), CodeValue.of(i)));
        }
      }
      fnResultBytes = hasNumVar ? codeGen.fnResultBytes(0) : null;
    }

    void clearResults(CodeGen codeGen) {
      Op clearOp = (fnResults != null) ? TState.CLEAR_RESULTS_OP : TState.CLEAR_RESULT_TEMPLATES_OP;
      clearOp.block(codeGen.tstateRegister()).addTo(codeGen.cb);
    }

    @Override
    public void visitNumVar(NumVar v) {
      hasNumVar = true;
    }

    @Override
    public void visitRefVar(RefVar v) {
      maxRefVar = Math.max(maxRefVar, v.index);
    }

    @Override
    CodeValue getSrcVar(CodeGen codeGen, Template t) {
      if (t instanceof NumVar numVar) {
        Op accessOp =
            switch (numVar.encoding) {
              case UINT8 -> Op.UINT8_ARRAY_ELEMENT;
              case INT32 -> CodeGen.INT_FROM_BYTES_OP;
              case FLOAT64 -> CodeGen.DOUBLE_FROM_BYTES_OP;
            };
        CodeValue offset = CodeValue.of(numVar.index);
        return accessOp.result(fnResultBytes, offset);
      } else {
        return fnResults[((RefVar) t).index];
      }
    }
  }
}
