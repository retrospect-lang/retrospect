# Enumeration and Collectors

[TOC]

## Overview

Processing each element of a collection is one of the core operations in
Retrospect, underlying both the `for` statement and many uses of the `|` (pipe)
operator. This document describes the types and functions that are used to
implement this kind of processing.

Note that most Retrospect code doesn't use these relatively low-level APIs;
`for` statements or pipes are usually the clearest way to express computations
over collections. The APIs described here may be needed

*   when defining a new collection type, or
*   when implementing some algorithms over collections whose flow of control
    doesn't correspond cleanly to the options provided by `for` statements.

First, some terminology:

*   *Enumerating* a collection executes some operation once for each element of
    the collection to produce a final result;
*   *Iterating* is sequential enumeration, where the processing of each element
    may depend on the results of processing previous elements in the collection.

Iteration is fundamentally sequential (we can only process an element after the
processing of the previous element is complete) and hence depends on the
ordering of the collection, while enumeration is more general and supports
parallel and unordered execution.

Both kinds of enumeration also support *early exit*: when processing any element
we may determine that the final result is now known, and there is no need to
process the rest of the collection.

## Kinds of Enumeration

For the purposes of enumeration we think of a collection as containing zero or
more (key, value) pairs. The value `Absent` may be used to indicate that there
is no value corresponding to a key, even though the key is present in the
collection's set of keys. For example, given the array

```
a = ["a", Absent, "b"]
```

*   `size(a)` returns 3
*   `keys(a)` returns `[[1], [2], [3]]`
*   `a[1]` returns `"a"`
*   `a[2]` returns `Absent`

When enumerating we can choose among three options, each identified by a
singleton:

*   `EnumerateValues`: enumeration will process only the value of each (key, value)
    pair, excluding those where the value is `Absent`. For the example above,
    enumeration would process `"a"` and `"b"`.
*   `EnumerateWithKeys`: enumeration will process (key, value) pairs, excluding
    those where the value is `Absent`. For the example above, enumeration would
    process `[[1], "a"]` and `[[3], "b"]`.
*   `EnumerateAllKeys`: enumeration will process all (key, value) pairs,
    including those where the value is `Absent`. For the example above, enumeration
    would process `[[1], "a"]`, `[[2], Absent]` and `[[3], "b"]`.

The type `EnumerationKind` contains these three singletons (and nothing else).

## Loops are state machines

The enumeration APIs represent the processing to be done for each element as a
state machine, comprising

* an initial state, and
* a function `nextState()` that takes the current state and an element of the
  collection and returns the new state.

We use values of type `Loop` to represent the state transitions:

```
open type Loop

// Returns the next state; item is a value (for EnumerateValues) or
// a [key, value] pair (for EnumerateWithKeys or EnumerateAllKeys)
open function nextState(loop, state, item)
```

For example, a simple state machine whose state is the maximum value it has
encountered so far (or Absent, if no elements have been processed) could be
written this way:

```
singleton MaxLoop is Loop

method nextState(MaxLoop, state, value) =
    (state is Absent or state < value) ? value : state
```

The function `iterate` sequentially processes the elements of a collection
using a state machine:

```
// Calls nextState with each element of the collection, returning the final
// state.
function iterate(collection, eKind, loop, initialState)
```

so e.g. `iterate([-1, 300, 7], EnumerateValues, MaxLoop, Absent)` returns `300`.

`iterate` also provides a means to stop the iteration before all elements have
been processed: if `nextState` returns a value of type `LoopExit`, `iterate`
will just return that state as its result without any further iteration.

```
compound LoopExit
// Returns a LoopExit wrapping the given value
function loopExit(finalState) = LoopExit_(finalState)
// Returns the value that was passed to loopExit()
function loopExitState(LoopExit exit) = exit_
```

For example, we could write a loop that determines whether any element of a
collection is equal to a given value, and if so returns the corresponding key:

