# Generating Java bytecodes for Retrospect, Appendix

## The methods that are called

Each of the function calls in [this example](codegen_3.md#the-vm-instructions)
(to `pipe`, `sum`, `sequentially`, `sqrt`, and `exponent`) is resolved to a
built-in method, which may in turn call other functions. This is a quick tour
through the methods that implement the first four calls (`pipe`, `sum`,
`sequentially`, and `pipe` again); `sqrt` and `exponent` are just the obvious
numeric methods.

There are multiple built-in methods for `pipe`; the first call will be handled
by the default method that matches `pipe(Collection, Lambda)` (i.e. the first
argument is a Collection, and the second argument is a Lambda). This method is
implemented by some Java code, but its behavior is documented by the equivalent
Retrospect source code:

```
method pipe(Collection left, Lambda right) default {
  if left is Matrix {
    return TransformedMatrix_({base: left, lambda: right})
  } else {
    return TransformedCollection_({base: left, lambda: right})
  }
}
```

All this method does is wrap the two arguments in a compound type, either
`TransformedMatrix` or `TransformedCollection`, depending on whether the first
argument is a `Matrix`. Both types lazily apply the lambda when elements of the
transformed collection are accessed; the only difference between them is that
`TransformedMatrix` is a subtype of `Matrix` and implements an additional method
for returning the matrix dimensions (by forwarding it to the wrapped matrix).
Since our collection is an array, and Array is a subtype of Matrix, this method
will return a TransformedMatrix.

The `sum` and `sequentially` methods are even simpler: `sum` just returns a
constant (the `Sum` compound type with a `0` as its zero value), and
`sequentially` just wraps its argument in another compound type:

```
method sum() = Sum_(0)
method sequentially(Collector collector) = SequentialCollector_(collector)
```

(Again, these are implemented as Java built-in methods, but the equivalent
Retrospect source code is the easiest way to document their behavior.)

The second call to `pipe` uses a different method than the first one, since its
second argument is a `Collector` rather than a `Lambda`:

```
method pipe(Collection collection, Collector collector) {
  { canParallel, eKind, initialState, loop } = collectorSetup(collector, collection)
  if canParallel {
    state = enumerate(collection, eKind, loop, initialState)
  } else {
    state = iterate(collection, eKind, loop, initialState)
  }
  // The pipe result is the final state, unwrapped if it's a LoopExit
  return loopExitState(state)
}
```

This method in turn calls `collectorSetup` to get the arguments it will pass to `enumerate`
or `iterate`; since `Sum` is a subtype of `Reducer`, the relevant methods are:

```
method collectorSetup(SequentialCollector sc, collection) {
  {canParallel, eKind, initialState, loop} = collectorSetup(sc_, collection)
  assert canParallel is Boolean and eKind is EnumerationKind
  return {canParallel: False, eKind, initialState, loop}
}

method collectorSetup(Reducer reducer, _) = {
    canParallel: True,
    eKind: EnumerateValues,
    initialState: emptyState(reducer),
    loop: reducer
  }

method emptyState(Sum reducer) = reducer_
```

Putting those together, `collectorSetup` on `sequentially(sum())` will return

```
{canParallel: False, eKind: EnumerateValues, initialState: 0, loop: Sum_(0)}
```

... which will set us up for the call to `iterate` that actually does all the
work.

(Yes, there is quite a bit of complexity there to ensure that the framework is
general enough to support the full range of "reduce a collection" computations.
One of the goals of bytecode generation is to ensure that these layers of
abstraction generally incur no measurable run-time cost.)
