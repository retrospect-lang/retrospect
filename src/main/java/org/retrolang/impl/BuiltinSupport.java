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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.retrolang.code.Loop;
import org.retrolang.impl.BaseType.SimpleStackEntryType;
import org.retrolang.impl.BuiltinMethod.BuiltinStatic;
import org.retrolang.impl.BuiltinMethod.Caller;
import org.retrolang.impl.BuiltinMethod.Continuation;
import org.retrolang.impl.BuiltinMethod.LoopContinuation;
import org.retrolang.impl.BuiltinMethod.Saved;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.util.Bits;

/**
 * Statics-only class to enable scanning designated classes for definitions of Retrospect built-in
 * methods.
 *
 * <p>Built-in methods are defined by Java methods with a @Core.Method annotation. See builtins.md
 * for details.
 */
class BuiltinSupport {
  private BuiltinSupport() {}

  /**
   * Given a MethodHandle whose last numValues arguments are Values, returns a MethodHandle with
   * those arguments replaced by an Object[] argument; the original MethodHandle will be called with
   * the first numValues elements of the array (which must all be Values).
   *
   * <p>This is almost equivalent to
   *
   * <pre>
   *   mh.asSpreader(Object[].class, numValues);
   * </pre>
   *
   * ... but that only works if the array's length is exactly numValues, and ours might be longer.
   */
  static MethodHandle spreadObjArray(MethodHandle mh, int numValues) {
    int numArgs = mh.type().parameterCount();
    if (numValues == 0) {
      return MethodHandles.dropArguments(mh, numArgs, Object[].class);
    }
    // Replace each Value arg with an Object[] arg, extracting the Value from the appropriate
    // element of the Object[].
    int firstValue = numArgs - numValues;
    MethodHandle[] filters = new MethodHandle[numValues];
    Arrays.setAll(filters, i -> MethodHandles.insertArguments(GET_VALUE_FROM_OBJECT_ARRAY, 1, i));
    mh = MethodHandles.filterArguments(mh, firstValue, filters);
    if (numValues == 1) {
      return mh;
    }
    // Replicate the Object[] arg numArg times
    int[] reorder = new int[numArgs];
    Arrays.fill(reorder, firstValue);
    for (int i = 0; i < firstValue; i++) {
      reorder[i] = i;
    }
    MethodType mtype = mh.type().dropParameterTypes(firstValue + 1, numArgs);
    return MethodHandles.permuteArguments(mh, mtype, reorder);
  }

  /**
   * Wraps a Java method that implements a Retrospect builtin or is a continuation within a
   * builtin's implementation.
   */
  static class BuiltinEntry {
    /** The builtin that this entry implements or is part of. */
    final BuiltinImpl impl;

    /** The Java method, with its signature canonicalized to match {@link MethodImpl#execute}. */
    final MethodHandle mh;

    /**
     * A StackEntryType to use if execution fails or is blocked; saves the Java method's original
     * arguments.
     */
    final SimpleStackEntryType stackEntryType;

    BuiltinEntry(BuiltinImpl impl, MethodHandle mh, String where, String... argNames) {
      this.impl = impl;
      this.mh = mh;
      this.stackEntryType =
          new SimpleStackEntryType(where, argNames) {
            @Override
            void resume(TState tstate, @RC.In Value entry, ResultsInfo results, MethodMemo mMemo) {
              // If this stackEntry is resumed we just rerun the Java method.
              assert entry.baseType() == this;
              Object[] args = tstate.allocObjectArray(size());
              for (int i = 0; i < size(); i++) {
                args[i] = entry.element(i);
              }
              tstate.dropValue(entry);
              execute(tstate, results, mMemo, args);
              tstate.finishBuiltin(results, mMemo, impl);
            }
          };
    }

    /**
     * The number of Values that are passed to this Java method, including any @Saved arguments to a
     * continuation.
     */
    int numArgs() {
      return stackEntryType.size();
    }

    /** Executes the Java method with the given arguments. */
    void execute(TState tstate, ResultsInfo results, MethodMemo mMemo, @RC.In Object[] args) {
      try {
        mh.invokeExact(tstate, results, mMemo, args);
      } catch (BuiltinException e) {
        if (tstate.hasCodeGen()) {
          tstate.codeGen().escape();
          return;
        }
        tstate.dropForThrow();
        e.push(tstate, stackEntryType, args);
      } catch (RuntimeException e) {
        // Shouldn't happen, but if it does try to log something useful, then construct an
        // INTERNAL_ERROR stack entry and start unwinding.
        if (tstate.hasCodeGen()) {
          throw e;
        }
        e.printStackTrace();
        // The args may have been mutated or released, but they might be better than nothing
        System.err.printf(
            "calling "
                + stackEntryType.toString(
                    i -> {
                      try {
                        return String.valueOf(args[i]);
                      } catch (RuntimeException nested) {
                        return "(can't print)";
                      }
                    }));
        // We may have been part way through returning results
        tstate.clearResults();
        Err.INTERNAL_ERROR.pushUnwind(tstate, e.toString());
      } catch (Error e) {
        throw e;
      } catch (Throwable e) {
        throw new AssertionError(e);
      }
    }

    @Override
    public String toString() {
      return stackEntryType.toString();
    }
  }

