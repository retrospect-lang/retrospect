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
import com.google.common.collect.ImmutableSet;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.retrolang.Vm;
import org.retrolang.code.FutureBlock;
import org.retrolang.impl.BaseType.SimpleStackEntryType;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.util.Bits;

/** Implements Vm.Function. */
public abstract class VmFunction implements Vm.Function {

  /**
   * We look up functions by (name, numArgs) pairs; the easiest way to turn that pair into a single
   * Java object with appropriate equals() and hashCode() is to make a String out of them.
   */
  static String key(String name, int numArgs) {
    return name + ":" + numArgs;
  }

  final String name;
  final int numArgs;
  final Bits inout;
  final int numResults;
  final Lambdas.AsLambdaExpr asLambdaExpr;

  VmFunction(String name, int numArgs, Bits inout, int numResults) {
    this.name = name;
    this.numArgs = numArgs;
    this.inout = inout;
    this.numResults = numResults;
    this.asLambdaExpr =
        (numResults == 1 && inout.isEmpty()) ? new Lambdas.AsLambdaExpr(this) : null;
  }

  /**
   * Returns a new compound baseType that is a subtype of {@link Core#LAMBDA}. The compound's size
   * is one less than {@link #numArgs}, and its {@code at()} method will call this function, using
   * the value passed to {@code at()} as the {@code i}th argument and taking the rest of the
   * arguments (in order) from the compound's elements.
   *
   * <p>Only valid on functions with at least two arguments (none inout) and exactly one result.
   */
  public BaseType partialApplication(int i) {
    return asLambdaExpr.partialApplication(i);
  }

  @Override
  public int numArgs() {
    return numArgs;
  }

  @Override
  public boolean argIsInout(int i) {
    return inout.test(i);
  }

  @Override
  public int numResults() {
    return numResults;
  }

  @Override
  public Singleton asLambdaExpr() {
    return asLambdaExpr.asValue();
  }

  @Override
  public String toString() {
    return key(name, numArgs);
  }

  /** Thrown by {@link VmFunction#findMethod} if more than one method matches. */
  static class MultipleMethodsException extends Exception {
    final VmMethod method1;
    final VmMethod method2;

    MultipleMethodsException(VmMethod method1, VmMethod method2) {
      this.method1 = method1;
      this.method2 = method2;
    }
  }

  /**
   * Returns the method that should be used to execute this function with the given arguments, or
   * null if no method matches. Throws a {@link MultipleMethodsException} if there are multiple
   * matching methods.
   */
  abstract VmMethod findMethod(Object... args) throws MultipleMethodsException;

  /**
   * Executes this function with the given arguments.
   *
   * <p>The caller should assume that any call to {@code doCall()} has indirectly called {@link
   * TState#syncWithCoordinator()}; see its documentation for the implications of that.
   */
  void doCall(
      TState tstate,
      ResultsInfo results,
      MethodMemo callerMemo,
      CallSite callSite,
      @RC.In Object[] args) {
    // Verify that we properly cleaned up after the previous call.
    assert !tstate.hasDropOnThrow();
    // If someone's passing garbage, try to detect it sooner rather than later.
    assert Arrays.stream(args).allMatch(RefCounted::isValidForStore);
    VmMethod method;
    try {
      method = findMethod(args);
    } catch (MultipleMethodsException e) {
      Err.MULTIPLE_METHODS.pushUnwind(
          tstate,
          tstate.asArrayValue(args, numArgs),
          new StringValue(tstate, e.method1.toString()),
          new StringValue(tstate, e.method2.toString()));
      return;
    }
    if (method == null) {
      Err.NO_METHOD.pushUnwind(
          tstate,
          new StringValue(tstate, String.format("%s:%d", name, numArgs)),
          tstate.asArrayValue(args, numArgs));
      return;
    }
    MethodMemo calledMemo = callerMemo.memoForCall(tstate, callSite, method, args);
    CodeGenParent prevCGParent;
    CodeGenLink savedCGLink = null;
    CodeGenTarget target = null;
    CodeGenTarget prevUnwoundFrom = null;
    if (calledMemo.extra() instanceof CodeGenLink cgLink) {
      // Before we switch to an exlined MethodMemo, make sure that any change to the current
      // MethodMemo is appropriately recorded.
      if (tstate.methodMemoUpdated) {
        tstate.resetStabilityCounter(callerMemo);
      }
      // Check to see if we have generated code for this method, and if so whether the
      // generated code accepts these args.  If it does, call the generated code instead of
      // executing the method directly.
      target = cgLink.checkReady(tstate);
      if (target != null) {
        Object[] preparedArgs = target.prepareArgs(tstate, args);
        if (preparedArgs != null) {
          tstate.dropReference(args);
          target.call(tstate, preparedArgs);
          return;
        }
      }
      prevCGParent = tstate.cgParent;
      tstate.cgParent = cgLink.selfLink();
      // Changes to an exlined method memo shouldn't affect whether the caller's execution is deemed
      // successful.
      prevUnwoundFrom = tstate.unwoundFrom;
      tstate.unwoundFrom = null;
      cgLink.incrementStabilityCounter(target, tstate.codeGenDebugging);
      savedCGLink = cgLink;
    } else {
      prevCGParent = tstate.cgParent;
    }
    results = callSite.adjustResultsInfo(results);
    method.impl.execute(tstate, results, calledMemo, args);
    if (savedCGLink != null) {
      if (tstate.methodMemoUpdated) {
        tstate.resetStabilityCounter(calledMemo);
      } else if (!tstate.unwindStarted()) {
        savedCGLink.incrementStabilityCounter(target, tstate.codeGenDebugging);
      }
      tstate.unwoundFrom = prevUnwoundFrom;
    }
    tstate.cgParent = prevCGParent;
  }

