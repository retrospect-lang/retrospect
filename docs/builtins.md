# Defining Built-in Methods

[TOC]

In addition to the basic representations of values and types, and the basic
mechanics of instruction execution, the VM is responsible for implementing
methods for the functions defined in the Core module (all Core functions have at
least one method defined by the VM, but some (such as `pipe`) have many).

Built-in methods are defined by Java methods. As a very simple example, the
`loopExit` function (which wraps the given value in a `LoopExit` type) is
documented as being equivalent to this Retrospect code:

```
compound LoopExit
function loopExit(finalState) =
    finalState is LoopExit ? finalState : LoopExit_(finalState)
```

but is actually implemented by this Java code:

```
@Core.Public
static final BaseType.Named LOOP_EXIT = newBaseType("LoopExit", 1);

@Core.Public
public static final VmFunction LOOP_EXIT_FN = newFunction("loopExit", 1);

@Core.Method("loopExit(_)")
static Value loopExit(TState tstate, @RC.In Value finalState) {
  return finalState.isa(Core.LOOP_EXIT)
      ? finalState : tstate.compound(Core.LOOP_EXIT, finalState);
}
```

The `@Core.Method` annotation indicates that this Java method defines a
Retrospect method for the `loopExit` function (of one argument) with no method
predicate (since no type is specified for the single argument).

The Java method also happens to be named `loopExit`, but that name is
inconsequential; when a function is implemented by a single method we typically
give it the same name as the Retrospect function, but when multiple methods are
defined for a single Retrospect function Java requires that they have distinct
names.

The Java method takes the `TState` as its first argument, followed by a `Value`
for each of the Retrospect function's arguments. The body of the method is
responsible for either returning a Value for the result or throwing a
`BuiltinException` if the function invocation should fail.

(Since Java methods can only return a single result, methods implementing
functions with more than one result call `tstate.setResults(result1, result2,
...)` and return void.)

Note that the `@Core.Method` annotation implies `@RC.Out`, i.e. that the caller
is responsible for storing the result or decrementing its reference count. The
author of a built-in method may choose whether or not to annotate each `Value`
argument with `@RC.In`; the automatically-generated wrapper will behave
accordingly. In the example above we used `@RC.In` because it made the body
simpler, but it could just as well have been written

```
@Core.Method("loopExit(_)")
static Value loopExit(TState tstate, Value finalState) {
  return finalState.isa(Core.LOOP_EXIT)
      ? addRef(finalState) : tstate.compound(Core.LOOP_EXIT, addRef(finalState));
}
```

As another example, here is the implementation of numeric division (this is the
code that is called for e.g. `a = 1/2`):

```
@Core.Method("divide(Number, Number)")
static Value divideNumbers(TState tstate, Value x, Value y) {
  return NumValue.of(NumValue.asDouble(x) / NumValue.asDouble(y), tstate);
}
```

Since the `Core.Method` annotation restricts this method to only be used when
both arguments are Numbers, it is safe for it to call `NumValue.asDouble()` on
them without checking their types.

For a more interesting example, here is the implementation of numeric addition:

```
@Core.Method("add(Number, Number)")
static Value addNumbers(TState tstate, Value x, Value y) {
  if (x instanceof NumValue.I && y instanceof NumValue.I) {
    try {
      int i = Math.addExact(((NumValue.I) x).value, ((NumValue.I) y).value);
      return NumValue.of(i, tstate);
    } catch (ArithmeticException e) {
      // fall through
    }
  }
  double d = NumValue.asDouble(x) + NumValue.asDouble(y);
  return NumValue.of(d, tstate);
}
```

If given two integers it attempts to do integer addition, but uses the
`Math.addExact()` function to catch the (presumably rare) situations where
integer overflow would cause an incorrect result, and in that case falls back to
`double` addition.

(Simply always returing a `double` result would also be correct -- integers
represented as doubles should work just as well as those represented as
integers -- but would likely make subsequent operations less efficient.)

As one more example, this is the implementation of array concatenation (the `&`
operator applied to two arrays):

```
@Core.Method("concat(Array, Array)")
static Value concatArrays(TState tstate, Value x, Value y) {
  int xSize = x.baseType().size();
  int ySize = y.baseType().size();
  if (xSize == 0) {
    return addRef(y);
  } else if (ySize == 0) {
    return addRef(x);
  } else {
    return CompoundValue.of(tstate, Core.FixedArrayType.withSize(xSize + ySize),
        i -> (i < xSize) ? x.element(i) : y.element(i - xSize));
  }
}
```

## Simple nested function calls