  /**
   * A ContinuationMethod identifies a Java method that will be called with the result of the
   * function call (for {@link TState#startCall}) or immediately (for {@link TState#jump}).
   */
  static class ContinuationMethod {
    final BuiltinImpl impl;
    final String name;

    /**
     * An optional argument to the {@link BuiltinMethod.Continuation} or {@link
     * BuiltinMethod.LoopContinuation} annotation; constrains the order in which {@link #index}
     * values are assigned. The body of a ContinuationMethod may only reference (via {@link
     * TState#startCall} or {@link TState#jump}) continuations that have a greater order or for
     * which {@link #isLoop} is true.
     */
    final short order;

    /** True if this method had a {@link BuiltinMethod.LoopContinuation} annotation. */
    final boolean isLoop;

    /**
     * Each ContinuationMethod within a BuiltInMethod will have a distinct index; used as the {@link
     * CallSite#vIndex} for each Caller that uses this continuation.
     */
    private int index = -1;

    /** The Java code to be called, or null if this is TAIL_CALL. */
    final BuiltinEntry builtinEntry;

    /** The names of any Value parameters following a {@code @Saved} annotation. */
    final String[] savedNames;

    ContinuationMethod(
        BuiltinImpl impl,
        String name,
        int order,
        boolean isLoop,
        BuiltinEntry builtinEntry,
        String[] savedNames) {
      assert order == (short) order;
      this.impl = impl;
      this.name = name;
      this.order = (short) order;
      this.isLoop = isLoop;
      this.builtinEntry = builtinEntry;
      this.savedNames = savedNames;
    }

    int numArgs() {
      // TAIL_CALL's builtinEntry is null
      return (builtinEntry == null) ? 0 : builtinEntry.numArgs();
    }

    /**
     * Sets this ContinuationMethod's {@link #index}; must be called exactly once before the
     * ContinuationMethod is used.
     */
    void setIndex(int index) {
      assert this.index < 0;
      this.index = index;
    }

    int index() {
      assert index >= 0 || this == BuiltinMethod.TAIL_CALL;
      return index;
    }

    /**
     * Throws an AssertionError if a reference to this continuation from a continuation with the
     * specified order is invalid; otherwise returns true (throwing an AssertionError rather than
     * just returning false enables us to provide a more useful message).
     */
    boolean checkCallFrom(int previousOrder) {
      if (isLoop || previousOrder < order) {
        return true;
      }
      throw new AssertionError("Out-of-order call to " + builtinEntry);
    }

    /** Returns the ValueMemo to be used for harmonizing values passed to this continuation. */
    ValueMemo valueMemo(TState tstate, MethodMemo memo) {
      return memo.valueMemo(tstate, index, builtinEntry.numArgs());
    }
  }

  /** A MethodImpl for built-in methods. */
  static class BuiltinImpl implements MethodImpl {
    final String where;

    /** The BuiltinEntry for the begin method. */
    private BuiltinEntry builtinEntry;

    /** The number of CallSites in this method. */
    private int numCallers;

    /** This method's continuations, keyed by name. */
    private ImmutableMap<String, ContinuationMethod> cMethodsByName;

    /** This method's continuations, sorted by order. */
    private ImmutableList<ContinuationMethod> cMethodsInOrder;

    /**
     * If this method has a @LoopContinuation, its index in {@link #cMethodsInOrder}; otherwise -1.
     */
    private int loopIndex;

    private ToIntFunction<Object[]> loopBounder;

    private int numExtraValueMemos;

    BuiltinImpl(String where) {
      this.where = where;
    }

    ContinuationMethod continuation(String name) {
      ContinuationMethod result = cMethodsByName.get(name);
      Preconditions.checkArgument(result != null, "Unknown continuation \"%s\" (%s)", name, where);
      return result;
    }

    void setBuiltinEntry(BuiltinEntry builtinEntry) {
      assert this.builtinEntry == null;
      this.builtinEntry = builtinEntry;
    }

    void setContinuations(List<ContinuationMethod> cMethods) {
      assert this.cMethodsInOrder == null;
      this.cMethodsInOrder =
          ImmutableList.sortedCopyOf(Comparator.comparingInt(cm -> cm.order), cMethods);
      // Assign a distinct index to each ContinuationMethod, consistent with their ordering.
      int loopIndex = -1;
      for (int i = 0; i < cMethodsInOrder.size(); i++) {
        ContinuationMethod cMethod = cMethodsInOrder.get(i);
        cMethod.setIndex(i);
        if (cMethod.isLoop) {
          Preconditions.checkArgument(
              loopIndex < 0, "At most one @LoopContinuation allowed (%s)", where);
          loopIndex = i;
        }
      }
      this.loopIndex = loopIndex;
      this.cMethodsByName =
          cMethodsInOrder.stream().collect(ImmutableMap.toImmutableMap(cm -> cm.name, cm -> cm));
    }

    boolean hasLoop() {
      assert this.cMethodsInOrder != null;
      return loopIndex >= 0;
    }

    ContinuationMethod loopContinuation() {
      return cMethodsInOrder.get(loopIndex);
    }

    void setLoopBounder(ToIntFunction<Object[]> loopBounder) {
      assert this.loopBounder == null;
      this.loopBounder = loopBounder;
    }

    int loopBound(Object[] args) {
      return (loopBounder == null) ? -1 : loopBounder.applyAsInt(args);
    }

