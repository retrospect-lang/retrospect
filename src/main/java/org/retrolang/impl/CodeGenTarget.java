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
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.retrolang.code.CodeBuilder;
import org.retrolang.code.CodeValue;
import org.retrolang.code.FutureBlock;
import org.retrolang.code.Op;
import org.retrolang.code.Register;
import org.retrolang.code.ReturnBlock;
import org.retrolang.code.SetBlock;
import org.retrolang.code.TestBlock;
import org.retrolang.impl.Template.VarSink;
import org.retrolang.impl.TemplateBuilder.VarAllocator;
import org.retrolang.util.Bits;

/**
 * A CodeGenTarget is used to configure a CodeGen. Once code generation is complete it will become
 * the current target of a CodeGenLink until we decide to regenerate the code.
 */
class CodeGenTarget {

  /**
   * The number of times we have entered this generated code. Only maintained if {@link
   * CodeGenGroup.Monitor#countCalls} returns true.
   */
  private volatile int callCount;

  /**
   * The CodeGenLink that points (or pointed to) this target.
   *
   * <p>When first created, a CodeGenTarget is the link's "next" target (only visible to the code we
   * are generating, not to already-running code).
   *
   * <p>Once the CodeGenGroup is completed it will be promoted to be the link's "ready" target.
   *
   * <p>If a subsequent CodeGenGroup regenerates the link the newer version (when completed) will
   * replace this target, which should eventually be garbage-collected.
   */
  final CodeGenLink link;

  /**
   * Used to identify this target in code listings; ideally will be unique, but nothing depends on
   * that. Must be a valid JVM method name, i.e. must contain at least one character and must not
   * contain any of "{@code .;[/<>}".
   */
  final String name;

  /**
   * A template for each of the args; numvar and refvar indices refer to the generated method's
   * arguments, so will be between 1 and {@link #numJavaArgs} (argument 0 is always the TState).
   */
  final ImmutableList<Template> args;

  /**
   * A template for each of the results; numvar indices are offsets into {@link
   * TState#fnResultBytes} and refvar indices are positions in {@link TState#fnResults}.
   */
  final ImmutableList<Template> results;

  /** The number of (Java) arguments that the generated method expects. */
  final int numJavaArgs;

  /** The maximum number of bytes required to represent the generated code's numeric results. */
  final int resultByteSize;

  /** The maximum number of pointers required to represent the generated code's Value results. */
  final int resultObjSize;

  /** A CallSite that will be linked to the generated code when it has been loaded. */
  final MutableCallSite mhCaller;

  /** An Op that will call the generated code. */
  final Op op;

  enum State {
    /** This target's MethodHandle has not yet been set and should not be used. */
    NEW,
    /** Code has been generated for this target and its MethodHandle is now safe to use. */
    READY,
    /**
     * The code originally generated for this target has been superseded, and its MethodHandle now
     * points to the {@link #fallback} method.
     */
    FALLBACK,
    /**
     * The code originally generated for this target has been superseded and its MethodHandle now
     * points to a wrapped version of the newer code.
     */
    FORWARDING
  }

  State state = State.NEW;

  /**
   * A counter that is incremented each time this target's generated code escapes and then completes
   * or resumes without making changes to the MethodMemo, and reset each time some part of the
   * memo's structure changes in a way that might affect code generation. When this counter reaches
   * a threshold it is probably worth re-generating the code (by creating a new CodeGenTarget for
   * our link).
   */
  private volatile int stabilityCounter;

  /** The number of times we have started running this generated code from interpreted code. */
  private volatile int entryCount;

  /**
   * The number of times we have been unable to run this generated code from interpreted code
   * because argument values could not be represented.
   */
  private volatile int rejectCount;

  /**
   * The CodeGen instance used to generate this code. Set to null when code generation completes.
   */
  CodeGen codeGen;

  private static final VarHandle CALL_COUNT_VAR;
  private static final VarHandle STABILITY_COUNTER_VAR;
  private static final VarHandle ENTRY_COUNT_VAR;
  private static final VarHandle REJECT_COUNT_VAR;

  private static final Op INCREMENT_CALL_COUNT;
  private static final MethodHandle FALLBACK;