Methods that need to make calls to other Retrospect functions involve some
additional steps. As an example, the `nextState()` method for the `sum()`
reducer uses the core `add()` function to combine the input with its current
state, enabling it to work equally well with numbers, matrices, or any other
values that define `add()` appropriately. In Retrospect it would be written as:

```
// Sum is a private type returned by sum()
method nextState(Sum, state, value) = state + value
```

Rather than making a synchronous call to the `add()` function, the Java method
must request a `Caller` object for the function it plans to call, and use
`tstate.startCall()` to make the call:

```
@Core.Method("nextState(Sum, _, _)")
static void nextStateSum(TState tstate,
                         Value reducer, @RC.In Value state, @RC.In Value value,
                         @Fn("add:2") Caller add) {
  tstate.startCall(add, state, value);
}
```

Each Caller argument must have a `@Fn` annotation identifying the function that
will be called (in this case, `add` with two arguments). As the name `startCall`
suggests, the actual function call will happen after the `nextStateSum` Java
method returns, and its result will be returned as the result of the original
call to `nextState()` (note that `nextStateSum` returns `void` and does not call
`tstate.setResult()`; it would be an error to both return a result and call
`startCall`).

## General nested function calls

The approach described in the previous section is convenient for simple cases,
but is limited: only a single function can be called, and its result must be
returned as the result of the original function call.

Methods that require more flexibility must first define a new subclass of
BuiltinMethod, and put the `@Core.Method` annotation on one of its methods
(conventionally named `begin`). The subclass can define Callers for each of the
functions it wishes to call, and a *continuation* to be executed when the nested
call completes. For example, this Retrospect method enables Arrays to be used as
one-dimensional Matrices, by implementing the `sizes()` function:

```
method sizes(Array array) = [size(array)]
```

It calls the Array's `size()` function, and then wraps the result in a
single-element array. The implementation in Java looks like

```
static class ArraySizes extends BuiltinMethod {
  static final Caller size = new Caller("size:1", "afterSize");

  @Core.Method("sizes(Array)")
  static void begin(TState tstate, @RC.In Value array) {
    tstate.startCall(size, array);
  }

  @Continuation
  static Value afterSize(TState tstate, @RC.In Value sizeResult) {
    return tstate.arrayValue(sizeResult);
  }
}
```

Note that the Caller is declared as a static field of the BuiltinMethod
subclass, and its constructor takes both the id of the function called
("size:1") and the name of the (Java) method to be called when the (Retrospect)
function returns ("afterSize").

The `begin` method and each `@Continuation` method must do exactly one of these
five things when called:

*   Call `tstate.setResults()` (execution of the built-in method will complete
    when this begin or after method returns).
*   Return a non-null Value (if the begin or after method was declared to return
    Value; equivalent to calling `tstate.setResults()` with the returned Value).
*   Throw a BuiltinMethodException (terminates execution of the built-in
    method).
*   Call `tstate.startCall()` (the nested call will start when this begin or
    after method returns; if the nested call completes successfully, either the
    built-in method completes or execution resumes with the corresponding
    continuation method, depending on which Caller was passed to `startCall`).
*   Call `tstate.jump()` (see below).

It is often necessary to pass additional values through to the continuation
method. As an example, the composition of Lambdas (applying two Lambda values in
succession, written `first | second`) could be implemented with this Retrospect
code:

```
private compound LambdaComposition is Lambda

method pipe(Lambda first, Lambda second) = LambdaComposition_({first, second})

method at(LambdaComposition composite, arg) {
  r = at(composite_.first, arg)
  return r is Absent ? Absent : at(composite_.second, r)
}
```

The equivalent Java code is

```
static class AtLambdaComposition extends BuiltinMethod {
  static final Caller atFirst = new Caller("at:2", "afterFirst");
  static final Caller atSecond = new Caller("at:2", "afterSecond");

  @Core.Method("at(LambdaComposition, _)")
  static void begin(TState tstate, Value composite, @RC.In Value arg) {
    Value first = composite.element(0);
    Value second = composite.element(1);
    tstate.startCall(atFirst, first, arg).saving(second);
  }

  @Continuation
  static void afterFirst(TState tstate, @RC.In Value r, @Saved Value second) {
    if (r.is(Core.ABSENT)) {
      tstate.setResults(Core.ABSENT);
    } else {
      tstate.startCall(atSecond, addRef(second), r);
    }
  }

  @Continuation(order = 2)
  static Value afterSecond(TState tstate, @RC.In Value r2) {
    return r2;
  }
}
```

Note the `.saving(second)` addition to the first `startCall`, which matches the
`@Saved Value second` argument to `afterFirst`; the value passed to `.saving()`
will be provided as the corresponding `@Saved` argument when the continuation is
eventually called.