    /** Returns a distinct CallSite index each time it is called. */
    int nextCallSiteIndex() {
      return numCallers++;
    }

    /**
     * Returns a distinct ExtraValueMemo index each time it is called. Must be called after {@link
     * #setContinuations}.
     */
    int extraValueMemoIndex() {
      // ExtraValueMemos are allocated after the ValueMemos that we create for each continuation.
      int result = cMethodsInOrder.size() + numExtraValueMemos;
      ++numExtraValueMemos;
      return result;
    }

    /** Creates a VmMethod from this MethodImpl and adds it to signature.function. */
    void createMethod(ParsedSignature signature) {
      int numArgs = signature.function.numArgs;
      int numResults = signature.function.numResults;
      int numCallMemos = numCallers;
      int numValueMemos = cMethodsInOrder.size() + numExtraValueMemos;
      MethodMemo.Factory memoFactory;
      if (hasLoop()) {
        memoFactory = new MethodMemo.LoopFactory(numArgs, numResults, numCallMemos, numValueMemos);
      } else {
        memoFactory = MethodMemo.Factory.create(numArgs, numResults, numCallMemos, numValueMemos);
      }
      signature.function.addMethod(
          new VmMethod(
              signature.function,
              signature.predicate,
              signature.isDefault,
              this,
              /* baseWeight= */ 1,
              memoFactory));
    }

    @Override
    public void execute(TState tstate, ResultsInfo results, MethodMemo mMemo, Object[] args) {
      builtinEntry.execute(tstate, results, mMemo, args);
      tstate.finishBuiltin(results, mMemo, this);
    }

    @Override
    public void emit(CodeGen codeGen, ResultsInfo results, MethodMemo mMemo, Object[] args) {
      createStackEntry(codeGen, builtinEntry, args, false);
      if (cMethodsInOrder.isEmpty()) {
        builtinEntry.execute(codeGen.tstate(), results, mMemo, args);
      } else {
        emit(false, codeGen, results, mMemo, args);
      }
    }

    void emitFromLoopContinuation(
        CodeGen codeGen, ResultsInfo results, MethodMemo mMemo, Value[] args) {
      emit(true, codeGen, results, mMemo, args);
    }

    private void emit(
        boolean fromLoopContinuation,
        CodeGen codeGen,
        ResultsInfo results,
        MethodMemo mMemo,
        Object[] args) {
      EmitState emitState = new EmitState(this, mMemo);
      codeGen.setBuiltinEmitState(emitState);
      // The index of the next continuation to be emitted
      int next;
      if (!fromLoopContinuation) {
        builtinEntry.execute(codeGen.tstate(), results, mMemo, args);
        next = 0;
      } else {
        ContinuationMethod loop = cMethodsInOrder.get(loopIndex);
        Destination dest = emitState.getDestination(codeGen, loop);
        dest.forceFull(codeGen);
        // Value[] initialValues = Arrays.copyOf(args, loop.numArgs(), Value[].class);
        dest.addBranch(codeGen, (Value[]) args);
        next = loopIndex;
      }
      // If loopBound >= 0 this method has a loop and we are unrolling it
      int loopBound = -1;
      boolean unrolling = false;
      // If we are emitting a loop and haven't unrolled it, backward branches to the
      // LoopContinuation will be implemented as forward branches to an end-of-loop Destination;
      // this Runnable will emit the blocks needed at that Destination.
      Runnable emitLoopBack = null;
      int n = cMethodsInOrder.size();
      for (; ; next++) {
        if (next == n) {
          if (unrolling && emitState.destinations[loopIndex] != null) {
            // We got to the end but we're unrolling a loop so back up and do it again
            if (loopBound > 0) {
              next = loopIndex - 1;
              loopBound--;
              continue;
            }
            // This should be unreachable, and optimization will probably recognize that, but in
            // the meantime we need to emit something here.
            emitState.destinations[loopIndex].emit(codeGen);
            emitState.destinations[loopIndex] = null;
            codeGen.emitAssertionFailed();
          }
          // We should have emitted all the destinations we constructed, except possibly for the one
          // that emitLoopBack will take care of.
          boolean finalUnrolling = unrolling;
          assert IntStream.range(0, n)
              .allMatch(
                  j -> emitState.destinations[j] == null || (j == loopIndex && !finalUnrolling));
          break;
        }
        ContinuationMethod cMethod = cMethodsInOrder.get(next);
        Destination destination = emitState.destinations[next];
        Value[] destinationArgs = (destination == null) ? null : destination.emit(codeGen);
        emitState.destinations[next] = null;
        if (destinationArgs == null) {
          // Continuation was never referenced or is unreachable.
          // This doesn't handle the case of a built-in that starts in the
          // middle of a loop, but we don't currently have any of those.
          continue;
        }
        if (cMethod.isLoop && !unrolling) {
          // First time we encounter the LoopContinuation
          assert emitLoopBack == null && loopBound < 0;
          if (!fromLoopContinuation) {
            loopBound = loopBound(destinationArgs);
            if (loopBound == 0) {
              unrolling = true;
            } else if (loopBound > 0 && loopBound <= 6) {
              // TODO: we need a more sophisticated policy for when to unroll loops
              int limit = Math.min(24, 100 / loopBound);
              unrolling = !mMemo.weightExceeds(codeGen.tstate().scope(), limit);
            }
          }
          if (!unrolling) {
            // Start by ensuring that the loop state is in a fresh set of registers.
            if (!destination.isFull()) {
              destination = destination.duplicate(codeGen);
              destination.forceFull(codeGen);
              destination.addBranch(codeGen, destinationArgs);
              destinationArgs = destination.emit(codeGen);
            }
            // Those will be the loop registers.
            Bits.Builder registers = new Bits.Builder();
            int firstLoopRegister = destination.firstRegister();
            int lastLoopRegister = destination.lastRegister();
            registers.setToRange(firstLoopRegister, lastLoopRegister);
            // We also have to include the stackRest, since it will be updated by any tracing.
            registers.set(codeGen.currentCall().stackRest.index);
            Loop loop = codeGen.cb.startLoop(registers.build());
            // Subsequent branches to this LoopContinuation should go to the loopBack code,
            // which we will emit after all the continuations.
            Destination loopBack = destination.duplicate(codeGen);
            // A duplicate of a full Destination is also full, so this would be redundant:
            // loopBack.forceFull(codeGen);
            emitState.destinations[next] = loopBack;
            emitLoopBack =
                () -> {
                  if (loopBack.emit(codeGen) == null) {
                    loop.disable();
                  } else {
                    // Copy each loopBack register to the corresponding loop register.
                    int diff = loopBack.firstRegister() - firstLoopRegister;
                    for (int j = firstLoopRegister; j <= lastLoopRegister; j++) {
                      codeGen.emitSet(codeGen.cb.register(j), codeGen.cb.register(j + diff));
                    }
                    loop.complete();
                  }
                };
          }
        }
        createStackEntry(
            codeGen, cMethod.builtinEntry, destinationArgs, cMethod.isLoop && !unrolling);
        cMethod.builtinEntry.execute(codeGen.tstate(), results, mMemo, destinationArgs);
      }
      if (emitLoopBack != null) {
        emitLoopBack.run();
      }
    }