```
compound SearchLoop is Loop
// Called with [key, value] pairs; returns loopExit(k) if this is the
// value we're looking for, otherwise returns state unchanged.
method nextState(SearchLoop loop, state, [k, v]) =
	(v == loop_) ? loopExit(k) : state

// If x is in collection, returns the corresponding key, otherwise Absent
function contains(collection, x) {
  // The initialState we provide here (None) doesn't matter -- anything other
  // than a LoopExit would work equally well.
  finalState = iterate(collection, EnumerateWithKeys, SearchLoop_(x), None)
  return (finalState is LoopExit) ? loopExitState(finalState) : Absent 
}
```

## Transforming Loops

Loops can be manipulated to create other Loops; one of the most useful
operations is to transform each incoming value:

```
compound TransformedLoop
// Transforms each incoming value with the given lambda.
function transformLoop(Lambda lambda, Loop loop) =
    TransformedLoop_({lambda, loop})

method nextState(TransformedLoop transformed, state, item) {
  item = transformed_.lambda @ item
  // If item transformed to Absent, skip the rest of the loop by returning state unchanged
  return (item is Absent) ? state : nextState(transformed_.loop, state, item) 
}
```

## Iterators

`iterate` is implemented using an *iterator*, which can produce the
elements of a collection as needed:

```
open type Iterator

// Either returns the next element of the iteration (a value or a [key, value]
// pair, depending on the eKind used to construct the iterator) and updates
// iterator to the remainder, or returns Absent if there are no more elements.
// The output value of iterator is unspecified if next() returns Absent.
open function next(iterator=)

// Some useful iterator constructors
function emptyIterator() = TrivialIterator_(Absent)
function oneElementIterator(x) = TrivialIterator_(x)

private compound TrivialIterator is Iterator
method next(TrivialIterator it=) {
  result = it_
  it = emptyIterator()
  return result
}
```

Each collection type defines an `iterator` method, which returns an appropriate
iterator for the given collection.  To provide additional flexibility, the
`iterator` function is passed the collection, the Loop that will process each element,
and the initial state for that loop:

```
// eKind must be one of EnumerateValues, EnumerateWithKeys, or EnumerateAllKeys
// In addition to returning an Iterator, this function may transform the given
// Loop and/or initial state; the returned Iterator should only be used with the
// returned Loop and state. 
open function iterator(collection, eKind, loop=, initialState=)
```

We now have all the pieces necessary to define `iterate`:

```
function iterate(collection, eKind, loop, state) {
  it = iterator(collection, eKind, loop=, state=)
  for sequential state, it {
    if state is LoopExit { break }
    element = next(it=)
    if element is Absent {
      state = finalState(loop, state)
      break
    }
    state = nextState(loop, state, element)
  }
  return state
}
```

As an example, here is the implementation of `iterator()` for a Range:

```
compound RangeIterator

method iterator(Range r, EnumerationKind eKind, loop=, state=) {
  min = min(r)
  assert min is not None
  // If keyOffset is not None, we will add it to the value to get the
  // corresponding key.
  // The first value (i.e. min) has key [1] (since Range is a type of Matrix).
  keyOffset = (eKind is EnumerateValues) ? None : 1 - min
  return RangeIterator_({next: min, max: max(r), keyOffset})
}

method next(RangeIterator it=) {
  { next, max, keyOffset } = it_
  if max is not None and next > max {
    return Absent
  }
  it_.next = next + 1
  return keyOffset is None ? next : [ [next + keyOffset], next ]
}
```

In this case the `iterator()` method always leaves the Loop and state arguments
unchanged.
On the other hand, to iterate over arrays we can just iterate over the range of
valid indices and transform each index to the corresponding array element:

```
method iterator(Array a, EnumerationKind eKind, loop=, initialState=) {
  lambda = applyToValues(i -> a[i], eKind)
  loop = transformLoop(lambda, loop)
  return iterator(1..size(a), eKind, loop=, initialState=)
}
```

While it would be possible to implement an ArrayIterator without transforming
the loop, doing it this way saves us the complexity of handling different
EnumerationKinds and skipping Absent when appropriate.

Note that when `iterate` reaches the end of the iterator it calls a `finalState`
method to give the Loop one last chance to update the state.  The default
method for that function leaves the state unchanged.