  /**
   * Emits code to determine the appropriate method for the given arguments and call {@link
   * CodeGen#emitMethodCall}.
   */
  abstract void emitCall(
      CodeGen codeGen, MethodMemo callerMemo, CallSite callSite, @RC.In Object[] args);

  /**
   * An implementation of VmFunction with a single method. The method is applicable to all argument
   * types, and no additional methods can be added for this function.
   */
  static class Simple extends VmFunction implements MethodImpl {

    interface SimpleMethodImpl {
      // Note that args is *not* @RC.In
      void executeSimpleMethod(TState tstate, Object[] args) throws BuiltinException;
    }

    final VmMethod method;
    final SimpleMethodImpl impl;
    // If executeSimpleMethod() throws a BuiltinException we need to push a stack entry
    final SimpleStackEntryType stackEntry;

    Simple(String name, int numResults, SimpleMethodImpl impl, String... argNames) {
      super(name, argNames.length, Bits.EMPTY, numResults);
      this.impl = impl;
      this.method =
          new VmMethod(
              this,
              /* predicate= */ null,
              /* isDefault= */ false,
              this,
              /* baseWeight= */ 1,
              MethodMemo.Factory.TRIVIAL);
      this.stackEntry = new SimpleStackEntryType(name, argNames);
    }

    @Override
    VmMethod findMethod(Object... args) {
      return method;
    }

    @Override
    public void execute(
        TState tstate, ResultsInfo results, MethodMemo mMemo, @RC.In Object[] args) {
      try {
        impl.executeSimpleMethod(tstate, args);
      } catch (BuiltinException e) {
        e.push(tstate, stackEntry, args);
        return;
      }
      tstate.dropReference(args);
    }

    @Override
    public void emit(CodeGen codeGen, ResultsInfo results, MethodMemo mMemo, Object[] args) {
      try {
        impl.executeSimpleMethod(codeGen.tstate(), args);
      } catch (BuiltinException e) {
        codeGen.escape();
      }
    }

    @Override
    void emitCall(CodeGen codeGen, MethodMemo callerMemo, CallSite callSite, Object[] args) {
      codeGen.emitMethodCall(this, method.fixedMemo, args);
    }
  }

  /**
   * A distinct ArgKey is created for each argument position of each {@link General}, to serve as a
   * key when methods for that function are attached to types.
   */
  static class ArgKey {}

  private static final VarHandle ARG_KEY_ARRAY_ELEMENT =
      MethodHandles.arrayElementVarHandle(ArgKey[].class);

  /**
   * A full implementation of VmFunction. Multiple methods may be defined, each with its own
   * MethodPredicate and "default" flag.
   */
  static class General extends VmFunction {
    /** The module in which this function was defined. */
    final VmModule module;

    /** Methods for this function may be defined by other modules only if {@code open} is true. */
    final boolean open;

    /**
     * While this function's module is being built, an ArrayList with all the methods defined for
     * it. When the module is completed ({@link ModuleBuilder#build}), some of the methods may be
     * moved to an arg type, and any remaining methods are left here in an ImmutableList. If there
     * are no remaining methods this is set to null.
     *
     * <p>This field is immutable after the module is built.
     */
    private List<VmMethod> methods = new ArrayList<>();