    /**
     * Creates a stack entry describing our state at the start of the current step. This will be
     * used to annotate any blocks we emit. If we need a new escape handler the stack entry can also
     * be used as an unwind point.
     */
    private static void createStackEntry(
        CodeGen codeGen, BuiltinEntry builtinEntry, Object[] args, boolean forceEscape) {
      BaseType.StackEntryType entryType = builtinEntry.stackEntryType;
      Value stackEntry;
      if (entryType.isSingleton()) {
        stackEntry = entryType.asValue();
      } else {
        stackEntry = codeGen.tstate().asCompoundValue(entryType, args);
      }
      codeGen.setCurrentBuiltinStep(stackEntry);
      if (codeGen.needNewEscape() || forceEscape) {
        codeGen.setNewEscape(stackEntry);
      }
    }

    @Override
    public String toString() {
      return where;
    }
  }

  /** An EmitState holds the state involved in a single call to {@link BuiltinImpl#emit}. */
  static class EmitState {
    final BuiltinImpl impl;

    /** A MethodMemo for {@link #impl}. */
    final MethodMemo memo;

    /** A (lazily-created) Destination for each of {@link #impl}'s continuations. */
    private final Destination[] destinations;

    EmitState(BuiltinImpl impl, MethodMemo memo) {
      this.impl = impl;
      this.memo = memo;
      this.destinations = new Destination[impl.cMethodsInOrder.size()];
    }

    /** Returns the Destination corresponding to the continuation with the given name. */
    Destination getDestination(CodeGen codeGen, String continuationName) {
      return getDestination(codeGen, impl.continuation(continuationName));
    }

    /** Returns the Destination corresponding to the given continuation. */
    Destination getDestination(CodeGen codeGen, ContinuationMethod cm) {
      int index = cm.index();
      Destination result = destinations[index];
      if (result == null) {
        result = Destination.fromValueMemo(cm.valueMemo(codeGen.tstate(), memo));
        destinations[index] = result;
      }
      return result;
    }
  }

  /**
   * Scans the specified class for definitions of Retrospect methods and adds them to the
   * appropriate functions.
   *
   * @param klass the class to search
   * @param types the types that may be referred to in method predicates
   * @param functions the functions for which methods may be defined
   */
  static void addMethodsFrom(
      Class<?> klass, Map<String, VmType> types, Map<String, VmFunction> functions) {
    String cName = klass.getSimpleName();
    for (Method m : klass.getDeclaredMethods()) {
      Core.Method annotation = m.getAnnotation(Core.Method.class);
      if (annotation == null) {
        continue;
      }
      String signature = annotation.value();
      String name = m.getName();
      String where = cName + "." + name;
      BuiltinImpl impl = new BuiltinImpl(where);
      // Parse the signature string in the annotation
      ParsedSignature parsed = new ParsedSignature(where, signature, types, functions);
      // Wrap the Java method to match the API defined in VmMethod
      Prepared prepared = new Prepared(where, m, functions, impl);
      // Verify that the two are consistent, and add the method definition to the function
      prepared.checkSignature(where, parsed.function.numArgs, parsed.function.numResults, false);
      impl.setBuiltinEntry(prepared.builtinEntry);
      // This method has no continuations
      impl.setContinuations(ImmutableList.of());
      impl.createMethod(parsed);
    }
    for (Class<?> nestedClass : klass.getDeclaredClasses()) {
      if (BuiltinMethod.class.isAssignableFrom(nestedClass)) {
        addMethodFromNestedClass(cName, nestedClass, types, functions);
      }
    }
  }