```
// Called once after all of the elements of a collection have been processed,
// if state is not a LoopExit
function finalState(loop, state)

default method finalState(Loop loop, state) = state

method finalState(TransformedLoop transformed, state) = finalState(transformed_.loop, state)
```

## Parallel Loops

Enabling parallel enumeration requires some additions to the Loop API. Since we may
now be processing subsets of the collection independently, we must be able to

* *fork* the state, so that each subset can update its own copy of the state;
  and
* *combine* multiple separately-updated states to get a final state.

In order to give the implementation maximum flexibility, parallel loops
implement two alternatives for forking state:

* creating a new "empty" state; or
* splitting an existing state into two states.

The default implementation of splitting just returns an empty state along with
the state it was given, but in some cases there may be efficiency benefits to
providing a specialized split method for a loop's state.

```
// Returns an empty state for the given loop.
open function emptyState(loop)

// Returns a new state and updates the given state; the result of combining
// those two states should be equivalent to the original state.
open function splitState(loop, state=)

// Combines two states.
// Will not be called with either state Absent or a LoopExit.
open function combineStates(loop, state1, state2)

// The default implementation just returns Absent; if that's not a suitable
// state for your loop you must override this method.
method emptyState(loop) default = Absent

// The default implementation just returns the result of emptyState(),
// leaving the given state unchanged.  It is not necessary to override this
// method, but for some loops there may be an efficiency benefit to doing so.
method splitState(loop, state=) default = emptyState(loop)
```

Given a parallelizable Loop (i.e. one that has appropriate methods for
`emptyState()`, `splitState()`, and `combineStates()`), the `enumerate()`
function is used to run it for each element of a collection.

```
// Calls nextState with each element of the collection, returning the final
// state.
open function enumerate(collection, eKind, loop, initialState)

// The default implementation falls back on sequential execution.
method enumerate(collection, eKind, loop, initialState) default =
    iterate(collection, eKind, loop, initialState)
```

Returning to our previous example, with one small addition we can make
`MaxLoop` parallelizable:

```
function combineStates(MaxLoop, state1, state2) =
    (state1 > state2) ? state1 : state2
```

`SearchLoop` is even simpler: since `nextState` only returns a LoopExit or the
original state, no `combineStates` method is needed, and `contains` can be made
parallelizable by simply replacing the call to `iterate` with a call to
`enumerate`.

(Note that doing so may change the behavior of `contains()`: while the
sequential version would always return the key of the first occurrence of `x`,
the version using `enumerate()` is only guaranteed to return the key of *some*
occurrence of `x`.)

A TransformedLoop is parallelizable if the inner loop is:

```
method emptyState(TransformedLoop transformed) = emptyState(transformed_.loop)
method splitState(TransformedLoop transformed, state=) = splitState(transformed_.loop, state=)
method combineStates(TransformedLoop transformed, state1, state2) =
    combineStates(transformed_.loop, state1, state2)
```

## Sequential loops without a collection

Retrospect also supports sequential loops without a collection. Since there is
no element to pass to the nextState function, it is just a simple Lambda from
state to state. The function `iterateUnbounded` takes such a Lambda an an
initial state, and calls the Lambda repeatedly until it returns a LoopExit
value. For example, we could compute the greatest common divisor of two integers
with:

```
function gcd(x, y) = iterateUnbounded(
        // next state lambda
        [u, v] -> (v == 0) ? loopExit(u) : [v, u % v],
        // initial state
        [abs(x), abs(y)])
```

The implementation of `iterateUnbounded` is equivalent to

```
function iterateUnbounded(lambda, state) {
  for sequential state {
    if state is LoopExit { break }
    state = lambda @ state
  }
  return state
}
```

There is no parallel equivalent to `iterateUnbounded`.

## Collectors

Most pipelines connect a source collection to a Collector such as `sum` or
`save`. Collectors combine all the information needed to process the elements
of a given collection and produce a final result, in a way that enables them to
be composed and manipulated.

