# Retrospect Core Module Reference

[TOC]

## Overview

All Retrospect programs can refer to types and functions declared in an
automatically-imported module named `Core`. Usually these are referred to
without module qualification (e.g. as `min()` rather than `Core.min()`), but
qualification is needed if the program declares its own types or methods with
the same name, or imports another module with conflicting declarations.

## Core Types

### Number and Integer

The values of type `Number` correspond to the
[IEEE 754 binary64](https://en.wikipedia.org/wiki/Double-precision_floating-point_format)
standard (aka Java `double`), including positive and negative infinities but not
NaNs (which are, after all, not numbers) or negative zero.

The subset of `Number` values that can be represented by a 32-bit signed integer
is referred to as `Integer`. There are some restrictions on the use of `Integer`
that are detailed elsewhere; for example, a union type may not contain `Integer`
without containing all of `Number`.

### True, False, None, and Absent

These are singletons, so may be used as both types and as values.

`True` and `False` are returned by comparison functions; `if` statements and the
logical operators (`not`, `and`, `or`) will error if given a value that is not
one of `True` or `False`. `Boolean` is a (closed) union type that contains only
`True` and `False`.

`None` is used by many core functions to represent an argument that is not
provided (e.g. to create a range with no upper bound) or when there is no value
to return (e.g. the index of the first occurrence of an element that does not
appear in an array). `None` is also returned by numeric functions when there is
no numerically valid result, such as `log(-1)` or `0/0` (i.e. any time the IEEE
standard specifies that a NaN should be returned).

`Absent` is returned by the core `at(collection, key)` function when given a
valid key that has no corresponding value. Keys without a corresponding value
are omitted when enumerating the collection.

Collections that allow replacing elements generally also allow removing them by
replacing them with `Absent`, e.g. after

```go
a = ["a", "b", "c", "d"]
a[1] = Absent
```

enumerating the elements of `a` will return `"a"`, `"c"`, and `"d"` (with keys
0, 2, and 3). If instead `a[1]` were replaced with `None`, enumeration would
return `"a"`, `None`, `"c"`, and `"d"`.

### Lambda

Lambda is an open union type, i.e. new types may be added as subtypes of it.
Subtypes of Lambda are expected to implement `at(x, y)` (i.e. to define a method
for `at(T x, y)` where T is the subtype of Lambda) . Lambda syntax (e.g. `x ->
x+1`) returns values of type Lambda, and many other types are subtypes of
Lambda, including Collection.

### Collection

Collection is another open union type, and a subtype of Lambda. Subtypes of
Collection are expected to implement one or more of the enumeration functions,
so that they can be used as the target of a `for` loop or the source of a
pipeline. The `[key, value]` pairs returned by enumeration are expected to be
consistent with `at()`, i.e. `collection @ key` returns `value`.

Collections are also expected to implement `keys()` and (where sensible)
`size()`.

Collections may also support replacing some or all elements by implementing
`replaceElement(collection, key, value)` and `startUpdate(collection, key)`, as
described below.

### Matrix

Matrix is an open union type, and subtype of Collection. The keys of a Matrix
are Arrays of integers, all the same length (the dimensionality of the Matrix).
Matrices with one dimension are referred to as "vectors", but there isn't a
corresponding Vector type.

The `sizes(matrix)` function returns a vector of non-negative integers, and
`size(matrix)` returns the same result as `sizes(matrix) | product`.
Sequentially enumerating a Matrix returns the elements in row-major order, e.g.
if `x` is a Matrix with sizes `[4, 3, 2]`, sequential enumeration will return
`x[1, 1, 1]`, `x[1, 1, 2]`, `x[1, 2, 1]`, ..., `x[4, 3, 2]`.

`at(matrix, key)` is also defined when key is an array of the correct length
containing one or more Ranges instead of Integers; the result is a submatrix of
`matrix`, with as many dimensions as there were Ranges. For example, if `x` is
Matrix with sizes `[4, 3, 2]` then `x[2.., 2, 1..1]` is a Matrix with sizes `[3,
1]`.

Some Matrix values can be updated, by replacing a single element or a submatrix.

### Range

A Range is a pair of values (the lower bound and the upper bound), each of which
is an Integer or `None` (indicating no bound). The bounds of a Range are
returned by `min(range)` and `max(range)`. If either bound is `None` the size is
undefined; if both are Integers the size is max+1-min, which must be
non-negative.

Ranges with both upper and lower bounds are vectors containing the integers
between the lower bound and upper bound (inclusive) in ascending order. For
example, after

```go
r = -5..5
```

`r[2]` returns `-4` and `sizes(r)` returns `[11]`.

Ranges with only a lower bound are collections but not matrices, and may only be
enumerated sequentially. Ranges without a lower bound are not Collections and
may not be enumerated or indexed.

Ranges may not be updated.

### Array

Arrays are one-dimensional matrices (Array is a subtype of Matrix). They have
the expected performance characteristics (constant access time and linear
storage cost).

Arrays can be updated by replacing any element or range of elements (replacing a
single element leaves the size unchanged; replacing a range may change the
size).

### String and StringRange

A String is a sequence of Unicode code points. A StringRange identifies a
contiguous subset of the characters in a String. Strings can be treated as a
collection indexed by StringRange; iteration returns individual characters in
the order they appear in the string.

Strings can be updated by replacing the characters identified by a StringRange
with another String. They can also be converted to or from an Array of integers
in the range 0 to 255, corresponding to the UTF-8 encoding of the String.

### Struct

Structs are a type of Collection with String keys.

Structs can be updated by replacing individual elements, but elements may not be
added or removed.

### Reducer and Saver

Reducer and Saver are open union types, providing alternative approaches to
implementing a *collector*, i.e. a value that can be used to terminate a
pipeline or to combine the values emitted by a `for` loop. The details of their
APIs are covered in a separate doc.

### U32

U32 ("unisgned 32-bit") values correspond to integers in the range 0 to
$$2^{32}-1$$. These values are not Numbers, and a different set of functions are
available for them:

*   bitwise operations (such as bitAnd, bitOr, and bitCount) that treat them as
    a sequence of 32 values, each 0 or 1; and
*   arithmetic operations (such as uAdd and uMultiply) that represent their
    results as a pair of U32s (since they may be outside the domain of a single
    U32).

### FutureValue

FutureValues provide a simple mechanism for explicitly enabling parallel
computation. Most programs are not expected to need FutureValues --
parallelizable loops usually provide sufficient parallelism.

## Core Functions

### Arithmetic

`add(x, y)`, `subtract(x, y)`, `multiply(x, y)`, `divide(x, y)`, `modulo(x, y)`,
and `exponent(x, y)` (usually written using the binary operators `+`, `-`. `*`,
`/`, `%`. and `**`) are open functions (i.e. methods can be added for new types)
that implement the usual mathematical operations when applied to two numbers.

`modulo(x, y)` is defined for all finite non-zero numbers `y`, returning a
number between zero (inclusive) and `y` (exclusive) such that `x - modulo(x, y)`
is an integer multiple of `y`. Thus `modulo(1.8, 0.5)` returns `0.3`, and
`modulo(1.8, -0.5)` returns `-0.2`.

`div(x, y)` returns the same value as `floor(x/y)`; note that `div(x, y) * y +
mod(x, abs(y)) == x`.

`negative(x)` (usually written `-x`), `floor(x)`, and `ceiling(x)` are the usual
mathematical functions.

When applied to a number and a matrix, or to two matrices, these operators are
implicitly distributed, e.g. if `n` is a number and `m1` and `m2` are matrices:

```go
a1 = n + m1;  a2 = m2 + n;  a3 = m1 + m2

// ... is equivalent to:
a1 = n +^ m1;  a2 = m2 ^+ n;  a3 = m1 ^+^ m2

// ... which is in turn equivalent to:
a1 = m1 | -> n + #
a2 = m2 | -> # + n
a3 = join(m1, m2) | [e1, e2] -> e1+e2
```

(and similarly for the other functions).

Note that the result of a distributed operation is lazy, i.e. the operation is
applied when each element is accessed. You can use `save` to compute all
elements and store the result as a new matrix, e.g. if `a` and `b` are matrices
with the same dimensions, then

```go
x = 2 * a + 3 * b - 1 | save
```

will create a new stored matrix with the same dimensions.

### `equal(x, y)` and `lessThan(x, y)`

Like the arithmetic operators, these are open functions and usually written
using an operator (`==` or `!=` for `equal()`, `<`, `<=`, `>=`, or `>` for
`lessThan()`).

They have the expected behavior when applied to Numbers, U32s, or Strings
(Strings are compared as a sequence of code points, without consideration for
canonical equivalence or language-specific collation rules).

Unlike the arithmetic operators, comparisons are not implicitly distributed over
matrices: `3 == [1, 2, 3]` is `False` (but you can use `3 ==^ [1, 2, 3]` if you
wanted `[False, False, True]`).

`equal()` has a default method defined for all pairs of values:

-   If either argument is a Number or U32, returns True if the other argument is
    a Number or U32 with the same numeric value.
-   If either argument is a singleton, returns True if the other argument is the
    same singleton.
-   If either argument is a String, returns True if the other argument is a
    String containing the same sequence of characters.
-   If either argument is a Matrix, returns True if the other argument is a
    Matrix with the same `sizes` and the corresponding elements are `equal`.
-   If either argument is a Range, returns True if the other argument is a Range
    with the same lower and upper bound.
-   If either argument is a StringRange, returns True if the other argument is a
    StringRange with the same lower and upper bound.
-   If either argument is a Struct, returns True if the other argument is a
    Struct with the same keys and the corresponding elements are `equal`.
-   If either argument is an instance of a compound type, returns True if the
    other argument is an instance of the same type and their underlying
    representations are `equal`.

If none of those rules are applicable the default method returns False.

### `min(x, y)` and `max(x, y)`

These functions have default methods based on `lessThan()`

```go
method min(x, y) default = (x <= y) ? x : y
method max(x, y) default = (x >= y) ? x : y
```

### `asInt(number)`

`asInt` is an open function that is intended to return an Integer with the same
value as its argument. The default method errors if given anything that isn't
already an Integer.

### `at(x, y)`, `replaceElement(collection, key, value)`, and `startUpdate(collection=, key)`

`at(x, y)` is used both for applying a lambda (`lambda @ arg`) and for getting
one element from a collection (`collection @ key`). It may return `Absent` to
indicate that there is no element with that key.

Updates to collections are implemented using `replaceElement(collection, key,
value)` and/or `startUpdate(collection=, key)`; collections that support updates
must implement both.

`replaceElement(collection, key, value)` is straightforward; the result should
be just like `collection` except that `newCollection @ key` returns `value`.

`startUpdate()` is used when the new value for a key is derived from the
previous value for that key. A call to `startUpdate(collection=, key)` returns
two results:

*   the previous value (as would have been returned by a call to `collection @
    key`); and
*   an *updater* lambda, which when applied to a new value for `key` returns the
    updated collection.

The updater is returned as the new value of the inout `collection` parameter.

For example,

```go
aa[i] = b
```

is implemented by

```go
aa = replaceElement(aa, [i], b)
```

A more complex example:

```go
aa[i].x = b
```

is implemented by combining `startUpdate` and `replaceElement`:

```go
// This call also replaces aa with an updater for a[i]...
temp_aa_i = startUpdate(aa=, i)
// ... then we make the desired change to the extracted a[i] ...
temp_aa_i = replaceElement(temp_aa_i, "x", b)
// ... and then we use the updater to get the final value of aa
aa = aa @ temp_aa_i
```

Longer sequences of indexing operations are handled by just nesting that
pattern, e.g.

```go
aa.m[i,j].x = b
```

is implemented by

```go
_temp_aa_m = startUpdate(aa=, "m")
_temp_aa_m_ij = startUpdate(_temp_aa_m=, [i, j])
_temp_aa_m_ij = replaceElement(_temp_aa_m_ij, "x", b)
_temp_aa_m = _temp_aa_m @ _temp_aa_m_ij
aa = aa @ _temp_aa_m
```

`startUpdate` is also used to implement indexed inout arguments, e.g.

```go
u = pop(aa.stack=)
```

is implemented by

```go
_temp_aa_stack = startUpdate(aa=, "stack")
u = pop(_temp_aa_stack=)
aa = aa @ _temp_aa_stack
```

### `filter(lambda)` and `applyToValue(lambda)`

These are simple functions for constructing pipelines.

`filter()` turns a boolean-returning test into a lambda that either returns its
argument or `Absent` if that argument fails the test.

For example

```go
c = a | filter(-> # > 0) | sum
```

sums the elements of `a` that are greater than zero.

`applyToValue()` turns a lambda that transforms values to a lambda that
transforms `[key, value]` pairs, leaving the key unchanged. For example,
`applyToValue(v -> v+1)` adds one to each value in a stream of `[key, value]`
pairs, while `applyToValue(filter(v -> v > 0))` and `filter(applyToValue(v ->
v > 0))` are both equivalent to `filter([k, v] -> v > 0)`, i.e. they drop pairs
whose value is negative.

```go
function filter(Lambda lambda) {
  return v -> (lambda @ v) ? v : Absent
}

function applyToValue(Lambda lambda) {
  return ApplyToValue_(lambda)
}

private compound ApplyToValue is Lambda
method at(ApplyToValue atv, [k, v]) {
  v = atv_ @ v
  return v is Absent ? Absent : [k, v]
}
```

### `keys(collection)` and `withKeys(collection)`

`keys(collection)` returns a Collection with the same keys as the given
collection, but where each key's value is the key itself. For example, `keys([3,
4, 5])` returns `[[1], [2], [3]]`, while `keys({a: 3, b: 4, c: 5})` returns `{a:
"a", b: "b", c: "c"}`.

Depending on the collection type, the result of `keys()` may include some keys
for which the source collection had no corresponding value. For example, given

```go
a = 1..1000 | filter(-> (# % 7) != 0)
```

enumerating `a` will only return integers not divisible by 7, but `keys(a)` will
include all the keys from `[1]` to `[1000]`.

`withKeys(collection)` returns a Collection with the same keys as the given
collection, but where each key's value is a pair of the key and its value in the
source collection. For example if `a` is an array,

```go
b = withKeys(a) | filter([k, v]-> v % 7 == 0) | [[i], _] -> i | saveSequential
```

will set `b` to an array containing all the indices at which the value of `a` is
divisible by 7.

### `size(collection)` and `contains(collection, x)`

`size(collection)` returns the size of a collection. Like `keys()`, for some
collections this may be greater than the number of elements returned by
enumeration. For example, given the earlier example

```go
a = 0..1000 | filter(-> (# % 7) != 0)
```

`size(a)` returns 1001, while `a | count` returns 858.

`contains(collection, x)` returns True if `x` is the value for some key in
`collection`. The default implementation just compares each value in the
collection with key, but some collections (such as Range) provide a more
efficient implementation.

### `sizes(matrix)`, `matrix(sizes)` and `matrix(sizes, matrix)`

`sizes(matrix)` returns a vector of non-negative integers, identifying the space
of valid keys for the matrix.

`matrix(sizes, matrix)` is similar to the `reshape` function in NumPy or MATLAB;
it returns a matrix with the same elements (in the same order) as `matrix`, but
with its shape determined by `sizes`. If all elements of `sizes` are
non-negative their product must be equal to the size of `matrix`. If one element
of `sizes` is negative, all other elements must be positive and their product
must divide evenly into the size of `matrix`.

`matrix(sizes)` returns a Matrix with the given sizes, where each element's
value is the same as its key; for example, if `x` is `matrix([3, 4])`

*   `sizes(x)` is `[3, 4]`, and
*   `x[i, j]` is `[i, j]` for any `i` in `1..3` and `j` in `1..4`.

### `new(collection)`, `new(collection, initialValue)`, `newMatrix(sizes)`, and `newMatrix(sizes, initialValue)`

`new` returns a new collection with the same keys as a given collection, but all
having the same value (`Absent` if no initial value is given). The collections
returned by `new` support replacing elements within the original set of keys, so
for example after

```go
a = new(1..4, -1)
a[2] = 10
```

`a` is an array with size 4 and value `[-1, 10, -1, -1]`.

`newMatrix(sizes)` is equivalent to `new(matrix(sizes))`, e.g.

```go
a = newMatrix([2, 1])
a[1, 1] = 10
a[2, 1] = 20
```

### Enumerating collections

These functions are the API for sequential enumeration of collections; see the
enumeration API doc for details:

*   `iterator(collection, eKind)` and `next(iterator=)`
*   `emptyIterator()` and `oneElementIterator(element)`
*   `iterateOne(loop, element, state)`
*   `iterateAll(loop, collection, eKind, initialState)`

These functions are the API for parallelizable enumeration collections; see the
enumeration API doc for details:

*   `initialDState(loop)` and `combineDStates(loop, dState1, dState2)`
*   `updateUState(loop, update, uState)`
*   `enumerateOne(loop, element, dState=)`
*   `enumerateAll(loop, collection, eKind, uState=)`
*   `enumerateAll(loop, collection, eKind)`

Both forms of enumeration use `LoopExit` values to indicate an early exit, via
the functions `loopExit(finalState)` and `loopExitState(exit)`.

### `reverse(vector)` and `reverse(matrix, dimension)`

The result of `reverse(v)` is a vector with the same size as `v` and its
elements in the reverse order. It is an error if `v` does not have exactly one
dimension.

The result of `reverse(mat, dim)` is a Matrix with the same sizes as `mat` and
its elements reversed along the given dimension. It is an error if `dim` is not
in `1..size(sizes(mat))`.

### `flatten(collections)`

A collection of collections can be treated as a flattened collection with keys
that are two-element arrays. For example, if

```go
a = flatten({x: [3, 4, 5], y: ["a", "b"], z: []})
```

Then `size(a)` returns 5, `a["y", [2]]` returns `"b"`, and

```go
a | filter(-> # is String) | count
```

returns 2. Also, since both the inner and outer collections support replacing
their elements,

```go
a["y", [2]] = "c"
```

is supported.

### The Reducer and Saver APIs

The Reducer API consists of four functions:

*   `initialState(reducer)`
*   `nextState(reducer, state, element)`
*   `combineStates(reducer, state1, state2)`
*   `finalState(reducer, state)`

The Saver API consists of three functions:

*   `savedValue(saver, value)`
*   `nextState(saver, state, element)`
*   `finalState(saver, state)`

See the enumeration API doc for details.

### `sum()`, `sum(zero)` and `count()`

These are some of the simplest reducers. `sum(zero)` adds all its inputs to the
given zero value; it can be used with any inputs for which an applicable `add`
method is defined. `sum()` is equivalent to `sum(0)`. `count()` just returns the
number of inputs reduced.

(Note that when `sum(zero)` is used in a parallel reduction the zero value may
be added in more than once, e.g. `[a, b, c, d] | sum(z)` may be computed as
`(z+a+b) + (z+c+d)`.)

### `min()` and `max()`

These functions (with no arguments) return reducers; they can be used with any
inputs for which an applicable `lessThan` method is defined. If there are no
inputs to be reduced they return `Absent`.

### `min(collection)` and `max(collection)`

The default methods for these functions reduce the collection with the
corresponding reducer, but some collection types (e.g. Range) override the
default method to provide a more efficient implementation.

```go
open function min(collection)
open function max(collection)

method min(collection) default = collection | min
method max(collection) default = collection | max
```

### `minAt(key)` and `maxAt(key)`

These functions return Reducers. Like `min()` and `max()` they return one of
their inputs, but the comparison is made with only a part of their inputs
(`input@key`).

For example, `withKeys(a) | maxAt([2])` will return the `[key, value]` pair from
`a` with the greatest value (if more than one key has that value, it is not
specified which will be returned).

### `top(preferFirst)`

This function returns a generalized form of the min/max Reducer, given a lambda
that compares two elements and returns True if the first one is preferred or
False if the second one is preferred.

For example, `nums | top([x, y] -> abs(x) < abs(y))` will return the element of
`numbers` with the minimum absolute value.

### `anyTrue()`, `anyFalse()`, `allTrue()`, `allFalse()`

These functions return Reducers that expect Boolean inputs and return a Boolean
output.

### `saveUnordered()` and `saveSequential()`

These collectors return Arrays containing the collected values. For example,

```go
primes = 1..1000 | filter(-> isPrime(#)) | saveUnordered
```

sets `primes` to an Array of length 168 containing the integers in `1..1000` for
which `isPrime` returns True. As the name suggests, the order of the Array's
elements is not guaranteed (giving the implementation the flexibility to easily
parallelize the enumeration and testing). Using `saveSequential` instead would
force the pipeline to enumerate and process the base collection sequentially,
ensuring that the results appear in the same order.

### `save()`

Like `saveUnordered` and `saveSequential`, the `save` collector returns a
collection containing the collected values. Unlike those collectors, which
always return an Array, `save` returns a collection with the same keys as the
base collection, where each value is stored with its original key. For example,

```go
primes = 1..1000 | filter(-> isPrime(#)) | save
```

would return an Array of length 1000 such that e.g. `primes[11]` is 11, but
`primes[12]` is Absent.

The type of value returned by `save` depends on the type of the collection being
enumerated, e.g.

*   `save` applied to the elements of a 1-dimensional Matrix (including an Array
    or Range) returns an Array
*   `save` applied to the elements of a multi-dimensional Matrix returns a
    reshaped Array
*   `save` applied to the elements of a Struct returns a Struct with the same
    keys

### `saveOver(collection)`

The `saveOver` collector is similar to `save` in that emitted values are stored
in the resulting collection at the same key as the element being processed, but
instead of starting with an empty result it starts with another value. Switching
the above example to use `saveOver`,

```go
primes = 1..1000 | filter(-> isPrime(#)) | saveOver(bigArray)
```

will replace the 168 elements of `bigArray` that have prime indices in 1..1000,
and will leave the remaining elements unchanged. This would be an error if
`bigArray` was not an Array of length at least 997 (or some other updatable
collection type that supported those keys).

### `sequentially(collector)`

This function makes any Reducer or Saver sequential. `saveSequential()` is just
a shortcut for `sequentially(saveUnordered)`.

`sequentially()` is also useful with the Boolean reducers (e.g.
`sequentially(anyTrue)` tests the elements of the base collection sequentially,
which may be more efficient for some uses).

Note that sequential collectors may not be used in parallel loops, e.g.

```go
for k : v in x {
  if interesting(v) {
    result << k
  }
} collect {
  result =| saveSequential
}
```

is an error: you must either make the loop sequential or use a parallel
collector such as `saveUnordered` or `save`.

### `pipe(collection, collector)`

Piping a Collection into a Reducer or Saver enumerates the collection and
returns the result of processing its elements with the collector.

### `pipe(collection, lambda)`

Piping a Collection into a Lambda returns a new Collection that lazily applies
the Lambda to each element.

Piping a Matrix returns a Matrix with the same sizes.

Piped collections do not support replacing their elements.

### `pipe(lambda, collector)`

`pipe` can also be used to compose a (non-Collection) Lambda with a collector,
returning a new collector. The lambda will be applied to each input before
passing the result to the collector, and inputs that return `Absent` from the
lambda will be discarded.

### `pipe(lambda1, lambda2)`

`pipe` can also be used to compose two lambdas, returning another lambda that
applies the given lambdas in order (skipping `lambda2` if `lambda1` returns
`Absent`).

```go
method pipe(Lambda lambda1, Lambda lambda2) default =
    ComposedLambda_([lambda1, lambda2])

private compound ComposedLambda is Lambda

method at(ComposedLambda cl, x) {
  x = cl_[1] @ x
  return x is Absent ? Absent : cl_[2] @ x
}
```

### `binaryUpdate(lhs=, lambda, rhs)`

Calls to `binaryUpdate` are usually made implicitly by using the compound
assignment operators `+=`, `-=`, `*=`, `/=`, and `|=`. The default method just
applies the lambda to the two arguments:

```go
method binaryUpdate(lhs=, lambda, rhs) default {
  lhs = lambda[lhs, rhs]
}
```

making `x += y` equivalent to `x = x + y`.

The default method is overridden when the left hand side is a matrix so that `x
+= y` behaves more like `x = x + y | save` (i.e. the element-wise addition is
done immediately, rather than constructing a matrix that does it lazily on
access). This override will error if the lhs value is not a stored matrix (i.e.
an array or a reshaped array), so e.g.

```go
// This will error
x = 1..100 | -> # ** 3
x += 1
```

will fail because `x` is a computed matrix and its elements cannot be updated.

### U32 conversions and comparisons

`number(u32)` and `u32(number)` convert values to and from U32. All U32 values
can be represented as Numbers, but calling `u32()` on a Number that does not
correspond to a U32 (such as `0.5` or `-1`) will error.

`equal()` and `lessThan()` can compare U32s with each other or with Numbers,
e.g. `u32(3) == 3` returns True, as does `u32(3) > 2`.

The standard arithmetic operators (`+`, `*`, etc.) do *not* work on U32s or on
combinations of U32s and Numbers. There are separate functions described below
specifically for arithmetic on U32s.

### Bit operations

These functions are currently only defined over U32 values, but may be extended
in the future to support bit sequences of unbounded length. They treat U32
values as a little-endian sequence of 32 bits, numbered `0` to `31`.

(Numbering from 0 gives a nice correspondence between bit *n* and $$2^n$$, but
note that this is different from arrays, which are indexed from 1.)

`bitAnd(x, y)`, `bitOr(x, y)`, `bitInvert(x)`, and `bitAndNot(x, y)` are the
usual bitwise operators, e.g.

```go
x = bitAnd(u32(19), u32(7))
```

returns `u32(3)`.

`bitCount(x)` returns the number of `1` bits in its argument, e.g.

```go
x = bitCount(u32(19))
```

returns `3`.

`bitFirstZero(x)` and `bitFirstOne(x)` return the index of the lowest-numbered
bit with that value, or `-1` if there is no such bit. For example

```go
x = bitFirstZero(u32(19))
y = bitFirstOne(u32(0))
```

returns `2` and `-1`.

`bitLastZero(x)` and `bitLastOne(x)` return the index of the highest-numbered
bit with that value, or `32` if there is no such bit. For example
`bitLastZero(u32(19))` returns `31`, and `bitLastOne(u32(19))` returns `4`.

`bitShift(u32, nBits)` returns a U32 where bit `i` of the result is bit `i -
nBits` of the argument (i.e. a positive `nBits` is a "left shift" and negative
`nBits` is a "right shift"). If `i - nBits` is not in `0..31`, the result bit is
zero. For example

```go
x = bitShift(u32(19), 2)
y = bitShift(u32(19), -2)
```

returns `u32(76)` and `u32(4)`.

`bitRotate(u32, nBits)` returns a U32 where bit `i` of the result is bit `(i -
nBits) % 32` of the argument. For example

```go
x = bitRotate(u32(19), 28)
y = bitRotate(u32(19), -4)
```

both return `u32(805306369)`.

`bitRotate({low, high}, nBits)` takes two U32s, which are interpreted as the
low-order and high-order halves of a 64 bit sequence. The 64-bit value is
rotated by the given amount, and then its low-order 32 bits are returned as a
U32 (this can be thought of as a shift of `low`, with the shifted-in bits
provided by `high`). For example,

```go
x = bitRotate({low: u32(4), high: bitInvert(u32(0))}, 2)
```

returns `19`.

### U32 arithmetic

`uAdd(x, y)` returns a struct `{low, high}` of U32s, where `low` is the
low-order 32 bits of `x+y` and `high` is the carry, either `u32(0)` or `u32(1)`.
For example,

```go
x = uAdd(bitInvert(u32(0)), u32(19))
```

returns `{low: u32(18), high: u32(1)}`

Similarly `uMultiply(x, y)` returns a struct `{low, high}` of U32s representing
the full unsigned 64-bit product of the given U32s. For example,

```go
x = uMultiply(u32(4), u32(19))
```

returns `{low: u32(76), high: u32(0)}`

`uSubtract(x, y)` returns a struct `{low, high}` of U32s, where `low` is the
low-order 32 bits of `x-y` and `high` is the borrow, either `u32(0)` or
`bitInvert(u32(0))`. For example,

```go
x = uSubtract(u32(4), u32(19))
```

returns `{low: bitInvert(u32(14)), high: bitInvert(u32(0))}`.

`uDivWithRemainder({low, high}, denominator)` takes three U32s (where first two
represent a 64-bit numerator) and returns a struct of three U32s `{q: {low,
high}, r}`, where the first two represent the 64-bit quotient and the last is
the remainder. For example,

```go
a = uDivWithRemainder({low: u32(19), high: u32(0)}, u32(5))
```

returns `{q: {low: u32(3), high: u32(0)}, r: u32(4)}`.

### FutureValues

`future(lambda)` computes `lambda[]` asynchronously; `waitFor(fv)` returns the
value computed by a previous call to `future(lambda)`. For example,

```go
function computeSum(args) {
  fv1 = future(-> expensiveFnOne(args))
  fv2 = future(-> expensiveFnTwo(args))
  return waitFor(fv1) + waitFor(fv2)
}
```

returns the same value as `expensiveFnOne(args) + expensiveFnTwo(args)`, but the
functions may be computed in parallel.

If the lambda computation errors, the call to `waitFor()` will error. If there
is no call to `waitfor` on that FutureValue the error will be ignored.

FutureValues may also be created without a lambda by calling `testFuture()`;
these FutureValues must eventually have their value set by calling
`setTestFuture()`. For example,

```go
fv1 = testFuture()
fv2 = future(-> waitFor(fv1) + 1)
setTestFuture(fv1, 41)
result = waitFor(fv2)
```

returns 42. As the names suggest, these FutureValues were created for testing
the virtual machine and are not intended for other uses. (Note that it is
possible to use `testFuture` to create circularly-linked values!)