    /**
     * If this function is open or finalizeMethods() decided to attach some of this module's methods
     * to types, an ArgKey for each argument position. The ArgKeys are created lazily, so each
     * position will only be non-null if at least one method is attached to an argument type at that
     * position.
     */
    private ArgKey[] argKeys;

    General(VmModule module, String name, int numArgs, Bits inout, int numResults, boolean open) {
      super(name, numArgs, inout, numResults);
      this.module = module;
      this.open = open;
    }

    @Override
    @Nullable VmMethod findMethod(Object... args) throws MultipleMethodsException {
      // First look for a preferred method...
      VmMethod m = findMethod(args, false);
      // ... and if that fails, look for a default method.
      return (m != null) ? m : findMethod(args, true);
    }

    /**
     * Returns the method that should be used to execute this function with the given arguments, or
     * null if no method matches. Only considers methods that are (isDefault=true) or are not
     * (isDefault=false) marked as "default". Throws a {@link MultipleMethodsException} if there are
     * multiple matching methods.
     */
    private VmMethod findMethod(Object[] args, boolean isDefault) throws MultipleMethodsException {
      // First check for methods attached to the function.
      VmMethod result = findMethod(null, (ImmutableList<VmMethod>) methods, args, isDefault);
      // Reading argKeys without synchronization might seem suspect but it should be safe; the only
      // changes that are made to it are monotonic (replacing a null element with an ArgKey), and
      // it's OK if we don't see changes made by a thread that hasn't synchronized with us (since
      // we couldn't encounter any of its types anyway).
      if (argKeys != null) {
        // Check for methods attached to an argument type.
        for (int i = 0; i < argKeys.length; i++) {
          ArgKey argKey = argKeys[i];
          if (argKey == null) {
            continue;
          }
          VmType argType = ((Value) args[i]).baseType().vmType();
          result = findMethod(result, argType.getMethods(argKey), args, isDefault);
          for (VmType superType : argType.superTypes.asSet) {
            result = findMethod(result, superType.getMethods(argKey), args, isDefault);
          }
        }
      }
      return result;
    }

    /**
     * Checks each of the methods in the given list, looking for one that (a) has the default flag
     * as specified, and (b) is applicable to the given arguments.
     *
     * <p>If no matching method is found (or the list is null), return {@code prior}.
     *
     * <p>If one matching method is found and {@code prior} was null, return the matching method.
     *
     * <p>Otherwise (a matching method is found with a prior, or more than one matching method is
     * found) throw a {@link MultipleMethodsException}.
     */
    private static VmMethod findMethod(
        VmMethod prior, ImmutableList<VmMethod> methods, Object[] args, boolean isDefault)
        throws MultipleMethodsException {
      if (methods != null) {
        for (VmMethod m : methods) {
          if (m.isDefault == isDefault && m.predicate.test(args).asBoolean()) {
            if (prior == null) {
              prior = m;
            } else if (prior != m) {
              throw new MultipleMethodsException(prior, m);
            }
          }
        }
      }
      return prior;
    }

    @Override
    void emitCall(CodeGen codeGen, MethodMemo callerMemo, CallSite callSite, Object[] args) {
      MethodCollector matches = new MethodCollector();
      // First get the methods that we have memos for, i.e. that have been chosen at least once by
      // this CallMemo
      synchronized (codeGen.tstate().scope().memoMerger) {
        CallMemo callMemo = callerMemo.memoForCall(callSite);
        if (callMemo != null) {
          callMemo.forEachChild(matches::addFromCallMemo);
        }
      }
      // Then find any other methods that could potentially match these args; we won't generate
      // code for them, but we have to verify that they don't match (and escape if they do).
      // Note that these will always be inserted after the methods with memos (while keeping all the
      // preferred methods before all the default methods).
      matches.addMatching(methods, args);
      if (argKeys != null) {
        matches.addFromArgKeys(argKeys, args);
      }
      // Any methods added from the CallMemo should also have been found in the second step, and had
      // their Condition filled in.
      assert matches.stream().allMatch(mm -> mm.condition != null);
      // Now emit instructions to identify and invoke the correct method for these args
      CodeGen.EscapeState savedEscape = codeGen.escapeState();
      for (int i = 0; i < matches.size(); i++) {
        if (!codeGen.cb.nextIsReachable()) {
          // All possible arg values have been handled, so there's nothing left to do
          return;
        }
        MatchingMethod mm = matches.get(i);
        if (mm.memo == null) {
          int next = matches.nextWithMemo(i);
          if (next < 0) {
            // None of the remaining methods have memos, so if we get here we should just escape
            break;
          }
          // There are preferred methods without memos, but at least one default method with a
          // memo.  We need to verify that none of the preferred methods match before considering
          // a default method.
          assert !mm.method.isDefault && matches.get(next).method.isDefault;
          matches.escapeIfAnyMatch(codeGen, false, i);
          // Jump to the start of the default methods (-1 because the loop will increment it)
          i = next - 1;
          continue;
        }
        FutureBlock tryNext = new FutureBlock();
        mm.condition.addTest(codeGen, tryNext);
        // This method matched, but we also need to ensure that no other method (with the same
        // preferred/default status) does
        matches.escapeIfAnyMatch(codeGen, mm.method.isDefault, i + 1);
        // Now we can actually emit the method body
        if (codeGen.cb.nextIsReachable()) {
          codeGen.emitMethodCall(mm.method.impl, mm.memo, args);
        }
        // That will have either branched to the callDone link or escaped, so we're ready to
        // consider the next method.
        codeGen.cb.setNext(tryNext);
        codeGen.restore(savedEscape);
      }
      // If none of the previously-executed methods matched, it might be because we need to try
      // a new method or it might just be an error.  The interpreter will sort it out.
      codeGen.escape();
    }