```
open type Collector
// Returns a struct { eKind, loop, initialState, canParallel }
open function collectorSetup(collector, collection)

method pipe(Collection collection, Collector collector) {
  { eKind, loop, initialState, canParallel } = collectorSetup(collector, collection)
  if initialState is LoopExit {
    state = initialState
  } else if canParallel {
    state = enumerate(collection, eKind, loop, initialState)
  } else {
    state = iterate(collection, eKind, loop, initialState)
  }
  // The pipe result is the final state, unwrapped if it's a LoopExit
  return loopExitState(state)
}
```

## Reducers

The simplest way to implement a Collector is as a Reducer, which implements the
parallel loop functions and uses an empty state as its initial state:

```
open type Reducer is Collector

method collectorSetup(Reducer reducer, _) = {
    eKind: EnumerateValues,
    loop: reducer,
    initialState: emptyState(reducer),
    canParallel: True
  }
```

For example, here are some of the simplest Reducer definitions:

```
private compound SummingReducer is Reducer

function sum() = SummingReducer_(True)
function count() = SummingReducer_(False)

method emptyState(SummingReducer) = 0

method nextState(SummingReducer reducer, state, value) = state + (reducer_ ? value : 1)

method combineStates(SummingReducer, state1, state2) = state1 + state2
```

Another example, using LoopExit values:

```
private compound BooleanReducer is Reducer

function anyTrue() = BooleanReducer_({initial: False, exitOn: True})
function anyFalse() = BooleanReducer_({initial: False, exitOn: False})
function allTrue() = BooleanReducer_({initial: True, exitOn: False})
function allFalse() = BooleanReducer_({initial: True, exitOn: True})

// We don't need a combineStates method since our state is only ever
// Absent or LoopExit
method nextState(BooleanReducer br, Absent, Boolean value) =
    (value == br_.exitOn) ? loopExit(not br_.initial) : Absent
method finalState(BooleanReducer br, state) =
    state is Absent ? br_.initial : state
```

One more example:

```
private singleton SaveUnordered is Reducer

function saveUnordered() = SaveUnordered

method emptyState(SaveUnordered) = []
method nextState(SaveUnordered, array, value) = array & [value]
method combineStates(SaveUnordered, state1, state2) = state1 & state2
```

## Transforming collectors

Collectors can also be composed with Lambdas to create new Collectors; for
example,

```
function sumSq() = -> #^2 | sum
```

defines a Collector that computes the sum of the squares of its inputs. More
generally, `lambda | collector` applies the lambda to each incoming value and
collects the results, discarding any that transform to `Absent` unless the
collector's eKind is EnumerateAllKeys.

The implementation is straightforward; note that to avoid conflicting with the
previous method for `pipe` we must explicitly exclude Collections, which may
also be Lambdas:

```
method pipe(Lambda lambda, Collector collector) (lambda is not Collection) default =
    TransformedCollector_({lambda, collector})

private compound TransformedCollector is Collector

method collectorSetup(TransformedCollector tc, collection) {
  result = collectorSetup(tc_.collector, collection)
  lambda = applyToValues(tc_.lambda, result.eKind)
  result.loop = transformLoop(lambda, result.loop)
  return result
}
```

Collectors may also be forced to be sequential. For example,

```
if test(^bigCollection) | anyTrue { ... }
```

determines whether any element of the collection satisfies `test()`, and may use
parallel and out-of-order execution in doing so. If we had reason to believe
that elements at the beginning of the collection were the most likely to satisfy
`test()`, it might be more efficient to test them sequentially:

```
if test(^bigCollection) | sequentially(anyTrue) { ... }
```

The `sequentially(saveUnordered)` combination is so useful that it's given its
own name:

```
function saveSequential() = sequentially(saveUnordered)
```

e.g.

```
a = 1..10 | filter(-> (# % 2) == 1) | -> #^2 | saveSequential
```

sets `a` to `[1, 9, 25, 49, 81]`.

The implementation of `sequentially` is again straightforward:

```
function sequentially(Collector collector) = SequentialCollector_(collector)

private compound SequentialCollector is Collector

method collectorSetup(SequentialCollector sc, collection) {
  result = collectorSetup(sc_, collection)
  result.canParallel = False
  return result
}
```

## Savers

Not all Collectors are as readily parallelizable as Reducers. The `save`
collector stores its inputs into a new collection, which in most cases requires
serializing the updates to a single value. For example,