  private static final String[] EMPTY_STRINGS = new String[0];

  /** The result of parsing the string that appears in a @Core.Method annotation. */
  private static class ParsedSignature {
    final VmFunction.General function;
    final MethodPredicate predicate;
    final boolean isDefault;

    /**
     * Matches a method signature: a function name, followed by zero or more (comma-separated) args
     * in parentheses, optionally followed by "default". Each arg is "_" or one or more type names
     * separated by "|".
     */
    private static final Pattern SIGNATURE_PATTERN =
        Pattern.compile("(\\w+)\\(([\\w|, ]*)\\)( default)?");

    private static final Pattern ARG_SEPARATOR = Pattern.compile(", *");
    private static final Pattern TYPE_NAME_SEPARATOR = Pattern.compile(" *\\| *");

    /**
     * Parses the given signature.
     *
     * @param where a description of where the signature appeared, for use in error messages
     * @param signature the signature to parse
     * @param types used to resolve any type names that appear in the signature
     * @param functions used to resolve the function name that appears in the signature
     */
    ParsedSignature(
        String where,
        String signature,
        Map<String, VmType> types,
        Map<String, VmFunction> functions) {
      Matcher matcher = SIGNATURE_PATTERN.matcher(signature);
      Preconditions.checkArgument(matcher.matches(), "Bad signature \"%s\" (%s)", signature, where);
      String fnName = matcher.group(1);
      String argString = matcher.group(2);
      this.isDefault = matcher.group(3) != null;
      assert !isDefault || matcher.group(3).equals(" default");
      String[] args = argString.isEmpty() ? EMPTY_STRINGS : ARG_SEPARATOR.split(argString, -1);
      String fnKey = VmFunction.key(fnName, args.length);
      VmFunction function = functions.get(fnKey);
      Preconditions.checkArgument(function != null, "Unknown function \"%s\" (%s)", fnKey, where);
      Preconditions.checkArgument(
          function instanceof VmFunction.General,
          "Cannot add methods to \"%s\" (%s)",
          fnKey,
          where);
      this.function = (VmFunction.General) function;
      MethodPredicate predicate = MethodPredicate.TRUE;
      for (int i = 0; i < args.length; i++) {
        predicate = predicate.and(asMethodPredicate(where, args[i], i, types));
      }
      this.predicate = predicate;
    }

    /**
     * Parses the given argument type into a MethodPredicate.
     *
     * <p>{@code arg} may be {@code "_"} (trivially true), a type name (true if the argument has
     * that type), or multiple type names separated by {@code "|"} (true if the argument has any of
     * those types).
     */
    private static MethodPredicate asMethodPredicate(
        String where, String arg, int index, Map<String, VmType> types) {
      if (arg.equals("_")) {
        return MethodPredicate.TRUE;
      }
      String[] typeNames = TYPE_NAME_SEPARATOR.split(arg, -1);
      MethodPredicate result = null;
      for (String typeName : typeNames) {
        VmType type = types.get(typeName);
        Preconditions.checkArgument(type != null, "Unknown type (%s) for %s", typeName, where);
        MethodPredicate predicate = new MethodPredicate.Simple(index, type, true);
        result = (result == null) ? predicate : result.or(predicate);
      }
      return result;
    }
  }

  /**
   * Wraps a Java method so that it matches the API defined in VmMethod.
   *
   * <p>The Java method
   *
   * <ul>
   *   <li>must have a return type of {@code Value} or {@code void};
   *   <li>may optionally have a first argument of type {@code TState};
   *   <li>must have all remaining arguments of type {@code Value}, each optionally annotated
   *       {@code @RC.In} or {@code @RC.Singleton};
   *   <li>may optionally throw {@link Err.BuiltinException}.
   * </ul>
   *
   * <p>If an argument is annotated {@code @RC.In}, the method takes responsibility for decrementing
   * its root count unless it throws {@code BuiltinException}. If the argument is not annotated, or
   * the method throws {@code BuiltinException}, the method must not change the argument's root
   * count.
   */
  private static class Prepared {
    final Method m;

    /** True if the Java method returns a Value, false if it returns void. */
    final boolean returnsValue;

    /**
     * The index of the first {@code Value} argument, either 1 if the method has an initial {@code
     * TState} argument, or 0 if it does not.
     */
    final int valueStart;

    /** The number of {@code Value} arguments. */
    final int numValues;

    /**
     * The number of Value arguments following the {@code @Saved} annotation, or zero if there is no
     * {@code @Saved}.
     */
    final int numSaved;

    /** A BuiltinEntry for this method. */
    final BuiltinEntry builtinEntry;

