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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.retrolang.impl.Value.addRef;

import com.google.common.collect.ImmutableMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.retrolang.Vm.Access;
import org.retrolang.impl.BuiltinMethod.Caller;
import org.retrolang.impl.BuiltinMethod.Fn;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.util.Bits;

@RunWith(JUnit4.class)
public class BuiltinSupportTest {

  private static final int MEMORY_LIMIT = 3000;

  // See setup()
  TState tstate;
  ResourceTracker tracker;

  @Before
  public void setup() {
    tracker = new ResourceTracker(new Scope(), MEMORY_LIMIT, true);
    tstate = TState.resetAndGet();
    tstate.bindTo(tracker);
  }

  @After
  public void checkAllReleased() {
    assertThat(tstate.unwindStarted()).isFalse();
    assertThat(tstate.bindTo(null)).isSameInstanceAs(tracker);
    assertThat(tracker.allReleased()).isTrue();
  }

  private MethodMemo newMemo(VmMethod method, Object[] args) {
    MemoMerger merger = tstate.scope().memoMerger;
    synchronized (merger) {
      return method.newMemo(merger, args);
    }
  }

  /// Retrieves the results of the last function call and verifies that they are identical to the
  /// given Values.
  private void verifyResults(TState tstate, Value... expected) {
    for (int i = 0; i < expected.length; i++) {
      Value v = tstate.takeResult(i);
      assertThat(v).isSameInstanceAs(expected[i]);
      tstate.dropValue(v);
    }
    tstate.clearResults();
  }

  /// Used by several tests.
  static class Module1 {
    /// `type MyUnion`
    static final VmType myUnion;

    /// `singleton Enum1`
    static final Singleton enum1;

    /// `singleton Enum2 is MyUnion`
    static final Singleton enum2;

    /// `function fn(x, y=, z)`
    static final VmFunction fn;

    static {
      ModuleBuilder mb = new ModuleBuilder();
      myUnion = mb.newUnionType("MyUnion", Access.VISIBLE);
      enum1 = mb.newSingleton("Enum1", Access.VISIBLE);
      enum2 = mb.newSingleton("Enum2", Access.VISIBLE, myUnion);
      fn =
          mb.newFunction(
              "fnA",
              /* numArgs= */ 3,
              /* argIsInout= */ Bits.of(1),
              /* hasResult= */ true,
              Access.VISIBLE);

      ImmutableMap<String, VmType> types =
          ImmutableMap.of(
              "Enum1", enum1.asType(),
              "Enum2", enum2.asType(),
              "MyUnion", myUnion);
      ImmutableMap<String, VmFunction> functions = ImmutableMap.of("fn:3", fn);
      synchronized (mb) {
        BuiltinSupport.addMethodsFrom(Module1.class, types, functions);
      }
      mb.build();
    }

    /// ```
    /// method fn(x, y=, Enum1) {
    ///   y = x
    ///   return x
    /// }
    /// ```
    @Core.Method("fn(_, _, Enum1)")
    static void method1(TState tstate, Value x, Value y, Value z) {
      tstate.setResults(addRef(x), addRef(x));
    }

    /// ```
    /// method fn(x, y=, z) default {
    ///   y = z
    ///   return x
    /// }
    /// ```
    @Core.Method("fn(_, _, _) default")
    static void method2(TState tstate, @RC.In Value x, @RC.In Value y, @RC.In Value z) {
      // This method is buggy: it claims to decrement y's root count, but doesn't.
      tstate.setResults(x, z);
    }

    /// ```
    /// method fn(x, MyUnion y=, z) {
    ///   assert z is Singleton // not actually a Retrospect type
    ///   y = z
    ///   return z
    /// }
    /// ```
    @Core.Method("fn(_, MyUnion, _)")
    static void method3(TState tstate, Value x, Value y, @RC.In Value z) throws BuiltinException {
      Err.ASSERTION_FAILED.when(z instanceof Singleton);
      tstate.setResults(addRef(z), z);
    }
  }

  @Test
  public void basic() throws Exception {
    // An argument sequence that matches method1
    Object[] args = tstate.allocObjectArray(3);
    StringValue s1 = new StringValue(tstate, "foo");
    StringValue s2 = new StringValue(tstate, "bar");
    args[0] = s1;
    args[1] = s2;
    args[2] = Module1.enum1;
    VmMethod method = Module1.fn.findMethod(args);
    method.impl.execute(tstate, null, method.fixedMemo, args);
    verifyResults(tstate, s1, s1);
    // s1 and s2 refcounts should now be zero
  }