```
// Apply f to each element of a matrix, and save the results as a new matrix.
a = f(^mat) | save
```

is executed using this approach:

```
// Create a new collection with the same keys as mat, initially all Absent
a = new(keys(mat))
// (The next statement isn't valid, because it updates 'a' in a parallel loop.)
for key: value in mat {
  a[key] = f(value)
}
```

Note that each execution of the loop body updates `a`. We could easily make this
work by forcing the loop to be sequential, but we'd like to preserve the
performance potential of parallel and out-of-order execution. We could have an
`emptyState()` method that created a separate new collection for each parallel
thread, but that would likely waste memory (and CPU to merge them in
`combineStates()`).

The function `saverSetup()` (usually called from `collectionSetup()`) is
designed to make it easy to implement such Collectors:

```
// Return a struct suitable for returning from collectorSetup().
// The result will have canParallel True, but loop need not be parallelizable.
function saverSetup(eKind, loop, initialState)
```

The loop returned by `saverSetup()` maintains a single "primary" state (updated
using the given loop).

*   When multiple threads are created on a single machine, it uses locks to
    ensure that updates to the primary state are serialized.
*   When distributed state is necessary, all states other than the primary state
    are just a list of pending updates to be eventually applied to the primary
    state.

This makes it easy to implement `save`:

```
private compound SaveWithDefault is Collector, Loop
private singleton SaveLoop

function saveWithDefault(v) = SaveWithDefault_(v)
function save() = SaveWithDefault_(Absent)

method collectorSetup(SaveWithDefault swd, collection) =
    saverSetup(EnumerateWithKeys, SaveLoop, new(keys(collection), swd_))

method nextState(SaveLoop, state, [key, value]) = replaceElement(state, key, value)
```

The synchronization behavior of `saverSetup()` cannot be expressed in
Retrospect, but this simplified implementation captures the way distributed
state is handled:

```
function saverSetup(eKind, Loop loop, initialState) {
  assert initialState is not SaverUpdates
  return { eKind, loop: SaverLoop_(loop), initialState, canParallel: True }
}

private compound SaverLoop is Loop
// When state is a SaverUpdates, it wraps an array of updates to be applied
// to the primary state.
private compound SaverUpdates

method nextState(SaverLoop loop, state, element) {
  if state is SaverUpdates {
    // We don't have the primary state, so just save this element for now.
    state_ &= [element]
    return state
  }
  // We have the primary state, so we can update it.
  return nextState(loop_, state, element)
}

// A new empty state is just an empty array of updates.
method emptyState(SaverLoop) = SaverUpdates_([])

method combineStates(SaverLoop loop, state1, state2) {
  // We have to
  if state1 is SaverUpdates {
    if state2 is SaverUpdates {
      // No primary state, so all we can do is concatenate the updates.
      state1_ &= state2_
      return state1
    }
    // Swap them so that we're always merging state2 into state1.
    [state1, state2] = [state2, state1]
  } else {
    // They can't both be primary.
    assert state2 is SaverUpdates
  }
  // state1 is primary, state2 is SaverUpdates
  for element in state2_ sequential state1 {
    state1 = nextState(loop_, state1, element)
  }
  return state1
}

method finalState(SaverLoop loop, state) {
  assert state is not SaverUpdates
  return finalState(loop_, state)
}
```

## Flattening Collections

It is often convenient to be able to treat a Collection of Collections as single
Collection containing all the elements of the nested collections, with keys that
combine the keys of the outer and inner collections. An example, using an Array
of structs:

```
a = flatten([{a: "A", b: "B"}, {}, {c: "C"}])
b = withKeys(a) | saveSequential
```

returns an Array containing these (key, value) pairs:

```
[ [[[1], "a"], "A"],
  [[[1], "b"], "B"],
  [[[3], "c"], "C"]
]
```

(Why so much nesting? Each element of the result is a `[key, value]`; each key
(of the flattened collection) is `[k0, k1]` where `k0` is a key of the outer
collection (the Array of structs) and `k1` is a key of the struct; and since an
Array is a one-dimensional Matrix, it has keys like `[1]`, `[2]`, etc.)