Continuations can have any number of Value arguments following a `@Saved`
annotation, but there must be a `saving()` call with the same number of values
at the corresponding `startCall` site.

Also note that we need a separate `Caller` for each of the `at` calls, since we
want different behavior after the call. However since the continuation for the
second `at` call just returns the result, it could have used the simpler form
with a Caller argument and no continuation:

```
static class AtLambdaComposition extends BuiltinMethod {
  static final Caller atFirst = new Caller("at:2", "afterFirst");

  @Core.Method("at(LambdaComposition, _)")
  static void begin(TState tstate, Value composite, @RC.In Value arg) {
    Value first = composite.element(0);
    Value second = composite.element(1);
    tstate.startCall(atFirst, first, arg).saving(second);
  }

  @Continuation
  static Value afterFirst(TState tstate, @RC.In Value r, @Saved Value second,
                          @Fn("at:2") Caller at) {
    if (r.is(Core.ABSENT)) {
      return Core.ABSENT;
    } else {
      tstate.startCall(at, addRef(second), r);
      return null;
    }
  }
}
```

Note also the `(order = 2)` on the second `@Continuation` (in the first version
of the code). If a call is started in a method with order *n* its continuation
must have order at least *n+1*. The `begin` method has order 0, and
`@Continuation` methods default to order 1, but classes that make a sequence of
calls must explicitly assign an order to later continuations to ensure a
strictly increasing order.

In return for the awkwardness of writing function calls this way, the built-in
method logic takes care of stack unwinding, generating appropriate stack entries
as needed for errors, tracing, blocking, or snapshots.

## BuiltinException

Built-ins can report errors (and begin stack unwinding) by throwing a
BuiltinException. For example, the two-argument `max()` function has a default
method

```
method max(x, y) default = (x < y) ? y : x
```

that is implemented by this Java code:

```
static class Max2 extends BuiltinMethod {
  static final Caller lessThan = new Caller("lessThan:2", "afterLessThan");

  @Core.Method("max(_, _) default")
  static void begin(TState tstate, @RC.In Value x, @RC.In Value y) {
    tstate.startCall(lessThan, addRef(x), addRef(y)).saving(x, y);
  }

  @Continuation
  static Value afterLessThan(Value xLess, @Saved Value x, Value y) throws BuiltinException {
    if (xLess.is(Core.TRUE)) {
      return addRef(y);
    } else if (xLess.is(Core.FALSE)) {
      return addRef(x);
    } else {
      throw new BuiltinException(Err.NOT_BOOLEAN);
    }
  }
}
```

The argument to `BuiltinException` is a StackEntry; usually (as in this case) a
singleton is sufficient since the following StackEntry will identify the
built-in being executed (in this case `Max2.afterLessThan`) and the values of
its arguments (in this case `xLess`, `x`, and `y`).

A built-in or continuation that throws a BuiltinException must do so before
changing any rootCounts or modifying its arguments.

## Looping and Skipping

BuiltinMethod provides some additional flexibility:

*   A Caller may be invoked from more than one place in the built-in's logic,
    and can be invoked again after processing the results of a previous call;
    this creates the ability to loop within a built-in.

*   Multiple Callers can share a continuation, if the functions they call return
    the same number of results.

*   By calling `jump` rather than `startCall`, a method can continue execution
    as if a Caller had returned the specified values, without actually making a
    function call. The arguments to `jump` must include both return values for
    each result of the function, plus values for each `@Saved` argument.

*   BuiltinMethods may also define continuations that are not associated with
    any Caller, but are `jump`ed to. These are sometimes useful for implementing
    more complex control flow.

A `@LoopContinuation` annotation is like a `@Continuation`, but relaxes the
usual rule strictly increasing `order`; it can be the continuation for a call or
jump from any method without regard to order.

As an example, the Core function `iterateUnbounded` calls a given lambda to
update the given state over and over until the state is a `LoopExit`, equivalent
to

```
function iterateUnbounded(lambda, state) {
  for sequential state {
    if state is LoopExit { break }
    state = at(lambda, state)
  }
  return state
}
```

The Java implementation:

```
static class IterateUnbounded extends BuiltinMethod {
  static final Caller at = new Caller("at:2", "afterAt");

  @Core.Method("iterateUnbounded(_, _)")
  static void begin(TState tstate, @RC.In Value lambda, @RC.In Value state) {
    // Continues execution at afterAt() as if at() had been called
    // and returned state
    tstate.jump("afterAt", state, lambda);
  }

  @LoopContinuation
  static Value afterAt(TState tstate, @RC.In Value state, @Saved Value lambda) {
    if (state.isa(Core.LOOP_EXIT)) {
      return state;
    } else {
      tstate.startCall(at, addRef(lambda), state).saving(addRef(lambda)));
    }
  }
}
```