  static {
    var lookup = MethodHandles.lookup();
    CALL_COUNT_VAR = Handle.forVar(lookup, CodeGenTarget.class, "callCount", int.class);
    STABILITY_COUNTER_VAR =
        Handle.forVar(lookup, CodeGenTarget.class, "stabilityCounter", int.class);
    ENTRY_COUNT_VAR = Handle.forVar(lookup, CodeGenTarget.class, "entryCount", int.class);
    REJECT_COUNT_VAR = Handle.forVar(lookup, CodeGenTarget.class, "rejectCount", int.class);
    INCREMENT_CALL_COUNT =
        Op.forMethod(lookup, CodeGenTarget.class, "incrementCallCount").hasSideEffect().build();
    FALLBACK = Handle.forMethod(lookup, CodeGenTarget.class, "fallback", Object[].class);
  }

  CodeGenTarget(
      String name,
      CodeGenGroup group,
      CodeGenLink link,
      CodeGenTarget previous,
      Function<VarAllocator, ImmutableList<Template>> argBuilder,
      Function<VarAllocator, ImmutableList<Template>> resultBuilder) {
    this.name = name;
    this.link = link;
    this.codeGen = new CodeGen(group, this);
    RegisterAllocator argsAlloc = new RegisterAllocator(codeGen.cb, true);
    RecordLayout.VarAllocator resultsAlloc =
        RecordLayout.VarAllocator.newWithAlignedDoubles().setUpgradeSubInts();
    ImmutableList<Template> args = argBuilder.apply(argsAlloc);
    ImmutableList<Template> results = resultBuilder.apply(resultsAlloc);
    this.numJavaArgs = codeGen.cb.numArgs();
    this.resultObjSize = resultsAlloc.ptrSize();
    this.resultByteSize = resultsAlloc.byteSize();
    if (previous != null && results.equals(previous.results)) {
      if (args.equals(previous.args)) {
        // The signature hasn't changed, so we can keep the old callSite and existing callers will
        // automatically pick up the new code.
        this.mhCaller = previous.mhCaller;
        this.op = previous.op;
        this.args = previous.args;
        this.results = previous.results;
        return;
      }
      // If the args templates have changed but the results templates haven't, we will need a
      // wrapper in order for old callSites to use the new code but it can be simpler if we keep the
      // old result object (see the == check for the fast path in TState.checkExlinedResult).
      results = previous.results;
    }
    this.mhCaller = new MutableCallSite(codeGen.cb.methodType(void.class));
    // All pointer args except the TState are passed RC.In
    Bits rcInArgs =
        Bits.fromPredicate(numJavaArgs - 1, i -> i != 0 && codeGen.cb.register(i).isPtr());
    this.op = RcOp.forMethodHandle(name, mhCaller.dynamicInvoker()).argIsRcIn(rcInArgs).build();
    this.args = args;
    this.results = results;
  }

  /** May be called from our generated code to increment {@link #callCount}. */
  void incrementCallCount() {
    var unused = (int) CALL_COUNT_VAR.getAndAdd(this, 1);
  }

  /** Returns the current value of {@link #callCount}. */
  int callCount() {
    return (int) CALL_COUNT_VAR.getOpaque(this);
  }

  /** Returns the current value of {@link #entryCount}. */
  int entryCount() {
    return (int) ENTRY_COUNT_VAR.getOpaque(this);
  }

  /** Increments {@link #stabilityCounter}; see the comment there for when this is done. */
  void incrementStabilityCounter() {
    var unused = (int) STABILITY_COUNTER_VAR.getAndAdd(this, 1);
  }

  /** Sets {@link #stabilityCounter} to zero; see the comment there for when this is done. */
  void resetStabilityCounter() {
    STABILITY_COUNTER_VAR.setOpaque(this, 0);
  }

  /** Returns the current value of {@link #stabilityCounter}. */
  int stabilityCounter() {
    return (int) STABILITY_COUNTER_VAR.getOpaque(this);
  }