For reference, the implementation of `flatten()` looks something like this:

```
function flatten(collections) {
  return Flattened_(collections)
}

private compound Flattened_

method at(Flattened f, key) {
  [k0, k1] = key
  c = f_ @ k0
  return c is Absent ? Absent : c @ k1
}

method size(Flattened f) = f | -> size(#) | sum

// To get the keys of a Flattened, get the keys of each subcollection and
// pair them with the subcollection's key.  Use save so that we call keys()
// on each subcollection and save the results now, rather than doing it lazily;
// that ensures that the result of keys() won't keep all the values around.
method keys(Flattened f) =
    flatten(withAllKeys(f_)
              | [k0, c] -> (c is Absent) ? Absent : [k0, ^keys(c)]
              | save)

method new(Flattened f) = flatten(f_ | -> new(keys(#)) | save)

// A Loop that iterates over each inner collection
compound FlattenLoop

method iterator(Flattened f, eKind, loop=, initialState=) {
  loop = FlattenLoop_({eKind, loop})
  return iterator(f_, eKind, loop=, initialState=)
}

method nextState(FlattenLoop flatten, state, value) {
  {eKind, loop} = flatten_
  if eKind is not EnumerateValues {
    [k1, value] = value
    if value is Absent {
      return nextState(loop, state, [[k1, Absent], Absent])
    }
    loop = transformLoop([k2, v] -> [[k1, k2], v], loop)
  }
  return iterate(value, eKind, loop, state)
}

// You can update a flattened collection if both the inner and outer collections
// can be updated.
method replaceElement(Flattened f, [k0, k1], v) {
  f_ @ k0 @ k1 = v
  return f
}

method startUpdate(Flattened f=, [k0, k1]) {
  c = startUpdate(f=, k0)
  result = startUpdate(c=, k1)
  f = -> f @ (c @ #)
  return result
}
```

## Compiling `for` loops

While pipelines stream one collection into a single Collector, `for` loops may
stream values into multiple Collectors while enumerating a single collection.
This section will outline the set of helper functions used to do that.

A prototypical `for` loop looks something like this:

`for` [ *key* `:`] *value* `in` *source* [ `sequential` *q1*, *q2* ] `{` \
&nbsp; &nbsp; . . . // references to *z1*, *z2* from enclosing scope \
&nbsp; &nbsp; `if` . . . `{` \
&nbsp; &nbsp; &nbsp; &nbsp; `break {` . . . `}` \
&nbsp; &nbsp; `}` \
&nbsp; &nbsp; . . . \
&nbsp; &nbsp; *out1* `<<` *x1* \
&nbsp; &nbsp; *out2* `<<^` *x2* \
&nbsp; &nbsp; . . . \
`} collect {` \
&nbsp; &nbsp; *out1* `=|` *collector1* \
&nbsp; &nbsp; *out2* `=|` *collector2* \
`}`

To implement this the compiler defines a few new compound types (one for the
loop itself, one for its state variables, and one for each `break` statement),
and methods on the new loop type:

*   for sequential loops over a collection it defines `nextState` and passes a
    loop instance to `iterate`;
*   for parallel loops it defines `nextState`, `emptyState`, `splitState`,
    `combineStates`, and passes a loop instance to `enumerate`.

(Sequential loops without a collection are similar, but define `at` and pass the
loop instance to `iterateUnbounded`.)

For this discussion I will refer to the new types as `Loop0`, `LoopState0`, and
`LoopExit0`.

For the parallel case, LoopState0 values will have two elements: `out1_rw` and
`out2_rw` (i.e. one element for each collected output).

For the sequential case, LoopState0 values will have two additional elements:
`q1` and `q2` (i.e. one element for each variable listed after the `sequential`
keyword), for a total of four elements.

In this example Loop0 values will have five elements: `eKind`, `out1_ro`,
`out2_ro`, `z1`, and `z2` (i.e. one element for the selected EnumerationKind,
one element for each collected output, and one element for each closed-over
variable from the enclosing scope).

(Note that each collected output has an "rw" (read/write) component in the
state, and an "ro" (read-only) component in the Loop).