One more example: piping a collection into a collector. In Retrospect:

```
default method pipe(Collection collection, Collector collector) {
  { eKind, initialState, loop, canParallel } = collectorSetup(collector, collection)
  if initialState is LoopExit {
    state = initialState
  } else if canParallel {
    state = enumerate(collection, eKind, loop, initialState)
  } else {
    state = iterate(collection, eKind, loop, initialState)
  }
  return loopExitState(state)
}
```

... and in Java:

```
static class PipeCollectionCollector extends BuiltinMethod {
  static final Caller collectorSetup = new Caller("collectorSetup:2", "afterCollectorSetup");
  static final Caller enumerate = new Caller("enumerate:4", "done");
  static final Caller iterate = new Caller("iterate:4", "done");

  @Core.Method("pipe(Collection, Collector) default")
  static void begin(TState tstate, @RC.In Value collection, @RC.In Value collector) {
    tstate.startCall(collectorSetup, collector, addRef(collection)).saving(collection);
  }

  @Continuation
  static void afterCollectorSetup(TState tstate, Value csResult, @Saved @RC.In Value collection)
      throws BuiltinException {
    // "collectorSetup() should return a {canParallel, eKind, initialState, loop} struct"
    Err.COLLECTOR_SETUP_RESULT.unless(SETUP_KEYS.matches(csResult));
    Value canParallel = csResult.peekElement(0);
    Value eKind = csResult.peekElement(1);
    Err.COLLECTOR_SETUP_RESULT.unless(
        canParallel.isa(Core.BOOLEAN).and(eKind.isa(ENUMERATION_KIND)));
    Value initialState = csResult.element(2);
    Value loop = csResult.element(3);
    initialState.isa(Core.LOOP_EXIT).test(
        () -> {
          tstate.dropValue(collection);
          tstate.jump("done", initialState);
        },
        () -> canParallel.is(Core.TRUE).test(
                  () -> tstate.startCall(
                            enumerate, collection, eKind, loop, initialState),
                  () -> tstate.startCall(
                            iterate, collection, eKind, loop, initialState)));
  }

  @Continuation(order = 2)
  static Value done(Value state) {
    return loopExitState(state);
  }
}
```

## Implementing Built-ins

(The following details are not needed in order to read or write built-in
methods, but may be useful in understanding the Retrospect implementation.)

The static initializer in `Core.java` calls `BuiltinSupport.addMethodsFrom()` on
each class that may contain methods annotated with `@Core.Method`.

`addMethodsFrom()` checks each method declared in the given class for a
`@Core.Method` annotation, and checks each nested class that is a subclass of
`BuiltinMethod`.

Top-level `@Core.Method`s are relatively simple: we parse the signature in the
`@Core.Method` annotation to determine the function, the method predicate, and
the "is default" flag, and we wrap the Java method so that it matches the
`MethodImpl` interface (details below). That gives us everything we need to
construct a `VmMethod`, which we can add to the specified function.

Nested `BuiltinMethod` classes require a few more steps:

*   A new `BuiltinInfo` is created; this will provide the linkage from
    continuation names to the corresponding wrapped Java methods.
*   The "begin" method (annotated with `@Core.Method`) is located and wrapped in
    much the same way as a top-level method.
*   Each `@Continuation` method is also wrapped to match the `MethodImpl`
    interface (although they will not be added as methods of a function we need
    to be able to call them in a uniform way).
*   Finally each of the `Caller` fields is initialized to point to the
    appropriate VmFunction and its wrapped continuation.

Wrapping a Java method to create an implementation of `MethodImpl` is done by
`BuiltinSupport.Prepared`; the adaptations it must make are:

*   Spread the `Object[]` that is passed to `execute()` into separate `Value`
    arguments.
*   After the Java method returns, `dropReference()` the args array (since it is
    `@RC.In`), but first remove any arguments that were annotated `@RC.In` on
    the Java method (since that method has taken responsibility for them).
*   If the Java method has type `Value` and returned a non-null Value, pass it
    to `tstate.setResults()`.
*   Bind each `Caller` argument to a tail Caller for the specified function.
*   If the Java method can throw BuiltinException, catch it and push two stack
    entries: one from the BuiltinException, and one identifying the Java method
    and its args (if this happens we won't `dropReference()` the args array or
    clear any of its entries).
*   If the Java method returns normally, call `tstate.finishBuiltIn()` to handle
    calls to `startCall()` and `jump()`.