  @Test
  public void rcIn() throws Exception {
    // An argument sequence that matches method2
    Object[] args = tstate.allocObjectArray(3);
    StringValue s1 = new StringValue(tstate, "foo");
    StringValue s2 = new StringValue(tstate, "bar");
    args[0] = s1;
    args[1] = s2;
    args[2] = Module1.enum2;
    VmMethod method = Module1.fn.findMethod(args);
    method.impl.execute(tstate, null, method.fixedMemo, args);
    verifyResults(tstate, s1, Module1.enum2);
    // Because method2 has @RC.In on its second argument, that value didn't get its reference count
    // dropped.
    assertThat(s2.getRefCntForDebugging()).isEqualTo(1);
    // Drop it now, or the tracker.allReleased() check will fail.
    tstate.dropReference(s2);
  }

  @Test
  public void doesntThrow() throws Exception {
    // An argument sequence that matches method3 but doesn't trigger its exception
    Object[] args = tstate.allocObjectArray(3);
    StringValue s1 = new StringValue(tstate, "foo");
    args[0] = Module1.enum1;
    args[1] = Module1.enum2;
    args[2] = s1;
    VmMethod method = Module1.fn.findMethod(args);
    method.impl.execute(tstate, null, method.fixedMemo, args);
    verifyResults(tstate, s1, s1);
  }

  @Test
  public void doesThrow() throws Exception {
    // An argument sequence that matches method3 and triggers its exception
    Object[] args = tstate.allocObjectArray(3);
    StringValue s1 = new StringValue(tstate, "foo");
    args[0] = s1;
    args[1] = Module1.enum2;
    args[2] = Module1.enum2;
    VmMethod method = Module1.fn.findMethod(args);
    tstate.setStackRest(TStack.BASE);
    method.impl.execute(tstate, null, method.fixedMemo, args);
    assertThat(tstate.unwindStarted()).isTrue();
    TStack tstack = tstate.takeUnwind();
    assertThat(tstack.stringStream().toArray())
        .asList()
        .containsExactly(
            "⟦Not true⟧", "⟦Module1.method3 ∥ x=\"foo\", y=Enum2, z=Enum2⟧", "⟦StackBase⟧");
    tstate.dropReference(tstack);
  }

  static class WrongNumberOfArgs {
    /// Try to load the method defined in this class into a new module; expected to fail.
    static void loadIt() {
      ModuleBuilder mb = new ModuleBuilder();
      VmFunction fn =
          mb.newFunction(
              "fn",
              /* numArgs= */ 2,
              /* argIsInout= */ Bits.EMPTY,
              /* hasResult= */ true,
              Access.VISIBLE);
      ImmutableMap<String, VmType> types = ImmutableMap.of();
      ImmutableMap<String, VmFunction> functions = ImmutableMap.of("fn:2", fn);
      synchronized (mb) {
        BuiltinSupport.addMethodsFrom(WrongNumberOfArgs.class, types, functions);
      }
    }

    /// A bad builtin method definition (Java signature doesn't match Retrospect signature) to test
    /// error handling.
    @Core.Method("fn(_, _)")
    static void method1(TState tstate, Value x) {}
  }