  /** Called once (from the CodeGenGroup) to generate code for this target. */
  void generateCode() {
    assert state == State.NEW;
    CodeGen codeGen = this.codeGen;
    this.codeGen = null;
    CodeGenManager.Monitor monitor = codeGen.group.manager.monitor();
    if (monitor.verbose()) {
      codeGen.cb.verbose = true;
      // CodeBuilder is going to print some log spam, let's add a little context
      System.out.printf("Generating code for %s (%s => %s)\n", name, args, results);
    }
    Supplier<String> counters = null;
    if (monitor.countCalls()) {
      INCREMENT_CALL_COUNT.block(CodeValue.of(this)).addTo(codeGen.cb);
      counters = this::counters;
    }
    VmMethod method = link.mm.method();
    codeGen.emit(link.mm, method.impl, this);
    MethodHandle mh = loadGeneratedCode(codeGen, monitor, method.toString());
    monitor.loaded(name, args, counters, codeGen.cb.debugInfo);
    mhCaller.setTarget(mh);
    state = State.READY;
  }

  /** Load the code from {@code codeGen}. */
  private MethodHandle loadGeneratedCode(
      CodeGen codeGen, CodeGenManager.Monitor monitor, String fileName) {
    MethodHandle mh = null;
    try {
      // TODO: use a more restrictive Lookup?
      mh = codeGen.cb.load(name, fileName, void.class, Handle.lookup);
    } finally {
      // That shouldn't error, but if it does (usually due to some verifier problem) the debugInfo
      // might be useful.
      if (mh == null && monitor != null) {
        monitor.loaded(name, args, null, codeGen.cb.debugInfo);
      }
    }
    return mh;
  }

  /**
   * Returns an array suitable for passing to {@link #call}, or null if the given arguments cannot
   * be represented by this target's args templates. The returned array is not included in the
   * current ResourceTracker's memory use but its references are counted.
   */
  Object[] prepareArgs(TState tstate, Object[] argValues) {
    ArgSaver saver = new ArgSaver(numJavaArgs);
    for (int i = 0; i < args.size(); i++) {
      if (!saver.saveArg(tstate, args.get(i), (Value) argValues[i])) {
        REJECT_COUNT_VAR.getAndAdd(this, 1);
        return null;
      }
    }
    // If we made it this far we're good to go.
    return saver.args;
  }

  /** A helper class to simplify {@link #prepareArgs}. */
  private static class ArgSaver implements VarSink {
    final Object[] args;

    ArgSaver(int numArgs) {
      // Don't use tstate.allocObjectArray() here, since this array gets passed into
      // invokeWithArguments() and never seen again.
      this.args = new Object[numArgs];
    }

    /**
     * Stores one argument value into the args array, using the given template. Returns true if
     * successful, false if the value cannot be represented by the given template.
     */
    boolean saveArg(TState tstate, Template t, Value v) {
      if (t.setValue(tstate, v, this)) {
        return true;
      }
      // This arg value couldn't be represented by the template.  Clean up any array elements we
      // wrote before failing (since their reference counts were incremented) and bail out.
      tstate.clearElements(args, 0, args.length);
      return false;
    }

    private void saveArg(int index, Object value) {
      // Each prepared arg should be set at most once, and the first slot is reserved for the
      // TState.
      assert index != 0 && args[index] == null;
      args[index] = value;
    }

    @Override
    public void setB(int index, int value) {
      saveArg(index, value);
    }

    @Override
    public void setI(int index, int value) {
      saveArg(index, value);
    }

    @Override
    public void setD(int index, double value) {
      saveArg(index, value);
    }

    @Override
    public void setValue(int index, @RC.In Value value) {
      saveArg(index, value);
    }
  }

  /**
   * Executes this target's generated code with the given arguments array (which should have been
   * returned by a call to {@link #prepareArgs}); either returns a result in {@code tstate} or
   * errors or blocks.
   */
  void call(TState tstate, Object[] preparedArgs) {
    assert preparedArgs.length == numJavaArgs && preparedArgs[0] == null;
    preparedArgs[0] = tstate;
    // If the arg templates include a union, some args may not have been set.
    // null is what we want for unused pointer-valued args, but the numeric ones must be set to
    // zero.
    for (int i = 1; i < preparedArgs.length; i++) {
      if (preparedArgs[i] == null && mhCaller.type().parameterType(i).isPrimitive()) {
        preparedArgs[i] = 0;
      }
    }
    ENTRY_COUNT_VAR.getAndAdd(this, 1);
    // If we're called (indirectly) from TState.checkExlinedResult() tstate.unwoundFrom may still
    // be in use to record a previously-escaped-from target.  Save it now and restore it when we're
    // done.
    CodeGenTarget savedUnwoundFrom = tstate.unwoundFrom;
    tstate.unwoundFrom = null;
    CodeGenDebugging debug = tstate.codeGenDebugging;
    if (debug != null) {
      debug.append(this, "s");
    }
    try {
      mhCaller.getTarget().invokeWithArguments(preparedArgs);
    } catch (Throwable e) {
      throw new AssertionError(e);
    }
    if (debug != null && !tstate.unwindStarted()) {
      debug.append(this, "r");
    }
    // We don't care about the representation of results, but this also handles escapes.
    var unused = tstate.checkExlinedResult(results);
    tstate.unwoundFrom = savedUnwoundFrom;
  }