    /**
     * Wrap the given method.
     *
     * @param where a description of where the signature appeared, for use in error messages and
     *     stack traces
     * @param m the method to be wrapped
     * @param functions used to resolve function names that appear in Caller arguments
     */
    Prepared(String where, Method m, Map<String, VmFunction> functions, BuiltinImpl impl) {
      Preconditions.checkArgument(
          Modifier.isStatic(m.getModifiers()), "Should be static (%s)", where);
      m.setAccessible(true);
      this.m = m;
      this.returnsValue = (m.getReturnType() == Value.class);
      Preconditions.checkArgument(
          returnsValue || m.getReturnType() == void.class,
          "Return type should be Value or void (%s)",
          where);
      Parameter[] params = m.getParameters();
      boolean hasTState = false;
      boolean hasResultsInfo = false;
      boolean hasMethodMemo = false;
      int valueStart = 0;
      int numCallers = 0;
      int numSaved = 0;
      // Arguments marked @RC.In will be removed from the args array before we release it, since the
      // wrapped method is responsible for reducing their root count.
      Bits rcInValues = Bits.EMPTY;
      // We don't currently do anything with @RC.Singleton values, so they're left in the args array
      // and TState.dropReference(Object[]) eventually clears them out.
      Bits rcSingletonValues = Bits.EMPTY;
      for (int i = 0; i < params.length; i++) {
        Class<?> type = params[i].getType();
        if (params[i].isAnnotationPresent(Saved.class)) {
          Preconditions.checkArgument(
              numSaved == 0 && type == Value.class, "Bad @Saved (%s)", where);
          numSaved = params.length - i;
        }
        if (type != Caller.class) {
          Preconditions.checkArgument(
              !params[i].isAnnotationPresent(BuiltinMethod.Fn.class), "Bad @Fn (%s)", where);
        }
        boolean isRcIn = params[i].isAnnotationPresent(RC.In.class);
        boolean isRcSingleton = params[i].isAnnotationPresent(RC.Singleton.class);
        if (isRcIn || isRcSingleton) {
          Preconditions.checkArgument(
              type == Value.class && !(isRcIn && isRcSingleton),
              "Bad RC parameter annotations (%s)",
              where);
          if (isRcIn) {
            rcInValues = rcInValues.set(i - valueStart);
          } else {
            rcSingletonValues = rcSingletonValues.set(i - valueStart);
          }
        }
        if (type == TState.class) {
          if (i == 0) {
            hasTState = true;
            valueStart = 1;
            continue;
          }
        } else if (type == ResultsInfo.class) {
          if (i == valueStart && !hasResultsInfo && !hasMethodMemo) {
            hasResultsInfo = true;
            valueStart++;
            continue;
          }
        } else if (type == MethodMemo.class) {
          if (i == valueStart && !hasMethodMemo) {
            hasMethodMemo = true;
            valueStart++;
            continue;
          }
        } else if (type == Value.class) {
          // Value arguments must come before any Caller arguments.
          if (numCallers == 0) {
            continue;
          }
        } else if (type == Caller.class) {
          if (numCallers == 0) {
            // The first Caller argument; everything after this must be a Caller.
            numCallers = params.length - i;
            if (numSaved != 0) {
              numSaved -= numCallers;
            }
          }
          continue;
        }
        throw new IllegalArgumentException(String.format("Bad method args (%s)", where));
      }
      this.valueStart = valueStart;
      this.numValues = params.length - valueStart - numCallers;
      this.numSaved = numSaved;

      Class<?>[] exceptionTypes = m.getExceptionTypes();
      boolean canThrow = exceptionTypes.length != 0;
      if (canThrow) {
        Preconditions.checkArgument(
            exceptionTypes.length == 1 && exceptionTypes[0].equals(BuiltinException.class),
            "Unexpected throws (%s)",
            where);
      }
      MethodHandle mh = Handle.forMethod(m);
      // Create an appropriate Caller for each Caller argument
      for (int i = params.length - numCallers; i < params.length; i++) {
        Parameter callerParam = params[i];
        BuiltinMethod.Fn fnAnnotation = callerParam.getAnnotation(BuiltinMethod.Fn.class);
        boolean isAnyFn = callerParam.isAnnotationPresent(BuiltinMethod.AnyFn.class);
        Preconditions.checkArgument(
            (fnAnnotation == null) == isAnyFn,
            "Caller must have exactly one of @Fn or @AnyFn (%s)",
            where);
        String fnKey;
        VmFunction fn;
        if (isAnyFn) {
          fnKey = null;
          fn = null;
        } else {
          fnKey = fnAnnotation.value();
          fn = functions.get(fnKey);
          Preconditions.checkArgument(
              fn != null, "Unknown function \"%s\" for Caller argument (%s)", fnKey, where);
        }
        Caller caller = new Caller(where, fnKey, fn, impl);
        mh = MethodHandles.insertArguments(mh, valueStart + numValues, caller);
      }
      // Break the Object[] into individual Value arguments
      mh = spreadObjArray(mh, numValues);
      // The MethodHandle will always be invoked with a TState, ResultsInfo, and MethodMemo as the
      // first 3 arguments, but we allow the built-in method to omit any or all of those if it
      // doesn't need them.  Use dropArguments() to add any arguments that are missing.
      //
      // (The terminology is confusing; dropArguments() actually adds an argument to the
      // MethodHandle, but that the value of that argument will be dropped when the MethodHandle
      // is invoked.  The lines below add between zero and three arguments to the MethodHandle
      // (in signature order), whose values will then be dropped (in the reverse of this order)
      // when the MethodHandle is invoked.)
      //
      // (They could actually be added in any order, but adding them left-to-right makes it easy to
      // determine the correct argument position.)
      if (!hasTState) {
        mh = MethodHandles.dropArguments(mh, 0, TState.class);
      }
      if (!hasResultsInfo) {
        mh = MethodHandles.dropArguments(mh, 1, ResultsInfo.class);
      }
      if (!hasMethodMemo) {
        mh = MethodHandles.dropArguments(mh, 2, MethodMemo.class);
      }
      // If the method returns its value, add a call to tstate.setResult()
      if (m.getReturnType() == Value.class) {
        mh = sequence(mh, CHECK_FOR_RESULT);
      }
      // Now we should have method handle of the right type.
      assert mh.type().equals(EXEC_TYPE);
      // After it returns, we still need to drop the args array and all the args *except* those
      // that were marked as @RC.In.
      //
      // Note that if BuiltinException exception is thrown the dropArgs code will not
      // be run, and the wrapped method is assumed to *not* have changed the rootCount even of
      // @RC.In arguments, so we can use the args array in our stack entry.

      // A MethodHandle with signature `void <- (TState, Object[])`
      MethodHandle dropArgs = TState.DROP_REFERENCE_OBJ_ARRAY;
      if (!rcInValues.isEmpty()) {
        for (int i = 0; i < numValues; i++) {
          if (rcInValues.test(i)) {
            // A MethodHandle that nulls out this element of the arg.
            MethodHandle clearElement = MethodHandles.insertArguments(SET_OBJECT_ARRAY, 1, i, null);
            // Applies clearElement to the Object[] before passing it to dropArgs.
            // Note that since clearElement returns void, this doesn't affect the arguments passed
            // to dropArgs.
            dropArgs = MethodHandles.foldArguments(dropArgs, 1, clearElement);
          }
        }
      }
      // Skip dropArgs if we're generating code
      dropArgs =
          MethodHandles.guardWithTest(
              TState.HAS_CODEGEN, MethodHandles.empty(dropArgs.type()), dropArgs);
      // Run dropArgs after mh, on the same arguments
      dropArgs = MethodHandles.dropArguments(dropArgs, 1, ResultsInfo.class, MethodMemo.class);
      mh = MethodHandles.foldArguments(dropArgs, mh);
      builtinEntry = new BuiltinEntry(impl, mh, where, valueNames(numValues));
    }