The code generated for the sequential version of this loop looks like

```
  // Change next line to EnumerateWithKeys if loop uses "key:"
  maxEKind = EnumerateValues
  {ro: out1_ro, rw: out1_rw} = loopHelper(collector1, source, maxEKind=, False)
  {ro: out2_ro, rw: out2_rw} = loopHelper(collector2, source, maxEKind=, False)
  loop = Loop0_({eKind: maxEKind, out1_ro, out2_ro, z1, z2})
  state = LoopState0_({out1_rw, out2_rw, q1, q2})
  state = iterate(source, maxEKind, loop, state)
  if state is LoopExit0 {
    // compiled code for "break" body
  } else {
    {out1_rw, out2_rw, q1, q2} = state_
    out1 = finalStateHelper(out1_ro, out1_rw)
    out2 = finalStateHelper(out2_ro, out2_rw)
  }
```

along with this method definition:

```
method nextState(Loop0 loop, LoopState0 state, value) {
  {eKind, out1_ro, out2_ro, z1, z2} = loop_
  {out1_rw, out2_rw, q1, q2} = state_
  key = Absent
  if eKind is not EnumerateValues {
    [key, value] = value
    // value can only be Absent if eKind was upgraded to EnumerateAllKeys;
    // in that case we don't execute the loop body, just the emitKeys.
    if value is Absent { goto continue }
  }
  // ... insert loop body here, with:
  // - continue statement replaced with branch to "continue" label below
  // - emit statement "out1 << value" replaced with
  //     emitValue(out1_ro, out1_rw=, value)
  // - distributed emit statement "out2 <<^ values" replaced with
  //     emitAll(out2_ro, out2_rw=, values)
  // - break statement replaced with
  //     return LoopExit0_(... any local vars needed for break body...)
continue:
  emitKey(out1_ro, out1_rw=, key)
  emitKey(out2_ro, out2_rw=, key)
  return LoopState0_({out1_rw, out2_rw, q1, q2})
}
```

These code templates reference several helper functions from the core library:

```
private compound RO
private compound RW

private function rw(pendingValue, state) = RW_({pendingValue, state})

private function toState(RW rw) {
  {pendingValue, state} = rw_
  assert pendingValue is Absent
  return state
}

function loopHelper(collector, collection, maxEKind=, isParallel) {
  { eKind, loop, initialState, canParallel } = collectorSetup(collector, collection)
  assert canParallel or not isParallel, "Can't use sequential collector in parallel loop"
  if eKind is not EnumerateValues and maxEKind is not EnumerateAllKeys {
    maxEKind = eKind
  }
  return {ro: RO_({eKind, loop, canParallel}), rw: rw(Absent, initialState)}
}

// For use in unbounded loops (with no source collection)
function loopHelper(collector) {
  { eKind, loop, initialState, canParallel } = collectorSetup(collector, Absent)
  assert eKind is EnumerateValues
  return {ro: RO_({eKind, loop, canParallel}), rw: rw(Absent, initialState)}
}

// For use when multiple collected vars use the same collector
function anotherRw(collector, collection) {
  return rw(Absent, collectorSetup(collector, collection).initialState)
}

// For use when no collector is specified, usually an inner loop inheriting a
// collected var from an outer loop.  The collector for the outer loop must not
// be keyed.
procedure verifyEV(ro) {
  assert ro_.eKind is EnumerateValues, "Can't inherit a keyed collector"
}

function finalStateHelper(ro, rw) {
  return finalState(ro_.loop, toState(rw))
}

// For use in a break; combines emitKey and finalStateHelper
function finalStateHelper(ro, rw, key) {
  {pendingValue, state} = rw_
  if ro_.eKind is not EnumerateValues {
    if state is not LoopExit and (pendingValue is not Absent or ro_.eKind is EnumerateAllKeys) {
      state = nextState(ro_.loop, state, [key, pendingValue])
    }
  }
  return finalState(ro_.loop, state)
}

procedure emitValue(ro, rw=, v) {
  if v is Absent {
    return
  }
  {pendingValue, state} = rw_
  assert pendingValue is Absent, "Can't emit twice to keyed collector"
  if state is not LoopExit {
    if if ro_.eKind is EnumerateValues {
      rw = rw(Absent, nextState(ro_.loop, state, v))
    } else {
      rw = rw(v, state)
    }
  }
}

procedure emitAll(ro, rw=, collection) {
  assert ro_.eKind is EnumerateValues, "Can't emitAll to keyed collector"
  state = toState(rw)
  if state is not LoopExit {
    if ro_.canParallel {
      state = enumerate(collection, EnumerateValues, ro_.loop, state)
    } else {
      state = iterate(collection, EnumerateValues, ro_.loop, state)
    }
    rw = rw(Absent, state)
  }
}

procedure emitKey(ro, rw=, key) {
  if ro_.eKind is EnumerateValues {
    return
  }
  {pendingValue, state} = rw_
  if state is not LoopExit and (pendingValue is not Absent or ro_.eKind is EnumerateAllKeys) {
    state = nextState(ro_.loop, state, [key, pendingValue])
    rw = rw(Absent, state)
  }
}
```