    /** Add a method to this function. Only called while building this function's module. */
    void addMethod(VmMethod method) {
      assert Thread.holdsLock(module.builder());
      methods.add(method);
    }

    /** Called from ModuleBuilder.build(). */
    void finalizeMethods() {
      assert Thread.holdsLock(module.builder());
      if (open || methods.size() > 2) {
        // Attach as many methods to their arg types as we can.
        argKeys = new ArgKey[numArgs];
        boolean attachedSome = methods.removeIf(method -> canAttachToTypes(method, module));
        if (!(open || attachedSome)) {
          // There's no reason to keep an all-null array of ArgKeys for a non-open method.
          assert Arrays.stream(argKeys).allMatch(k -> k == null);
          argKeys = null;
        }
      }
      if (methods.isEmpty()) {
        // Setting methods to an empty ImmutableList would work as well, but findMethods is checking
        // for null anyway.
        methods = null;
      } else {
        methods = ImmutableList.copyOf(methods);
      }
    }

    /** Returns the ArgKey for the specified argument, creating it if needed. */
    ArgKey argKey(int index) {
      ArgKey result = argKeys[index];
      if (result != null) {
        return result;
      } else {
        // We could use synchronize here if it seemed simpler; this method is only called during
        // module building, so performance isn't critical.
        result = new ArgKey();
        ArgKey prev =
            (ArgKey) ARG_KEY_ARRAY_ELEMENT.compareAndExchange(argKeys, index, null, result);
        // If we raced and someone else set this element first, use the ArgKey they chose.
        return (prev == null) ? result : prev;
      }
    }

    /**
     * If the given method can be attached to types in the given module, does so and returns true;
     * otherwise returns false.
     *
     * <p>Called from {@link #finalizeMethods} with {@code typesModule} == {@link #module}, and from
     * {@link ModuleBuilder#addMethod} with {@code typesModule} != {@link #module}.
     */
    boolean canAttachToTypes(VmMethod method, VmModule typesModule) {
      assert Thread.holdsLock(typesModule.builder());
      ImmutableSet<MethodPredicate.Simple> argRestriction =
          method.predicate.asArgRestriction(typesModule);
      if (argRestriction == null) {
        return false;
      }
      assert !argRestriction.isEmpty();
      for (MethodPredicate.Simple restriction : argRestriction) {
        assert restriction.positive;
        restriction.type.addMethod(argKey(restriction.argIndex), method);
      }
      return true;
    }
  }

  /** Information about a method that might match at a CallSite for which we are generating code. */
  static class MatchingMethod {
    final VmMethod method;
    final MethodMemo memo;

    /** The number of calls we've seen to this method at this site, as recorded in the CallMemo. */
    final int count;

    /** This method is only applicable if {@code condition} is true. */
    Condition condition;

    MatchingMethod(VmMethod method, MethodMemo memo, int count) {
      // condition will be set later
      this.method = method;
      this.memo = memo;
      this.count = count;
    }
  }

  /**
   * A list of MatchingMethods with non-FALSE conditions, sorted first by preferred before default,
   * and then by count.
   */
  static class MethodCollector extends ArrayList<MatchingMethod> {
    /**
     * The first {@code numPreferred} elements are preferred (i.e. not default) methods; the rest
     * are default methods. Within each group methods are sorted by count (decreasing).
     */
    int numPreferred;