    /** Returns the names of this method's last {@code n} Value parameters. */
    private String[] valueNames(int n) {
      if (n == 0) {
        return EMPTY_STRINGS;
      }
      String[] result = new String[n];
      Parameter[] params = m.getParameters();
      int start = valueStart + numValues - n;
      Arrays.setAll(result, i -> params[start + i].getName());
      return result;
    }

    /** Returns the names of any parameters following a {@link Saved} annotation. */
    String[] savedNames() {
      return valueNames(numSaved);
    }

    /**
     * Checks that this method definition has the expected number of arguments and results.
     *
     * <p>We can only check the number of results if the method returns {@code Value} (in which case
     * {@code numResults} must be 1); otherwise the method calls {@link TState#setResults} and we'll
     * have to rely on runtime checks.
     */
    void checkSignature(String where, int numArgs, int numResults, boolean savedOK) {
      Preconditions.checkArgument(savedOK || numSaved == 0, "@Saved not expected (%s)", where);
      Preconditions.checkArgument(
          numArgs == -1 || numValues == numArgs + numSaved, "Args don't match method (%s)", where);
      Preconditions.checkArgument(
          numResults == 1 || !returnsValue,
          "Method with %s results can't return Value (%s)",
          numResults,
          where);
    }
  }

  /**
   * Calls {@code first} followed by {@code second}, passing shared arguments to both of them.
   *
   * <p>Returns a MethodHandle with the same parameter types as {@code first} and the same result
   * type as {@code second}.
   *
   * <p>If {@code first} returns void, the arguments to {@code second} will be a prefix of the
   * arguments to {@code first}.
   *
   * <p>If {@code first} returns non-void, the arguments to {@code second} will be a prefix of the
   * arguments to {@code first} followed by the result of {@code first}.
   */
  private static MethodHandle sequence(MethodHandle first, MethodHandle second) {
    int numArgsToFirst = first.type().parameterCount();
    int numFirstResults = (first.type().returnType() == void.class) ? 0 : 1;
    int numOriginalArgsToSecond = second.type().parameterCount() - numFirstResults;
    Preconditions.checkArgument(numOriginalArgsToSecond <= numArgsToFirst);
    MethodHandle mh = MethodHandles.collectArguments(second, numOriginalArgsToSecond, first);
    MethodType type = first.type().changeReturnType(second.type().returnType());
    int[] perm = new int[numArgsToFirst + numOriginalArgsToSecond];
    for (int i = 0; i < numOriginalArgsToSecond; i++) {
      perm[i] = i;
    }
    for (int i = 0; i < numArgsToFirst; i++) {
      perm[numOriginalArgsToSecond + i] = i;
    }
    return MethodHandles.permuteArguments(mh, type, perm);
  }

  /** A MethodHandle of type `Value <- (Object[], int)` that returns the specified array element. */
  private static final MethodHandle GET_VALUE_FROM_OBJECT_ARRAY =
      MethodHandles.arrayElementGetter(Object[].class)
          .asType(MethodType.methodType(Value.class, Object[].class, int.class));

  /**
   * A MethodHandle of type `void <- (Object[], int, Object)` that sets the specified array element.
   */
  private static final MethodHandle SET_OBJECT_ARRAY =
      MethodHandles.arrayElementSetter(Object[].class);