The code generated for the parallel version is similar:

```
  // Change next line to EnumerateWithKeys if loop uses "key:"
  maxEKind = EnumerateValues
  {ro: out1_ro, rw: out1_rw} = loopHelper(collector1, source, maxEKind=, True)
  {ro: out2_ro, rw: out2_rw} = loopHelper(collector2, source, maxEKind=, True)
  loop = Loop0_({eKind: maxEKind, out1_ro, out2_ro, z1, z2})
  state = LoopState0_({out1_rw, out2_rw})
  state = enumerate(source, maxEKind, loop, state)
  if state is LoopExit0 {
    // compiled code for "break" body
  } else {
    {out1_rw, out2_rw} = state_
    out1 = finalStateHelper(out1_ro, out1_rw)
    out2 = finalStateHelper(out2_ro, out2_rw)
  }
```

(The only differences, aside from the absence of sequential vars `q1` and `q2`,
are that the `isParallel` argument to `loopHelper` is True and the call to
`enumerate` instead of `iterate`.)

The definition of the `nextState` method on `Loop0` is unchanged, but the
compiler also defines methods for `emptyState`, `splitState`, and
`combineStates`:

```
method emptyState(Loop0 loop) {
  out1_rw = emptyStateHelper(loop_.out1_ro)
  out2_rw = emptyStateHelper(loop_.out2_ro)
  return LoopState0_({out1_rw, out2_rw})
}

method splitState(Loop0 loop, LoopState0 state=) {
  out1_rw = splitStateHelper(loop_.out1_ro, state_.out1_rw=)
  out2_rw = splitStateHelper(loop_.out2_ro, state_.out2_rw=)
  return LoopState0_({out1_rw, out2_rw})
}

method combineStates(Loop0 loop, LoopState0 state1, LoopState0 state2) {
  out1_rw = combineStatesHelper(loop_.out1_ro, state1_.out1_rw, state2_.out1_rw)
  out2_rw = combineStatesHelper(loop_.out2_ro, state1_.out2_rw, state2_.out2_rw)
  return LoopState0_({out1_rw, out2_rw})
}
```

These use some additional helper functions:

```
function emptyStateHelper(ro) = rw(Absent, emptyState(ro_.loop))

function splitStateHelper(ro, rw=) {
  state = toState(rw)
  if state is LoopExit {
    // We can't call splitState with a LoopExit, so just create another
    // LoopExit that we'll discard when it gets back to combineStatesHelper.
    return loopExit(Absent)
  }
  state2 = splitState(ro_.loop, state=)
  rw = rw(Absent, state)
  return rw(Absent, state2)
}

function combineStatesHelper(ro, rw1, rw2) {
  state1 = toState(rw1)
  state2 = toState(rw2)
  if state1 is LoopExit {
    if state2 is LoopExit and loopExitState(state1) is Absent {
      return rw2
    }
    return rw1
  } else if state2 is Absent {
    return rw1
  } else if state1 is Absent or state2 is LoopExit {
    return rw2
  }
  return rw(Absent, combineStates(ro_.loop, state1, state2))
}
```