  @Test
  public void wrongNumberOfArgs() throws Exception {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, WrongNumberOfArgs::loadIt);
    assertThat(e).hasMessageThat().startsWith("Args don't match method");
  }

  static class BadArgType {
    /// Try to load the method defined in this class into a new module; expected to fail.
    static void loadIt() {
      ModuleBuilder mb = new ModuleBuilder();
      VmFunction fn =
          mb.newFunction(
              "fn",
              /* numArgs= */ 2,
              /* argIsInout= */ Bits.EMPTY,
              /* hasResult= */ true,
              Access.VISIBLE);
      ImmutableMap<String, VmType> types = ImmutableMap.of();
      ImmutableMap<String, VmFunction> functions = ImmutableMap.of("fn:2", fn);
      synchronized (mb) {
        BuiltinSupport.addMethodsFrom(BadArgType.class, types, functions);
      }
    }

    /// A bad builtin method definition (Java arguments should be type Value) to test error
    /// handling.
    @Core.Method("fn(_, _)")
    static void method1(TState tstate, Value x, Object y) {}
  }

  @Test
  public void badArgType() throws Exception {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, BadArgType::loadIt);
    assertThat(e).hasMessageThat().startsWith("Bad method args");
  }

  static class SavedNotAllowed {
    /// Try to load the method defined in this class into a new module; expected to fail.
    static void loadIt() {
      ModuleBuilder mb = new ModuleBuilder();
      VmFunction fn =
          mb.newFunction(
              "fn",
              /* numArgs= */ 1,
              /* argIsInout= */ Bits.EMPTY,
              /* hasResult= */ true,
              Access.VISIBLE);
      ImmutableMap<String, VmType> types = ImmutableMap.of();
      ImmutableMap<String, VmFunction> functions = ImmutableMap.of("fn:1", fn);
      synchronized (mb) {
        BuiltinSupport.addMethodsFrom(SavedNotAllowed.class, types, functions);
      }
    }

    @Core.Method("fn(_)")
    static void method1(TState tstate, Value x, @BuiltinMethod.Saved Value notAllowed) {}
  }

  @Test
  public void savedNotAllowed() throws Exception {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, SavedNotAllowed::loadIt);
    assertThat(e).hasMessageThat().startsWith("@Saved not expected");
  }

  static class CallerTest {
    /// `function foo(x, y)`
    static final VmFunction foo;

    /// `function bar(x)`
    static final VmFunction bar;

    static {
      ModuleBuilder mb = new ModuleBuilder();
      foo =
          mb.newFunction(
              "foo",
              /* numArgs= */ 2,
              /* argIsInout= */ Bits.EMPTY,
              /* hasResult= */ true,
              Access.VISIBLE);
      bar =
          mb.newFunction(
              "bar",
              /* numArgs= */ 1,
              /* argIsInout= */ Bits.EMPTY,
              /* hasResult= */ true,
              Access.VISIBLE);

      ImmutableMap<String, VmFunction> functions = ImmutableMap.of("foo:2", foo, "bar:1", bar);
      synchronized (mb) {
        BuiltinSupport.addMethodsFrom(CallerTest.class, ImmutableMap.of(), functions);
      }
      mb.build();
    }

    /// `method foo(x, y) = (x == 0) ? bar(y) : foo(y, x)`
    @Core.Method("foo(_, _)")
    static void foo(
        TState tstate, Value x, Value y, @Fn("foo:2") Caller foo, @Fn("bar:1") Caller bar) {
      if (NumValue.equals(x, 0)) {
        tstate.startCall(bar, addRef(y));
      } else {
        tstate.startCall(foo, addRef(y), addRef(x));
      }
    }

    /// `method bar(x) { assert x is not number; return x }`
    @Core.Method("bar(_)")
    static Value bar(TState tstate, @RC.In Value x) throws BuiltinException {
      Err.INVALID_ARGUMENT.when(x instanceof NumValue);
      return x;
    }
  }

  @Test
  public void callerTest() throws Exception {
    // An argument sequence that causes foo to recursively call itself with (0, s1) and
    // then call bar(s1).
    Object[] args = tstate.allocObjectArray(3);
    StringValue s1 = new StringValue(tstate, "foo");
    args[0] = s1;
    args[1] = NumValue.ZERO;
    VmMethod method = CallerTest.foo.findMethod(args);
    MethodMemo memo = newMemo(method, args);
    method.impl.execute(tstate, null, memo, args);
    verifyResults(tstate, s1);
  }

  @Test
  public void callerTestWithThrow() throws Exception {
    // An argument sequence that causes foo to recursively call itself with (0, 1) and
    // then call bar(1), which errors so that we can see the stack.
    Object[] args = tstate.allocObjectArray(3);
    args[0] = NumValue.ONE;
    args[1] = NumValue.ZERO;
    VmMethod method = CallerTest.foo.findMethod(args);
    MethodMemo memo = newMemo(method, args);
    tstate.setStackRest(TStack.BASE);
    method.impl.execute(tstate, null, memo, args);
    assertThat(tstate.unwindStarted()).isTrue();
    TStack tstack = tstate.takeUnwind();
    assertThat(tstack.stringStream().toArray())
        .asList()
        .containsExactly(
            "⟦Invalid argument⟧",
            "⟦CallerTest.bar ∥ x=1⟧",
            "⟦CallerTest.foo⟧",
            "⟦CallerTest.foo⟧",
            "⟦StackBase⟧");
    tstate.dropReference(tstack);
  }
}