  /** The MethodType of {@link MethodImpl#execute}. */
  private static final MethodType EXEC_TYPE =
      MethodType.methodType(
          void.class, TState.class, ResultsInfo.class, MethodMemo.class, Object[].class);

  /** A MethodHandle of type `void <- (TState, Value)` that calls {@link #checkForResult}. */
  private static final MethodHandle CHECK_FOR_RESULT =
      Handle.forMethod(BuiltinSupport.class, "checkForResult", TState.class, Value.class);

  /**
   * Checks the result returned by a builtin method, and calls {@link TState#setResult(Value)} if it
   * is non-null.
   */
  static void checkForResult(TState tstate, Value result) {
    if (result != null) {
      tstate.setResult(result);
    }
  }

  /** Creates a builtin method from a subclass of BuiltinMethod. */
  private static void addMethodFromNestedClass(
      String parentName,
      Class<?> klass,
      Map<String, VmType> types,
      Map<String, VmFunction> functions) {
    String cName = parentName + "." + klass.getSimpleName();
    Preconditions.checkArgument(
        Modifier.isStatic(klass.getModifiers()), "Should be static (%s)", cName);
    BuiltinImpl impl = new BuiltinImpl(cName);
    ParsedSignature signature = null;
    // First pass: find the begin method (which will be annotated with @Core.Method).
    for (Method m : klass.getDeclaredMethods()) {
      Core.Method annotation = m.getAnnotation(Core.Method.class);
      if (annotation == null) {
        continue;
      }
      Preconditions.checkArgument(signature == null, "Multiple @Methods (%s)", cName);
      String mName = m.getName();
      String whereBegin = cName + "." + mName;
      // For simplicity we don't support combining @Method and @Continuation, although in principle
      // we could.
      Preconditions.checkArgument(
          !(m.isAnnotationPresent(Continuation.class)
              || m.isAnnotationPresent(LoopContinuation.class)),
          "@Method can't be a @Continuation (%s)",
          whereBegin);
      signature = new ParsedSignature(whereBegin, annotation.value(), types, functions);
      Prepared prepared = new Prepared(whereBegin, m, functions, impl);
      prepared.checkSignature(
          whereBegin, signature.function.numArgs, signature.function.numResults, false);
      impl.setBuiltinEntry(prepared.builtinEntry);
    }
    Preconditions.checkArgument(signature != null, "No @Method (%s)", cName);
    // Second pass: find all the continuation methods
    List<ContinuationMethod> continuations = new ArrayList<>();
    Set<String> continuationNames = new HashSet<>();
    for (Method m : klass.getDeclaredMethods()) {
      String mName = m.getName();
      String where = cName + "." + mName;
      Continuation annotation = m.getAnnotation(Continuation.class);
      LoopContinuation annotation2 = m.getAnnotation(LoopContinuation.class);
      if (mName.equals("loopBound")) {
        Preconditions.checkArgument(
            Modifier.isStatic(m.getModifiers()), "Should be static (%s)", where);
        Preconditions.checkArgument(
            m.getReturnType() == int.class, "Return type should be int (%s)", where);
        Class<?>[] params = m.getParameterTypes();
        Preconditions.checkArgument(
            params.length == 1 && params[0].equals(Object[].class),
            "Parameter type should be Object[] (%s)",
            where);
        Preconditions.checkArgument(
            annotation == null && annotation2 == null, "Should not be annotated (%s)", where);
        m.setAccessible(true);
        MethodHandle mh = Handle.forMethod(m);
        @SuppressWarnings("unchecked")
        ToIntFunction<Object[]> bounder =
            MethodHandleProxies.asInterfaceInstance(ToIntFunction.class, Handle.forMethod(m));
        impl.setLoopBounder(bounder);
      }
      if (annotation == null && annotation2 == null) {
        continue;
      }
      Preconditions.checkArgument(
          continuationNames.add(mName), "Ambiguous continuation name %s", where);
      Preconditions.checkArgument(
          annotation == null || annotation2 == null,
          "@Continuation cannot also be a @LoopContinuation (%s)",
          where);
      int order = (annotation != null) ? annotation.order() : annotation2.order();
      Prepared prepared = new Prepared(where, m, functions, impl);
      prepared.checkSignature(where, -1, signature.function.numResults, true);
      continuations.add(
          new ContinuationMethod(
              impl,
              mName,
              order,
              annotation2 != null,
              prepared.builtinEntry,
              prepared.savedNames()));
    }
    impl.setContinuations(continuations);
    // Third pass: initialize all the Caller and ExtraValueMemo fields
    for (Field f : klass.getDeclaredFields()) {
      Class<?> fType = f.getType();
      if (BuiltinStatic.class.isAssignableFrom(fType)) {
        String fName = f.getName();
        String where = cName + "." + fName;
        int modifiers = f.getModifiers();
        Preconditions.checkArgument(
            Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers),
            "Should be static final (%s)",
            where);
        f.setAccessible(true);
        BuiltinStatic value;
        try {
          value = (BuiltinStatic) f.get(null);
        } catch (IllegalAccessException e) {
          throw new AssertionError(e);
        }
        value.setup(where, impl, functions);
      }
    }
    impl.createMethod(signature);
  }
}