    /** Creates and inserts a new MatchingMethod. We'll fill in the Condition later. */
    void addFromCallMemo(MethodMemo memo, int count) {
      VmMethod method = memo.method();
      MatchingMethod mm = new MatchingMethod(method, memo, count);
      // Entries in [start, pos) have the same isDefault status as this method
      int pos;
      int start;
      if (method.isDefault) {
        start = numPreferred;
        pos = size();
      } else {
        start = 0;
        pos = numPreferred++;
      }
      // Scan from the back of the list until we find the right place to insert it.
      while (pos > start && get(pos - 1).count < count) {
        --pos;
      }
      add(pos, mm);
    }

    /**
     * Ensure that we have a complete MatchingMethod (i.e. including {@link
     * MatchingMethod#condition}) for each entry in {@code methods} that could match the given args.
     */
    void addMatching(List<VmMethod> methods, Object[] args) {
      if (methods != null) {
        for (VmMethod method : methods) {
          Condition condition = method.predicate.test(args);
          if (condition != Condition.FALSE) {
            MatchingMethod mm = findOrInsert(method);
            // It's possible that we'll set the condition more than once (due to ArgKeys), but
            // we should construct the same Condition each time.
            mm.condition = condition;
          }
        }
      }
    }

    /**
     * If there is already a MatchingMethod for the given method, return it; otherwise create a new
     * one (with null MethodMemo and zero count).
     */
    private MatchingMethod findOrInsert(VmMethod method) {
      // We could just search the subrange before or after numPreferred based on method.isDefault,
      // but my guess is that wouldn't actually be faster.
      for (MatchingMethod mm : this) {
        if (mm.method == method) {
          return mm;
        }
      }
      MatchingMethod mm = new MatchingMethod(method, null, 0);
      // Since count is zero it's easy to insert this in the right place.
      if (method.isDefault) {
        add(mm);
      } else {
        add(numPreferred++, mm);
      }
      return mm;
    }

    /**
     * Ensure that we have complete MatchingMethods for all the methods reachable from these ArgKeys
     * for these args.
     */
    void addFromArgKeys(ArgKey[] argKeys, Object[] args) {
      for (int i = 0; i < argKeys.length; i++) {
        ArgKey argKey = argKeys[i];
        if (argKey == null) {
          continue;
        }
        // Check if there are any methods associated with this arg's type.
        Value arg = (Value) args[i];
        if (!(arg instanceof RValue)) {
          // The arg is constant, so we know its type.
          addFromArgType(argKey, arg.baseType(), args);
        } else {
          Template template = ((RValue) arg).template;
          if (!(template instanceof Template.Union union)) {
            // The arg is not a constant, but we still know its type.
            addFromArgType(argKey, template.baseType(), args);
          } else {
            // The arg is a union, so we don't know its type, but we can enumerate the possibilities
            // and check them all.
            int nChoices = union.numChoices();
            for (int c = 0; c < nChoices; c++) {
              addFromArgType(argKey, union.choice(c).baseType(), args);
            }
          }
        }
      }
    }

    /**
     * Given an ArgKey and the BaseType of the corresponding arg, find all associated methods and
     * ensure that we have a complete MatchingMethod for each of them.
     */
    private void addFromArgType(ArgKey argKey, BaseType baseType, Object[] args) {
      VmType argType = baseType.vmType();
      addMatching(argType.getMethods(argKey), args);
      for (VmType superType : argType.superTypes.asSet) {
        addMatching(superType.getMethods(argKey), args);
      }
    }

    /**
     * Given the index of MatchingMethod with no memo, returns the index of the next MatchingMethod
     * that does have a memo, or -1 if there is none.
     */
    int nextWithMemo(int i) {
      assert get(i).memo == null;
      // Because our list was constructed to have MatchingMethods with memos before those without,
      // the only way there can be a MatchingMethod with a memo following one without is if we
      // were given the index of a preferred MatchingMethod, and there are default MatchingMethods
      // with memos (in which case the first default MatchingMethod is the one we want).
      int result = -1;
      if (i < numPreferred && numPreferred < size() && get(numPreferred).memo != null) {
        result = numPreferred;
      }
      assert subList(i + 1, result < 0 ? size() : result).stream().allMatch(mm -> mm.memo == null);
      return result;
    }

    /**
     * Given the index of a MatchingMethod, emit instructions to escape if any of the methods with
     * index >= and the specified preferred/default status have true Conditions.
     */
    void escapeIfAnyMatch(CodeGen codeGen, boolean isDefault, int start) {
      int end = isDefault ? size() : numPreferred;
      for (int i = start; i < end; i++) {
        codeGen.escapeUnless(get(i).condition.not());
      }
    }
  }
}
