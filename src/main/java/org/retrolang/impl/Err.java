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

/**
 * A SimpleStackEntryType for errors, with a static instance for each error that can be thrown by
 * the VM.
 */
public class Err extends BaseType.SimpleStackEntryType {
  Err(String msg, String... localNames) {
    super(msg, localNames);
  }

  /**
   * Builtin methods may report an error by throwing a BuiltinException that wraps a stack entry.
   */
  public static final class BuiltinException extends Exception {
    // The code that catches this exception ({@link BuiltinSupport#handleException} is
    // responsible for storing this value somewhere else.
    @RC.Counted private Value stackEntry;

    private BuiltinException(@RC.In Value stackEntry) {
      assert stackEntry.baseType() instanceof Err;
      this.stackEntry = stackEntry;
    }

    Err baseType() {
      return (Err) stackEntry.baseType();
    }

    @RC.Out
    Value takeStackEntry() {
      assert stackEntry != null && baseType() != ESCAPE;
      Value result = stackEntry;
      stackEntry = null;
      return result;
    }

    /**
     * Push two entries on the stack: one describing this error, and one describing the method call
     * in which it happened.
     *
     * @param entryType a StackEntryType for the method reporting the error
     * @param args the args to the method
     */
    void push(TState tstate, StackEntryType entryType, @RC.In Object[] args) {
      assert !tstate.unwindStarted();
      Value entry;
      if (entryType.isSingleton()) {
        tstate.dropReference(args);
        entry = entryType.asValue();
      } else {
        entry = new CompoundValue(tstate, entryType, args);
      }
      tstate.pushUnwind(entry);
      tstate.pushUnwind(takeStackEntry());
    }
  }

  /** Returns a BuiltinException corresponding to this Err. */
  public BuiltinException asException() {
    // TODO (mdixon): since we throw away these exceptions without ever looking at their Java
    // stacks, would it be worth precreating them (for singleton Errs)?  We'd just need to change
    // takeStackEntry() to not null it out in that case.
    return new BuiltinException(asValue());
  }

  /** Returns a BuiltinException corresponding to this Err. */
  public BuiltinException asException(TState tstate, @RC.In Value... locals) {
    return new BuiltinException(tstate.compound(this, locals));
  }

  /** Throws {@link #asException} unless {@code check} is true. */
  public void unless(boolean check) throws BuiltinException {
    if (!check) {
      throw asException();
    }
  }

  /** Throws {@link #asException} unless {@code check} is true. */
  public void unless(Condition check) throws BuiltinException {
    if (check != Condition.TRUE) {
      doCheck(check);
    }
  }

  /** Throws {@link #asException} if {@code check} is true. */
  public void when(boolean check) throws BuiltinException {
    if (check) {
      throw asException();
    }
  }

  /** Throws {@link #asException} if {@code check} is true. */
  public void when(Condition check) throws BuiltinException {
    if (check != Condition.FALSE) {
      doCheck(check.not());
    }
  }

  private void doCheck(Condition mustBeTrue) throws BuiltinException {
    if (mustBeTrue == Condition.FALSE) {
      throw asException();
    }
    CodeGen codeGen = TState.get().codeGen();
    if (codeGen.cb.nextIsReachable()) {
      codeGen.escapeUnless(mustBeTrue);
      if (!codeGen.cb.nextIsReachable()) {
        // All paths branched to the escape, so there's no reason to continue generating code here.
        throw asException();
      }
    }
  }

  public static final Err ESCAPE = new Err("Escape");

  public static final Err UNDEFINED_VAR = new Err("Undefined var", "varName");

  public static final Err NO_METHOD = new Err("No matching method", "function", "args");
  public static final Err MULTIPLE_METHODS =
      new Err("Multiple matching methods", "args", "method1", "method2");

  public static final Err OUT_OF_MEMORY = new Err("Out of memory");

  static final Err INTERNAL_ERROR = new Err("Internal error", "details");

  public static final Err ASSERTION_FAILED = new Err("Not true");
  public static final Err ASSERTION_FAILED_WITH_MSG = new Err("Error", "msg");

  public static final Err NOT_BOOLEAN = new Err("Not a Boolean");

  public static final Err CANT_UNCOMPOUND =
      new Err("Not a compound type from this module", "value");

  public static final Err COLLECTOR_SETUP_RESULT =
      new Err("collectorSetup() should return {canParallel, eKind, initialState, loop} struct");

  public static final Err INVALID_ARGUMENT = new Err("Invalid argument");

  public static final Err RANGE_IS_UNBOUNDED = new Err("Range is unbounded");

  public static final Err RANGE_HAS_NO_LOWER_BOUND = new Err("Range has no lower bound");

  public static final Err RANGE_HAS_NO_UPPER_BOUND = new Err("Range has no upper bound");

  public static final Err INVALID_SIZES = new Err("Result of sizes() is invalid");

  public static final Err NOT_PAIR = new Err("Not a two-element array");

  public static final Err SEQUENTIAL_COLLECTOR =
      new Err("Can't use sequential collector in a parallel loop");

  public static final Err KEYED_COLLECTOR =
      new Err("Can't use a keyed collector in a loop with no collection");

  public static final Err DOUBLE_EMIT = new Err("Can't emit twice to keyed collector");

  public static final Err EMIT_ALL_KEYED = new Err("Can't emitAll to keyed collector");

  public static final Err INHERIT_KEYED_COLLECTOR = new Err("Can't inherit a keyed collector");

  public static final Err PARSE_FAILED = new Err("Input does not match parser");

  public static final Err REPEATED_EMPTY_PARSE = new Err("Repeated parser matches empty string");

  /** {@code futureStack} is an array of stack entries, from top to base. */
  public static final Err FUTURE_ERROR =
      new Err("Error while computing future() value", "futureStack");
}