  /**
   * Returns a formatted summary of the entries, rejects, and (if enabled) calls to this generated
   * code.
   *
   * <p>(Even if {@link CodeGenGroup.Monitor#countCalls} is false we still maintain the other
   * counters just because that's the easiest thing to do and we don't expect {@link #call} to be a
   * hotspot.)
   */
  String counters() {
    int entries = entryCount();
    int rejects = (int) REJECT_COUNT_VAR.getOpaque(this);
    int calls = callCount();
    return String.format("%s:(entry=%d reject=%d total=%d)", name, entries, rejects, calls);
  }

  /**
   * Sets this target's state to {@link State#FALLBACK} and redirects its MethodHandle to the {@link
   * #fallback(Object...)} method. Usually used when a CodeGenTarget has been superseded by a newer
   * version, but could also be used if we wanted to postpone the initial code generation for a
   * link.
   */
  void setToFallback() {
    synchronized (this) {
      assert state == State.READY || state == State.NEW;
      mhCaller.setTarget(FALLBACK.bindTo(this).asType(mhCaller.type()));
      state = State.FALLBACK;
    }
  }

  /**
   * If the code that was generated for this target is now obsolete, {@link #mhCaller} will be
   * redirected to this generic handler, which finds the most recent version of the code for {@link
   * #link} and redirects the MutableCallSite to it.
   */
  void fallback(Object... args) {
    MethodHandle handler;
    synchronized (this) {
      if (state == State.FALLBACK) {
        CodeGenTarget latest = link.ready();
        if (latest == null || latest == this) {
          // No code has been generated for this link yet
          handler = null;
        } else {
          handler = wrap(latest);
          mhCaller.setTarget(handler);
          state = State.FORWARDING;
        }
      } else {
        // Someone else already did the work
        assert state == State.FORWARDING;
        handler = mhCaller.getTarget();
      }
    }
    if (handler != null) {
      // A forwarding wrapper has now been installed, so just use it.
      try {
        handler.invokeWithArguments(args);
      } catch (RuntimeException | Error e) {
        // Shouldn't happen, but we can let our caller handle it
        throw e;
      } catch (Throwable e) {
        // A checked exception should be impossible
        throw new AssertionError(e);
      }
    } else {
      // We don't do use this yet, but I think it would be safe to just bounce into the interpreter.
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Construct a replacement MethodHandle for this target that just wraps an adapter around a more
   * recent version of the code.
   */
  private MethodHandle wrap(CodeGenTarget latest) {
    // If our successor reused the callSite (because the signature was unchanged) we shouldn't
    // have been set to fallback (and if we did we would create a MethodHandle that called itself).
    assert latest.mhCaller != this.mhCaller;

    CodeGen codeGen = new CodeGen(null, null);
    codeGen.emit(() -> emitWrapper(codeGen, latest));
    return loadGeneratedCode(codeGen, null, "wrap:" + latest.name);
  }

  /** Emit a method that has the same signature as this target but just calls {@code latest}. */
  private void emitWrapper(CodeGen codeGen, CodeGenTarget latest) {
    // The CodeGen constructor already allocated the first arg (TState); we need to add the rest
    assert codeGen.cb.numArgs() == 1 && codeGen.cb.register(0).type() == TState.class;
    MethodType thisType = mhCaller.type();
    for (int i = 1; i < numJavaArgs; i++) {
      Register r = codeGen.cb.newRegister(thisType.parameterType(i));
      assert r.index == i;
    }

    Register[] argsForLatest = new Register[latest.numJavaArgs];
    if (this.args.equals(latest.args)) {
      // args are unchanged, just use them directly
      Arrays.setAll(argsForLatest, codeGen.cb::register);
    } else {
      argsForLatest[0] = codeGen.tstateRegister();
      // Allocate new registers to hold the arguments for the wrapped version
      MethodType latestType = latest.mhCaller.type();
      for (int i = 1; i < latest.numJavaArgs; i++) {
        argsForLatest[i] = codeGen.cb.newRegister(latestType.parameterType(i));
      }
      // A CopyEmitter that interprets destination vars as the new version's arguments
      CopyEmitter emitter =
          new CopyEmitter() {
            @Override
            void setDstVar(CodeGen codeGen, Template t, CodeValue v) {
              int index =
                  (t instanceof Template.NumVar nv) ? nv.index : ((Template.RefVar) t).index;
              new SetBlock(argsForLatest[index], v).addTo(codeGen.cb);
            }

            @Override
            boolean checkNarrow() {
              // The destination template should never be narrower than the source.
              throw new AssertionError();
            }
          };
      for (int i = 0; i < args.size(); i++) {
        // Create a plan for copying the old arg registers to the new ones, and emit it
        Template src = this.args.get(i);
        Template dst = latest.args.get(i);
        CopyPlan plan = CopyPlan.create(src, dst);
        plan = CopyOptimizer.toRegisters(plan, 1, latest.numJavaArgs, dst);
        // Failing should be impossible (args only expand), so don't provide an onFail handler
        emitter.emit(codeGen, plan, null);
      }
    }
    // Call the wrapped version with the converted arguments
    latest.op.block(argsForLatest).addTo(codeGen.cb);

    // Eventually we'll return, but we may need to do some fixups first
    FutureBlock exit = codeGen.addAtEnd(() -> new ReturnBlock(null).addTo(codeGen.cb));

    if (this.results == latest.results) {
      // No result fixups needed
      codeGen.cb.branchTo(exit);
      return;
    }

    // If the wrapped code started unwinding, just return and let our caller handle it.
    CodeValue unwindStarted = TState.UNWIND_STARTED_OP.result(codeGen.tstateRegister());
    // non-zero => unwindStarted() returned true
    new TestBlock.IsEq(CodeBuilder.OpCodeType.INT, unwindStarted, CodeValue.ZERO)
        .setBranch(false, exit)
        .addTo(codeGen.cb);

    // Transforming results is a little trickier that transforming args; we have to load them into
    // registers and then store them back into the tstate.
    ResultsLoader loader = new ResultsLoader(codeGen);
    latest.results.forEach(loader::loadAllVars);
    CopyPlan[] resultPlans = new CopyPlan[results.size()];
    // Create a plan for copying each of the new results to the corresponding old template, but
    // don't emit them yet.
    for (int i = 0; i < resultPlans.length; i++) {
      Template src = latest.results.get(i);
      Template dst = this.results.get(i);
      CopyPlan plan = CopyPlan.create(src, dst);
      plan = CopyOptimizer.toFnResult(plan, resultObjSize, resultByteSize);
      resultPlans[i] = plan;
    }
    // It is possible that one of the results can't be stored in its old template; we need to
    // handle that, and we want to detect it *before* we make any changes to the result state
    // (so that we can just return and let our caller sort it out -- if they are generated code
    // they'll notice that the result templates don't match and escape).
    // So we'll first try emitting the plan with an emitter that doesn't do any stores, and see
    // if we can do it without failing.
    CopyEmitter testEmitter =
        new CopyEmitter() {
          @Override
          CodeValue getSrcVar(CodeGen codeGen, Template t) {
            return loader.loaded.get(t);
          }

          @Override
          void setDstVar(CodeGen codeGen, Template t, CodeValue v) {
            // Don't do anything
          }
        };
    for (CopyPlan plan : resultPlans) {
      // If we fail part way through we may have already loaded some of the results into registers.
      // For NumVars that's harmless, but for RefVars TState.fnResult() will have addRef()'d them.
      // RcCodeBuilder should notice that they were loaded by an @RC.Out Op and are now being
      // abandoned, and insert dropRef()s as needed.
      testEmitter.emit(codeGen, plan, exit);
    }
    // If we made it this far we are able to store the results in the old templates, so emit the
    // code to do that.
    loader.clearResults();
    CopyEmitter saveEmitter =
        new CodeGen.ToFnResults(codeGen, this) {
          @Override
          CodeValue getSrcVar(CodeGen codeGen, Template t) {
            return loader.loaded.get(t);
          }

          @Override
          boolean checkNarrow() {
            // We've already checked any narrowing in the first pass, so there's no need to do it
            // again.
            return false;
          }
        };
    // Even though the testEmitter call above should have caught any possible failures, this plan
    // may repeat some of those tests.  They can't fail, but we still need to tell the JVM what
    // to do if they did; `throw new AssertionError()` seems appropriate.
    FutureBlock unreachable = codeGen.addAtEnd(codeGen::emitAssertionFailed);
    for (CopyPlan plan : resultPlans) {
      saveEmitter.emit(codeGen, plan, unreachable);
    }
    // We're done
    codeGen.cb.branchTo(exit);
  }

  @Override
  public String toString() {
    return "target:" + name;
  }

  /**
   * A helper class that loads each of the vars in a given template from the TState results into a
   * new register. If the template has a union we load the vars from all of the choices, which may
   * cause us to load random bytes for an unused NumVar or null for an unused RefVar, but that will
   * end up being harmless.
   *
   * <p>(We could be smarter and make some of the loads conditional on the other loads, but that
   * would be more complex, it's unclear whether it would actually be faster, and if there was a
   * performance difference I wouldn't expect it to have a measurable impact overall.)
   *
   * <p>To use it:
   *
   * <ul>
   *   <li>Create a new ResultsLoader.
   *   <li>Call {@link #loadAllVars} with the results template(s); this will emit code to load all
   *       the values referenced in the template from the TState into new registers.
   *   <li>Use {@link #loaded} to determine which register each NumVar and RefVar was loaded into.
   *   <li>Call {@link #clearResults} to clear the TState's result state before starting to write a
   *       new result.
   * </ul>
   */
  private static class ResultsLoader implements Template.VarVisitor {
    final CodeGen codeGen;
    final Map<Template, Register> loaded = new HashMap<>();
    private Register fnResultBytes;
    private boolean loadedRef;

    ResultsLoader(CodeGen codeGen) {
      this.codeGen = codeGen;
    }

    void loadAllVars(Template t) {
      Template.visitVars(t, this);
    }

    @Override
    public void visitNumVar(Template.NumVar nv) {
      loaded.computeIfAbsent(nv, k -> loadNumVar(nv));
    }

    @Override
    public void visitRefVar(Template.RefVar rv) {
      loaded.computeIfAbsent(rv, k -> loadRefVar(rv));
    }

    private Register loadNumVar(Template.NumVar nv) {
      if (fnResultBytes == null) {
        fnResultBytes = codeGen.fnResultBytes(0);
      }
      Register temp =
          codeGen.cb.newRegister(nv.encoding == NumEncoding.FLOAT64 ? double.class : int.class);
      Op accessOp =
          switch (nv.encoding) {
            case UINT8 -> Op.UINT8_ARRAY_ELEMENT;
            case INT32 -> CodeGen.INT_FROM_BYTES_OP;
            case FLOAT64 -> CodeGen.DOUBLE_FROM_BYTES_OP;
          };
      CodeValue offset = CodeValue.of(nv.index);
      new SetBlock(temp, accessOp.result(fnResultBytes, offset)).addTo(codeGen.cb);
      return temp;
    }

    private Register loadRefVar(Template.RefVar rv) {
      Register temp = codeGen.cb.newRegister(Value.class);
      new SetBlock(
              temp, TState.FN_RESULT_OP.result(codeGen.tstateRegister(), CodeValue.of(rv.index)))
          .addTo(codeGen.cb);
      loadedRef = true;
      return temp;
    }

    void clearResults() {
      Op clearOp = loadedRef ? TState.CLEAR_RESULTS_OP : TState.CLEAR_RESULT_TEMPLATES_OP;
      clearOp.block(codeGen.tstateRegister()).addTo(codeGen.cb);
    }
  }
}
