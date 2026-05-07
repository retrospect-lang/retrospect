# Retrospect Language Reference

<!--* css: "docs/include/lang.css" *-->

[TOC]

## Names

Programs in Retrospect refer to four distinct kinds of entities:

*   values,
*   types,
*   functions, and
*   modules.

Most values don't have fixed names; the exceptions are singletons like "None"
and "True". Values are often referred to via variables, which have names
beginning with a lowercase letter.

Types and functions are referred to by fixed names; type names begin with an
uppercase letter (e.g. `Number`), and function names begin with a lowercase
letter (e.g. `size()`). Singletons are both types and values, and their names
are capitalized like other types. Type or function names that are defined in
more than one imported module may need to be qualified with the module name
(e.g. `Core.iterator()`).

Modules don't have fixed names, but a name beginning with an uppercase letter is
assigned to the module at the time it is imported. Usually the default module
name is used, based on the file or directory containing the module source code,
but it may be necessary to choose another name to avoid a conflict with types or
other modules.

All names:

*   must begin with a letter;
*   may contain letters, digits, or underscore;
*   may not end with an underscore.

> Summary:
>
> *VariableName:*
>
> -   *UncapitalizedName*
>
> *LocalTypeName:*
>
> -   *CapitalizedName*
>
> *LocalFunctionName:*
>
> -   *UncapitalizedName*
>
> *ModuleName:*
>
> -   *CapitalizedName*
>
> *TypeName:*
>
> -   [ *ModuleName* `.`{.syntax} ] *LocalTypeName*
>
> *FunctionName:*
>
> -   [ *ModuleName* `.`{.syntax} ] *LocalFunctionName*

> NOTE A brief metasyntactic aside: I'm going to include the formal syntax in
> fragments as I go along, but feel free to ignore it; it should be redundant
> with the text. Keywords and language punctuation will be in `code font`, such
> as the "`.`" characters above. Metasyntactic names (such as *VariableName*)
> are always capitalized and italicized and are each defined in a single place
> in this document, by one or more alternatives. (The examples above each
> provide a single alternative; see e.g. *MethodPredicate* below for an example
> with multiple alternatives.) The metasyntactic punctuation is
>
> *   ==[ *xx* ]== to indicate that *xx* is optional;
> *   =={ *xx* }== to indicate that *xx* may be repeated zero or more times;
> *   ==( *xx* | *yy* | *zz* )== to indicate that exactly one of the
>     alternatives must be present; and
> *   ==[ *xx* | *yy* | *zz* ]== to indicate that at most one of the
>     alternatives may be present.

## Comments

Comments use the same syntax as C++ and many other languages:

*   Everything from a "`/*`" to the next "`*/`" is a comment.
*   Everything from a "`//`" to the end of the line is a comment.

## Function and method declarations

A Retrospect source file contains declarations of functions, methods, and types.
Function and method declarations are described in this section, and type
declarations in the following section.

### Functions

A function declaration specifies the function's name and its signature. The
signature of a function determines:

*   the number of arguments
*   which, if any, of the arguments are *inout*
*   whether the function returns a result
*   whether the function is *open*, i.e. whether methods for it may be declared
    in other modules
*   whether the function is *private*, i.e. whether it is only visible in the
    module in which it is declared

For example,

```go
function gcd(x, y)
```

declares a function named "gcd" with two arguments. The argument names in a
simple declaration like this are ignored, although choosing meaningful names can
be useful for documentation purposes. Neither of the arguments is inout (since
they are not annotated), the function returns a result (since it was introduced
with the keyword `function` instead of `procedure`), and it is closed, i.e. all
methods for this function will be declared in this module (since it wasn't
marked open).

Another example:

```go
procedure swap(collection=, key1, key2)
```

declares a function named "swap" with three arguments. The first argument is
inout, and the function has no other result (because it was declared with
`procedure` rather than `function`). (Functions that do not return a result are
still referred to as functions, even though they're declared using a different
keyword.)

A call to a function with an inout argument is restricted in the kind of
expression it can provide for that argument: it must be a "left hand side"
expression (i.e. one that could appear on the left hand side of an assignment)
and it must be followed by an "=". For example, we could call the function
declared above with a statement like

```go
swap(a=, [i], [i+1])
```

(to swap two elements of the array `a`), or

```go
swap(a[i].bounds=, "min", "max")
```

(to swap `a[i].bounds.min` and `a[i].bounds.max`), but not

```go
swap(last(a)=, "min", "max")
```

since a function call like `last(a)` may not appear on the left hand side of an
assignment.

### Methods

A method declaration declares an implementation for a previously-declared
function. For example, we could implement the "gcd" function with this method:

```go
method gcd(x, y) {
  if y == 0 {
    return x
  } else {
    return gcd(y, x % y)
  }
}
```

Note that methods name the function they are implementing (in this case `gcd`)
but do not have names of their own; there is no way in the language to refer to
a specific method.

When (as in this case) a function is fully implemented by a single method the
two declarations would typically be combined, e.g.

```go
procedure swap(collection=, key1, key2) {
  t = collection[key1]
  collection[key1] = collection[key2]
  collection[key2] = t
}
```

If a method is defined by a single expression a more compact syntax (using `=`
in place of `{}`) is available:

```go
function gcd(x, y) = (y == 0) ? x : gcd(y, x % y)
```

Separating function and method declarations allows a function to be implemented
by more than one method, providing functionality similar to "virtual functions"
in languages like C++ or "generic functions" in Common Lisp. The appropriate
method is chosen based on the types of the argument values.

For example, Retrospect's API for *reducers* uses four functions:

```go
open function initialState(reducer)
open function nextState(reducer, state, input)
open function combineStates(reducer, state1, state2)
open function finalState(reducer, state)
```

To implement a simple "mean" Reducer we could declare a new type (in this case a
singleton is sufficient) and corresponding methods for those functions:

```go
method initialState(MeanReducer) = { count: 0, sum: 0 }

method nextState(MeanReducer, state, Number input) =
    { count: state.count+1, sum: state.sum+input }

method combineStates(MeanReducer, state1, state2) =
    { count: state1.count+state2.count, sum: state1.sum+state2.sum }

method finalState(MeanReducer, state) = state.sum / state.count
```

### Default methods

Retrospect's logic for method resolution is much simpler than that of languages
like C++ or Common Lisp. It is an error if more than one method matches the
provided argument types, *except* that

*   methods may be declared to be "default" methods (methods not declared
    "default" are assumed to be preferred), and
*   if more than one method matches but exactly one preferred method matches,
    that method is used.

For example, we might declare that `MeanReducer` is a subtype of `MyReducer` and
that by default `None` values are ignored by values of type `MyReducer`:

```go
// By default, "None" inputs to MyReducers are discarded
method nextState(MyReducer reducer, state, None) default = state
```

Since the `nextState` method defined earlier only applies to (MeanReducer,
Number), this default method will be used if `update` is called with
(MeanReducer, None). On the other hand, if we wanted MeanReducer to treat None
as equivalent to zero we could add another method to override the default, e.g.

```go
method nextState(MeanReducer r, state, None) = nextState(r, state, 0)
```

or we could consolidate the `nextState` methods for MeanReducer into a single
one with no type restriction on the input:

```go
method nextState(MeanReducer, state, input) {
  state.count += 1
  if input is not None {
    state.sum += input
  }
  return state
}
```

(The other changes, from expression form to statement form and from creating a
new state to updating the given one, are just for readability; they don't affect
the behavior or performance of the method.)

### Discarding or extracting arguments and the limitations of method resolution

Method declarations may indicate that the value of an argument is unused, by
using `_` in place of the name. If a type is provided for the argument the name
can be omitted completely. For example, this method uses only its second
argument so neither of the other arguments needs a name:

```go
// A DiscardAll reducer ignores its input
method nextState(DiscardAll, state, _) = state
```

Method declarations may also extract the elements of an argument if it will
always be a list with a known size or struct with known keys; for example, the
earlier method declaration above could also have been written

```go
method nextState(MeanReducer, {count, sum}, Number input) =
    { count: count+1, sum: sum+input }
```

An extracted argument is implicitly declared to be of type `Array` or `Struct`,
but method resolution will not consider the size or keys of the argument value.
For example, attempting to declare these two methods:

```go
method foo([x, y], n) { ... }
method foo([x, y, z], n) { ... }
```

would fail; both are considered applicable to any call with an array as the
first argument. And

```go
method foo([x, y], n) { ... }
method foo(Array a, n) default { ... }
```

would not have the desired effect; the first (preferred) method will always be
used in preference to the second, so a call with an array of length three would
match the first declaration and then throw an error because the array's length
is not two.

To get the desired behavior in either of these cases you would have to write a
single method that took an argument of type Array and tested its length
explicitly.

The type `Integer` has a similar limitation: when used to constrain a method
argument, it is resolved as `Number` (followed by a test that it is actually an
integer before starting the method body). For example,

```go
method foo(Integer x) { ... }
method foo(Number x) default { ... }
```

would not have the desired effect; e.g. a call `foor(0.5)` would match the first
declaration and then throw an error because the argument is not an integer.

### Cross-module method declarations

You can declare methods for a function from another module, as long as that
function was declared to be open, e.g.

```go
method Core.add(MyType x, MyType y) {
  ...
}
```

to add a method to the `add` function declared as part of the `Core` module.
(The "`Core.`" isn't actually needed unless there's also an `add()` function
declared in the current module, but I'm including it here to remind us that
we're talking about a function from a different module.)

There is an important restriction, however: *methods declared for functions from
other modules must restrict at least one argument to a type declared in this
module*. (Or: "You can't implement other people's functions over other people's
types".) For example, you would not be allowed to declare

```go
method Core.add(Number x, None y) {
  ...
}
```

since neither the function (`add`) nor any of the argument types (`Number` and
`None`) were declared in this module.

(This restriction gives us the desirable property that the behavior of a given
function call can be determined by examining the method declarations in a
clearly delineated set of modules: the module declaring the function itself,
plus the module(s) declaring the types of the argument values; no other module
can have relevant declarations.)

### Generalized method predicates

The examples above used the simplest kind of *method predicate*: one or more of
the function arguments were each restricted to a single type. While that is
sufficient for most cases, Retrospect also allows type restrictions to be
combined in arbitrary boolean expressions. For example,

```go
method add(x, y) (x is MyType or y is MyType) {
  ...
}
```

declares a method that is applicable if either arg is MyType.

(Note that the cross-module method declaration rule still applies: you cannot
declare a method for a function from another module unless any combination of
argument values that makes the method's predicate true will always include at
least one argument value with a type declared in this module.)

> Summary:
>
> *FunctionDeclaration:*
>
> -   \[ `open`{.syntax} | `private`{.syntax} ] ( `function`{.syntax} |
>     `procedure`{.syntax} ) *LocalFunctionName* `(`{.syntax} [
>     *ArgDeclarations* ] `)`{.syntax} [ *MethodBody* ]
>
> *MethodDeclaration:*
>
> -   `method`{.syntax} *FunctionName* `(`{.syntax}[ *ArgDeclarations*
>     ]`)`{.syntax} [ `(`{.syntax} *MethodPredicate* `)`{.syntax} ] [
>     `default`{.syntax} ] *MethodBody*
>
> *ArgDeclarations:*
>
> -   *ArgDeclaration* \{ `,`{.syntax} *ArgDeclaration* &rbrace;
>
> *ArgDeclaration:*
>
> -   ( *ExtractLhs* | *TypeName* [ *VariableName* ] ) [`=`{.syntax} |
>     `<<`{.syntax}]
>
> *MethodBody:*
>
> -   `{`{.syntax} [ *Statements* ] `}`{.syntax} {.libug}
> -   `=`{.syntax} *Expression*
>
> *MethodPredicate:*
>
> -   [ `not`{.syntax} ] `(`{.syntax} *MethodPredicate* `)`{.syntax} {.libug}
> -   *VariableName* `is`{.syntax} [ `not`{.syntax} ] *TypeName*
> -   *MethodPredicate* ( `and`{.syntax} | `or`{.syntax} ) *MethodPredicate*

## Type declarations

A type identifies a subset of the set of all values.

New types may be defined in one of three ways:

*   as a new singleton;
*   as a new compound; or
*   as a union of other types.

Declaring a singleton or compound introduces new value(s) unique to that type;
declaring a union just groups together existing values.

### Singletons

The `MeanReducer` example given earlier could be declared just as:

```go
singleton MeanReducer is Reducer
```

The `MeanReducer` type contains exactly one value, also written as
`MeanReducer`. This type is also being declared as a subtype of `Reducer` (a
union type) so that methods declared to apply to `Reducer` can be used on it.
Multiple supertypes may be listed after the "is", if desired.

### Compounds

To define a new type with multiple values, use `compound`, e.g.

```go
compound BoundingSumReducer is Reducer

// Returns a sum reducer that limits its inputs to the given range
function boundingSum(min, max) = BoundingSumReducer_({ min: min, max: max })

method initialState(BoundingSumReducer) = 0

method nextState(BoundingSumReducer bsr, state, input) =
    state + min(bsr_.max, max(bsr_.min, input))

method combineStates(BoundingSumCollector, state1, state2) = state1 + state2
```

Values of a compound type are returned by its *compounding function* (in this
case, `BoundingSumReducer_`), which given any value wraps it to create a value
of the new type. It is common to wrap simple structs (as we do here), but any
value can be wrapped. The reverse operation (unwrapping, to return the original
value) is denoted by the postfix "_" operator (e.g. in `bsr_.max`).

### Unions

Union types serve the same function as superclasses (or parent interfaces) in
other languages. For example, the Core module declares

```go
open type Reducer
```

and then defines methods for Reducer; other types that are declared subtypes of
Reducer (such as MeanReducer and BoundingSumReducer above) inherit those methods
(if declared as `default` methods, they may be overridden; otherwise they may
not).

Union types may in turn be declared to be subtypes of other union types, e.g.

```go
type MyReducer is Reducer
```

Alternatively union types may be declared to contain other existing types, e.g.

```go
type Numberish contains Number, None, Complex, Rational
```

declares a new type that is a supertype of four existing types; additional
subtypes of Numberish could still be declared later (e.g. `singleton Infinity is
Numberish`).

A union declaration may not, however, combine `is` and `contains`; doing so
would allow you to create a new type containment relationship among existing
types, with the same kinds of potential problems as cross-module method
declarations.

### Special limitations of the type Integer

As hinted at in the section on method resolution, the type `Integer` is a subset
of `Number` rather than a full Retrospect type, and consequently there are some
limitations on how it may be used:

*   unions may not be declared to contain the type `Integer` (they must contain
    the full `Number` type instead); and
*   method predicates may not be specified using `Integer`.

> Summary:
>
> *TypeDeclaration:*
>
> -   \[ `private`{.syntax} ] ( `singleton`{.syntax} | `compound`{.syntax} )
>     *LocalTypeName* [ `is`{.syntax} *Types* ]
> -   [ `open`{.syntax} | `private`{.syntax} ] `type`{.syntax} *LocalTypeName* [
>     ( `is`{.syntax} | `contains`{.syntax} ) *Types* ]
>
> *Types:*
>
> -   *TypeName* \{ `,`{.syntax} *TypeName* &rbrace;

## Statements

The body of a method declaration contains statements indicating how the value(s)
returned by the method should be computed.

Statements in Retrospect can be grouped into a few categories:

*   computing new values: assignment and function call statements
*   `if`
*   `return`
*   enumerating elements of a collection: `for`, emit (`<<`), and `break`
*   error checking and debugging: `assert` and `trace`

> Summary:
>
> *Statements:*
>
> -   *Statement* \{ *Separator* *Statement* &rbrace;
>
> *Statement:*
>
> -   *AssignmentStatement*
> -   *FunctionCall*
> -   *IfStatement*
> -   *ReturnStatement*
> -   *ForStatement*
> -   *EmitStatement*
> -   *BreakStatement*
> -   *AssertStatement*
> -   *TraceStatement*
>
> *Separator:*
>
> -   `;`{.syntax} {.libug}
> -   *LineBreak*

### Assignment statements

The simplest form of assignment does exactly what you'd expect:

```go
var = expression
```

evaluates the expression on the right hand side and sets the value of the
variable.

The left hand side of an assignment may select only part of the variable to
update, e.g.

```go
var[index].field = expression
```

... but keep in mind that this does *not* modify the value that had previously
been stored in `var`; all values are immutable. Instead it is closer to

```go
temp = var[index]
temp = replaceElement(temp, "field", expression)
var = replaceElement(var, [index], temp)
```

where `replaceElement()` returns a copy of its first argument with the specified
element changed.

(That is a simplification of the actual implementation, because that version has
a couple of issues:

*   If some work is needed to find `var[index]` (e.g. a hashtable lookup) it
    would have to be done twice.

*   If `var[index]` is a large object, the first call to `replaceElement()`
    would have to copy it since it is still accessible as `var[index]` until
    after the second call.

The actual implementation avoids both these issues.)

Due to inout function arguments an assignment statement may update multiple
variables, e.g.

```go
x = pop(stack=)
```

assigns new values to both `x` and `stack`. It is an error for a single
statement to try to assign to the same variable more than once, e.g.

```go
// THIS IS AN ERROR (two assignments to 'stack')
x = [pop(stack=), pop(stack=)]
```

or even

```go
// THIS IS STILL AN ERROR (two assignments to 'stacks')
x = [pop(stacks[i]=), pop(stacks[j]=)]
```

Functions that only return values through inout arguments (declared with the
`procedure` keyword) are called as statements, e.g.

```go
swap(a.elements=, i, i+1)
```

The usual compound assignment operators (`+=`, `-=`, `*=`, `/=`) are available
and work in the usual way with numbers, e.g. `x += 3` is equivalent to `x = x +
3` if `x` is a number. More generally their behavior is determined by the
function `binaryUpdate`:

```go
// These two statements are equivalent (and similarly for -=, *=, and /=):
x += y
binaryUpdate(x=, [a, b] -> a + b, y)
```

The default implementation of `binaryUpdate` just applies the lambda:

```go
method binaryUpdate(lhs=, lambda, rhs) default {
  lhs = lambda[lhs, rhs]
}
```

making `x += 3` equivalent to `x = x + 3`, but the default method is overridden
when the left hand side is a Matrix; see the
[Core Module Reference](library_reference.md#binaryupdatelhs-lambda-rhs) for
details.

Retrospect also includes two additional compound operators: `&=` and `|=`.

```go
// These two statements are equivalent:
x &= y
concatUpdate(x=, y)
```

Why `concatUpdate()`, rather than just `x = x & y`? The `concat` (`&`) function
is lazy: given any two one-dimensional matrices it returns a new matrix that
wraps its arguments. This makes `&` very cheap, but retrieving elements from its
result is a little more expensive than retrieving them from its inputs. Deeply
nested concatenations become inefficient; usually you should pipe the result of
concatenation through `save` or some other collector instead.

`concatUpdate()` on the other hand, can only be called with an array as its
first argument, and it always returns an array. This will sometimes be more
expensive than `concat`, but behaves better when called repeatedly to append to
an array (as typically happens with `&=` statements).

(Why is `&=` not defined in terms of `binaryUpdate`, like the other compounds?
Because `binaryUpdate` is intended to allow types to implicitly distribute the
binary operation across their elements, but `&` is typically an operation that
applies to whole collections, so distributing it would be surprising.)

`|=` is usually used to update each element of a Matrix with a given Lambda,
e.g.

```go
// These two statements are equivalent if x is a Matrix (they replace each
// element of x with its square root).
x |= -> sqrt(#)
x = sqrt(^x) | save
```

More precisely,

```go
// These two statements are equivalent:
x |= y
binaryUpdate(x=, [a, b] -> b @ a, y)
```

### Extracting assignments

Assignment statements can also destructure arrays or structs, e.g.

```go
// Sets variables a and b.
// someExpression must evaluate to a struct with fields "a", "wacky key",
// and "xx".  The "xx" field is discarded.
{a: a, "wacky key": b, xx: _} = someExpression
```

or

```go
// Swap x and y.
[x, y] = [y, x]
```

or

```go
// Equivalent to
//   x = x[1][1]
// except that it errors if either x or x[1] is not an array of length 1.
[[x]] = x
```

Destructuring cannot be used with compound assignment operators.

> Summary:
>
> *AssignmentStatement:*
>
> -   *VariableName* \{ *Index* \} *AssignmentOp* *Expression*
> -   *FunctionCallExpression*
> -   *ExtractLhs* `=`{.syntax} *Expression*
>
> *Index:*
>
> -   `[`{.syntax} [ *Expression* \{ `,`{.syntax} *Expression* \} ] `]`{.syntax}
>     {.libug}
> -   `.`{.syntax} *UncapitalizedName*
> -   `@`{.syntax} *Expression*
> -   `_`{.syntax} {.libug}
>
> *AssignmentOp:*
>
> -   [ `=`{.syntax} | `+=`{.syntax} | `-=`{.syntax} | `*=`{.syntax} |
>     `/=`{.syntax} ]
>
> *ExtractLhs:*
>
> -   *VariableName*
> -   `[`{.syntax} [ *ExtractLhs* \{ `,`{.syntax} *ExtractLhs* \} ] `]`{.syntax}
>     {.libug}
> -   `{`{.syntax} [ *ExtractLhsStructElement* \{ `,`{.syntax}
>     *ExtractLhsStructElement* \} ] `}`{.syntax} {.libug}
> -   `_`{.syntax} {.libug}
>
> *ExtractLhsStructElement:*
>
> -   ( *UncapitalizedName* | *StringLiteral* ) `:`{.syntax} *ExtractLhs*
> -   *VariableName*

### `if` statements

The syntax and behavior of an `if` statement is unsurprising, e.g.

```go
if a is None {
  // do something
} else if a < 0 or a > 10 {
  // do something else
}
```

Like go, and unlike most C-descended languages, the conditions need not be in
`()` but the nested statements must be in `{}`.

> Summary:
>
> *IfStatement:*
>
> -   `if`{.syntax} *Expression* `{`{.syntax} [ *Statements* ] `}`{.syntax} \{
>     *ElseIf* \} [ `else`{.syntax} `{`{.syntax} [ *Statements* ] `}`{.syntax} ]
>
> *ElseIf:*
>
> -   `else`{.syntax} `if`{.syntax} *Expression* `{`{.syntax} [ *Statements* ]
>     `}`{.syntax} {.libug}

### `return` statements

The `return` statement is also unsurprising; it can have an expression (in
methods for functions that were declared with the `function` keyword) or not (in
methods for functions declared with the `procedure` keyword).

In methods with inout arguments, executing a `return` statement also returns the
current values of the inout arguments.

Unlike most C-descended languages, Retrospect does not allow returning directly
from inside a `for` loop; instead you must `break` first. See examples in the
next section.

> Summary:
>
> *ReturnStatement:*
>
> -   `return`{.syntax} [ *Expression* ]

### `for` statements

Retrospect `for` loops are more complex than in most languages, because they
provide several interacting options:

*   parallelizable or sequential execution
*   collecting results with `collect`
*   early exit with `break`

The core functionality of a `for` loop is one of three behaviors:

1.  Execute the body of the loop once for each element of a collection,
    independently (i.e. the execution for one element is unaffected by the
    execution of other elements, and so can be done in parallel with them).

2.  Execute the body of the loop iteratively, starting each execution with the
    results of the previous execution (i.e. the execution is inherently
    sequential).

3.  Execute the body of the loop once for each element of a collection in order,
    also passing in the results of the previous execution (i.e. a combination of
    the previous two options).

A simple example of the first kind of loop, computing the mean and standard
deviation of a collection of numbers:

```go
for x in nums {
  n << 1
  xSum << x
  x2Sum << x ** 2
} collect {
  n, xSum, x2Sum =| sum
}
mean = xSum / n
sd = sqrt((x2Sum - xSum * mean) / (n - 1))
```

This uses three collectors (described below) to accumulate the count, sum, and
sum-of-squares while processing each element of the collection. The use of
collectors enables the elements to be processed in parallel.

A simple example of the second kind of loop rewrites the gcd function
(previously implemented with recursion) to use iteration:

```go
function gcd(x, y) {
  for sequential x, y {
    if y == 0 { break }
    [x, y] = [y, x % y]
  }
  return x
}
```

The `sequential` keyword is required to indicate that this is an iterative (or
non-parallelizable) loop; it is followed by the variables whose values will be
passed from one iteration to the next.

An example of the third kind of loop returns the key of the first element in a
sequence of numbers that is less than the element that precedes it:

```go
function firstDecrease(nums) {
  previous = None
  for key: x in nums sequential previous {
    if (previous is not None) and x < previous {
      break { return key }
    }
    previous = x
  }
  return None
}
```

Where the first example used `for x in nums` to bind `x` to the current element
of the collection, this example uses `for key: x in nums` to also bind `key` to
the key of the current element.

Statements in the body of a `for` loop are subject to some rules about variable
access:

*   Statements in the body can read the values of any variables that were set
    before the loop started.

*   Statements in the body can only modify their collector variables by emitting
    a value to them.

*   Statements in the body of a sequential loop can modify the variables listed
    after the `sequential` keyword.

*   Any other variables set in the body of a loop are local to that execution of
    the loop, and will not be visible after the body has finished executing.

### `for` with `collect`

Parallelizable loops cannot use ordinary variables to accumulate their results;
instead they use emit statements and *collectors*. The standard collectors
include simple numeric operations like `sum` and `min`, and constructors like
`saveUnordered` (which returns an array containing all emitted values). Loops
can use any number of collectors, e.g.

```go
for x in nums {
  if x > 0 {
    positives << x
  } else if x < 0 {
    negatives << x
  } else {
    numZero << 1
  }
} collect {
  positives, negatives =| saveUnordered
  numZero =| sum
}
```

produces three results: an array of the positive values, an array of the
negative values, and a count of the zeros.

Within the loop collected variables can only be modified by emit (`<<`)
statements, and cannot be read. Emit statements are similar to assignments, but
do not allow indexing or destructuring on the left hand side. Some collectors
(such as `sum`) allow multiple emits to the same variable within one execution
of the loop body, but see the next section for some that do not.

Collected variables can be passed as parameters to functions, with appropriate
annotation; e.g.

```go
procedure maybeEmitElements(output<<, obj) {
  if isInteresting(obj.x) {
    output << obj.x
  }
  if isInteresting(obj.y) {
    output << obj.y
  }
}
```

could be called as e.g.

```go
for x in objs {
  maybeEmitElements(results<<, x)
} collect {
  results =| saveUnordered
}
```

If none of the standard collectors suffice, new collectors may be implemented by
declaring a new subtype of `Reducer` or `Saver`; see the enumeration API doc for
details.

Collectors may also be used in sequential loops; while not as critical there
(since sequential loops are not subject to the same restrictions as
parallelizable loops) they are often convenient.

When parallel loops are nested it may be desirable to emit values to the outer
loop's collectors from the inner loop; to enable this just `collect` the
variable without providing a collector:

```go
for x in xs {
  for y in ys {
    result << foo(x, y)
  } collect {
    result  // use outer loop's collector
  }
} collect {
  result =| mean
}
```

(Note that just omitting the `collect` on the inner loop would give an error
that the modification of `result` is not allowed.)

### `save`

The `save` collector provides another way for parallelizable loops to produce a
result: a new collection, with the same keys as the collection being looped
over, where each element of the new collection is derived from the corresponding
element of the original collection.

A very simple example:

```go
for x in source {
  result << foo(x)
} collect {
  result =| save
}
```

If `source` is an array of 100 elements, `result` would also be an array of the
same size. Since each element is computed independently, we could potentially
compute all 100 in parallel.

At first glance this appears to be similar to `saveUnordered`, but there are
some important differences.

First, using `saveUnordered` in the fragment above would produce an array of the
same length but it could be in any order, while `save` guarantees that each
output element will have the same key as the input from which it was derived.

(This difference is only applicable to parallel loops; a sequential loop using
`saveUnordered` *is* guaranteed to get outputs in the same order as inputs. The
remaining differences apply to both parallel and sequential loops.)

Second, while the loop body can emit to a `saveUnordered` collector any number
of times, it can only emit to a `save` variable at most once. Looping over an
array of length 100 with `save` will always produce an array of length 100,
while a `saveUnordered` output could end up with any length.

(Note that if an execution of the loop body doesn't emit to a `save` variable
the corresponding array element will be *absent*; the implications of that are
covered later.)

Third, with `save` the output will have the same structure as the input -- e.g.
if `source` was a 4x4 matrix `result` would be as well. If `source` was a struct
with three fields, `result` would be as well. The output of `saveUnordered` will
always be an array.

Sequential loops over a collection (type 3 above) can use `save`, but sequential
loops without a collection (type 2) cannot.

> Summary:
>
> *ForStatement:*
>
> -   `for`{.syntax} [ *ForCollection* ] [ *ForSequential* ] `{`{.syntax} [
>     *Statements* ] `}`{.syntax} [ *ForCollect* ]
>
> *ForCollection:*
>
> -   [ *ExtractLhs* `:`{.syntax} ] *ExtractLhs* `in`{.syntax} *Expression*
>
> *ForSequential:*
>
> -   `sequential`{.syntax} [ *VariableName* { `,`{.syntax} *VariableName* } ]
>
> *ForCollect:*
>
> -   `collect`{.syntax} `{`{.syntax} *CollectVar* { *Separator* *CollectVar* }
>     `}`{.syntax} {.libug}
>
> *CollectVar:*
>
> -   *VariableName* { `,`{.syntax} *VariableName* } [ `=|`{.syntax}
>     *Expression* ]

### `continue`

As in C-descended languages, `continue` ends the current iteration of the
nearest enclosing loop.

### `break`

As in C-descended languages, `break` causes an immediate exit from the nearest
enclosing loop.

Retrospect adds an optional block of statements which are executed immediately
after the `break` and before continuing after the loop; because they are
executed after the loop has terminated, those statements are not subject to the
usual restrictions on modifying variables from outside the loop.

For sequential loops the implications are pretty straightforward:

*   The current iteration is terminated, and no further iterations will be
    started.

*   `collect` outputs are available following a `break`.

For parallel loops `break` is a little more subtle:

*   The current loop body execution is terminated immediately.

*   If more than one loop execution can reach a `break` statement, exactly one
    of them will execute its associated block but there is no guarantee as to
    which.

*   `collect` outputs are *not* available following a `break`.

For example,

```go
for x in nums {
  result << x
} collect {
  result =| product
}
```

computes the product of a collection of numbers. If the collection may include
zero, it might be worthwhile to exit as soon as we encounter zero (since the
product will have to be zero). We could do that this way:

```go
for x in nums {
  if x == 0 {
    break { result = 0 }
  }
  result << x
} collect {
  result =| product
}
```

Note the assignment to `result` in the `break` block: without that, it would
have been an error to try to read `result` after the loop exit.

The fact that parallel loops may be executed out of order means that this
fragment:

```go
firstZero = None
for [i]: x in nums {
  if x == 0 {
    break { firstZero = i }
  }
}
```

will find the index of a zero in the given array (if one exists), but not
necessarily the first one. If it was necessary to find the first one, the
easiest fix would be to simply add a `sequential` keyword to the loop.

> Summary:
>
> *BreakStatement:*
>
> -   `break`{.syntax} [ `{`{.syntax} [ *Statements* ] `}`{.syntax} ]

### `assert` statements

`assert` statements provide an easy way to detect errors or other unexpected
conditions in a program. The simplest version just provides an expression that
is expected to be true, e.g.

```go
assert i is None or i < j
```

When the statement is executed the condition is evaluated; if it is true the
program continues, if it is false the program stops with an error.

It is also possible to include an error message, e.g.

```go
assert i < maxIterations, "Approximation did not converge"
```

As with any error in Retrospect, a failed `assert` will display as much
information as possible about the program's state. At a minimum, that will
include

*   the values of all variables referenced by the `assert` statement;

*   the values of all previously-assigned variables that could have been used by
    subsequent statements had the assertion succeeded (the "live" variables);

*   if this statement was part of a method body, the statement that called that
    method and the live variables at the time of the call, etc.

For example, in

```go
function foo(x, y) {
  s = x | sum
  assert s > 0
  return y | filter(# > s) | sum
}
```

if the assertion fails the error display will include the values of `s` and `y`,
but not `x` since `x` is no longer live.

If `x` would be valuable for debugging this error it can be kept alive by just
listing it in the assert:

```go
assert s > 0, x
```

(Note that in some cases keeping a variable alive longer may require additional
memory, which is why it is not done by default.)

> Summary:
>
> *AssertStatement:*
>
> -   `assert`{.syntax} *Expression* [ `,`{.syntax} *StringLiteral* ] \{
>     `,`{.syntax} *VariableName* &rbrace;

### `trace` statements

A `trace` statement, like a failed `assert` statement, captures the program's
state when executed, but unlike a failed `assert` it doesn't interrupt
execution.

Since a program may potentially execute a `trace` statement a very large number
of times, the Retrospect implementation only saves the state from the first 10
and the last 10 times each `trace` is executed (for some value of 10).

An interface is provided for browsing the trace results during and after program
execution.

> Summary:
>
> *TraceStatement:*
>
> -   `trace`{.syntax} [ ( *StringLiteral* | *VariableName* ) { `,`{.syntax}
>     *VariableName* } ]

## Expressions

Expressions evaluate to values.

### The usual stuff

Numeric literals, string literals, and variable references are similar to most
other languages. Some details:

*   numeric literals only support base ten (no octal, hex, or binary constants);
*   floating-point literals use `e` or `E` for the exponent (e.g. `1e-9`);
*   string literals are delimited by double quotes (`"e.g."`), must be on a
    single source code line, and may use these escape sequences:
    *   `"\b"`, `"\f"`, `"\n"`, `"\r"`, and `"\t"` for the usual whitespace
        characters
    *   `"\\"` and `"\""` for escaping the escape and quote characters
    *   `"\u`xxxx`"`, where xxxx are exactly four hex digits (upper or lower
        case), for an arbitrary 16-bit unicode code point.

Most arithmetic and comparison operators also work the way they do in most other
languages. Retrospect uses `%` for modulo, `**` for exponentiation, and `&` for
string and array concatenation.

```go
t = (a**2 + b**2 > c**2) ? "acute" : "obtuse"
msg = "Your triangle is " & t & "."
```

All of those operators are alternate syntax for functions defined in the `Core`
package, e.g. the fragment above could also have been written

```go
t = lessThan(exponent(c, 2), add(exponent(a, 2), exponent(b, 2))) ? "acute" : "obtuse"
msg = concat(concat("Your triangle is ", t), ".")
```

Note that all of the inequality operators (`<`, `>`, `<=`, and `>=`) are syntax
for a single function `lessThan` (e.g. `x <= y` is interpreted as `not
lessThan(y, x)`).

A value's type can be tested with `is` or `is not`, e.g.

```go
xIsFloat = (x is Number) and (x is not Integer)
```

Array expressions and struct expressions are similar to ECMAScript, e.g.

```go
results = { x: [0, 1, 2], y: [1, a, a**2] }
```

Struct keys must be quoted if they are not valid variable names:

```go
results = { "First Name": "Bob", id: id}
```

If the expression associated with a key is the variable with the same name as
the key, the key can be elided, e.g.

```go
y = [1, a, a**2]
return { x: [0, 1, 2], y }
```

(aka "shorthand property names" in recent versions of ECMAScript).

Retrospect includes the ternary operator (`x ? y : z`) with the usual meaning,
and an "Elvis operator" `x ?: y` equivalent to `(x is not Absent) ? x : y`.

### Indexing

Array elements and struct elements can be accessed using the usual syntax, e.g.

```go
z = results.y[1]
```

Both array indexing and property references are alternate syntax for the more
generic "collection `@` key" operator, so that could also have been written

```go
z = (results @ "y") @ [1]
```

Note that the key of an array element is a single-element array containing the
index (e.g. a key of `[0]` is used to access the first element of an array).
Arrays are one-dimensional matrices; multi-dimensional matrices just use arrays
with more elements as their keys, e.g. `x[i,j]` (or equivalently `x@[i,j]`).

The `@` operator is in turn just syntax for a call to the `at` function, so that
could also have been written

```go
z = at(at(results, "y"), [1])
```

`_` is a postfix operator that unwraps a compound value, i.e. it returns the
value that was passed to the type's compounding function. It can only be applied
to values whose type is a compound defined in the current package. In other
respects it behaves similarly to indexing (without an index) and is allowed in
the same contexts.

The different forms of indexing can be used in any combination on the left hand
side of an assignment as long as they are applied to a variable, e.g.

```go
// Increment one of the given rationals
procedure incrementElement(rationals=, index) {
  rationals[index]_.num += rationals[index]_.denom
}
```

Although left-hand-side indexing uses the same syntax it is translated to
different functions (e.g. `startUpdate` and/or `replaceElement` instead of
`at`) -- see below for details.

### Lambda expressions and `#`

Lambda expressions in Retrospect use a syntax similar to Java, e.g.

```go
transform = x -> [x[0]+1, x[1]-1]
```

sets `transform` to a lambda that, given a two-element array, returns a new
array with the first element incremented and the second element decremented.
Since the left hand side of a lambda is treated as an extracting assignment,
this would usually be written instead as

```go
transform = [x, y] -> [x+1, y-1]
```

(although the two versions behave differently if called with a list of more than
two elements: the first discards the additional elements, and the second
errors).

Retrospect also supports a simplified syntax where the left hand side is omitted
and `#` is used on the right hand side to refer to the argument, e.g. a lambda
that just returns the "count" field of its argument can be written

```go
getCount = -> #.count    // equivalent to x -> x.count
```

To avoid possible confusion, the right hand side of a lambda using the
simplified form may not itself contain a lambda expression.

Note that the value of a lambda expression is not a Retrospect function;
functions are not values. Functions have signatures, methods, and may have inout
arguments; none of those are true for lambda values. Instead, a lambda
expression returns a value that can be passed as the first argument to the `at`
function. Thus code to call the lambda defined above might be written

```go
z = at(transform, [3, 4])
```

or equivalently

```go
z = transform[3, 4]
```

### Function calls and inout arguments

Calls to Retrospect functions are written using the usual syntax:

```go
count = treeSearch(tree, key)
```

Calls to functions with inout arguments must pass a variable (with optional
indices) followed by `=` for each inout arg. For example, the function declared
by

```go
// If key is not present, inserts it with count 1 and returns 1; otherwise
// increments the associated count and returns the new count.
function treeInsert(tree=, key)
```

could be called with

```go
newCount = treeInsert(tree=, key)
```

or

```go
newCount = treeInsert(state.trees[i]=, key)
```

(But not, say, `treeInsert(foo(state)=, key)` -- the expression before the `=`
has the same restrictions as the left hand side of a (non-extracting)
assignment.)

Calls to functions with inout arguments may not appear in some contexts, such as
the collector of a `for` loop or the body of a lambda expression.

No variable can be assigned to more than once in a single statement; for
example,

```go
// NOT ALLOWED -- two assignments to the same variable (state)
state.count = treeInsert(state.trees[i]=, key)
```

### Indexed assignments and inout arguments

As mentioned earlier, `.`, `[]`, or `@` on the left hand side of `=` are not
interpreted as calls to `at()`. In the simplest case they are implemented using
`replaceElement()`, e.g.

```go
a.x = b
// ... is equivalent to:
a = replaceElement(a, "x", b)
```

When more than one indexing operation is combined we use
`startUpdate(collection=, key)`, which returns both the specified element and a
lambda that transforms a new value of the element to an updated collection, e.g.

```go
a[i].x = b
// ... is equivalent to:
temp_a_i = startUpdate(a=, i)  // replaces a with updater for a[i]
temp_a_i = replaceElement(temp_a_i, "x", b)
a = a @ temp_a_i               // gets updated a
```

The updater lambda returned by `startUpdate` (through its inout argument) can be
thought of as wrapping all of the object being updated except for the element
being replaced; when called with the new value for that element it constructs
the updated object.

More deeply nested indexing just translates into more calls to `startUpdate`,
e.g.

```go
a.x.y.z = b
// ... is equivalent to:
temp_a_x = startUpdate(a=, "x")          // replaces a with updater for a.x
temp_a_xy = startUpdate(temp_a_x=, "y")  // replaces temp_a_x with updater for a.x
temp_a_xy = replaceElement(temp_a_xy, "z", b)
temp_a_x = temp_a_x @ temp_a_xy          // gets updated a.x
a = a @ temp_a_x                         // gets updated a
```

The same approach is used to implement indexed inout args, without
`replaceElement`:

```go
newCount = treeInsert(state.trees[i]=, key)
// ... is equivalent to:
temp_state_trees = startUpdate(state=, "trees")
temp_state_trees_i = startUpdate(temp_state_trees=, i)
newCount = treeInsert(temp_state_trees_i=, key)
temp_state_trees = temp_state_trees @ temp_state_trees_i
state = state @ temp_state_trees
```

### Ranges

The `..` operator is alternate syntax for calls to the `range(low, high)`
function, which returns a value representing the sequence of integers from `low`
to `high` inclusive. Ranges are often used as collections to loop over, e.g.

```go
for pass in 1..maxTries sequential state {
  state = updateState(state)
  if closeEnough(state) {
    break { return state }
  }
}
return DidNotConverge
```

or to refer to subranges of an array or other collection, e.g.

```go
// Handle the first 3 elements specially
firstThree = x[1..3]
// ... and remove them from the original array.
x[1..3] = []
```

(There's no special treatment of ranges here -- those are just calls to `at` and
`replaceElement` with a range as the second argument, for which there are
appropriate methods defined.)

The upper and/or lower bound of a range may also be omitted, which just calls
`range` with `None` as the corresponding argument. Unbounded ranges may be used
in some contexts (e.g. array indexing treats a missing lower bound as one, and a
missing upper bound as the length of the array) but not in others (e.g.
sequential `for` loops can range over ranges with missing upper bounds but not
missing lower bounds; parallel `for` loops require that both bounds be present).
The preceding example could just as well have been written this way:

```go
firstThree = x[1..3]
x = x[4..]           // Or equivalently x[4..None]
```

There is also a `rangeWithSize(start, size)` function, which is a more
convenient equivalent to `range(start, start + size - 1)`. It has corresponding
syntax `++`, so e.g. the range of size 4 starting with 3 can be written `3..6`
or `3++4`.

### Pipes

The `|` operator is alternate syntax for calls to the `pipe` function. There are
a number of useful methods defined for this function.

The simplest use of `|` is to pipe a collection into a collector, providing a
more compact alternative to a `for` loop, e.g.

```go
s = a | sum
// ... is equivalent to the following loop:
for x in a {
  s << x
} collect {
  s =| sum
}
```

Most pipelines include at least one transformation step, e.g.

```go
s = a | -> #.count ** 2 | sum
// Equivalent to the following loop:
for x in a {
  s << x.count ** 2
} collect {
  s =| sum
}
```

Multi-step pipelines are just repeated uses of the binary operator; this could
just as well have been written

```go
transformedA = a | -> #.count ** 2
s = transformedA | sum
```

or

```go
myCollector = -> #.count ** 2 | sum
s = a | myCollector
```

(The original version is interpreted as the first of those -- `|` is
left-associative -- but the second returns the same result.)

To understand why those work we have to explain the different methods defined on
`pipe`.

`collection | lambda` returns a new collection that lazily transforms each
element of the original collection when accessed, i.e. `(collection|lambda) @ x`
computes `lambda @ (collection @ x)` (unless the inner `@` returns `Absent`, in
which case the outer `@` is skipped). A `for` loop over the new collection loops
over the original collection transforming each element as it is processed.

`lambda1 | lambda2` returns a new lambda that applies both lambdas in order.

`lambda | collector` returns a new collector that applies the lambda to each
value before collecting it.

### Distributing a Function

Piping a collection through a lambda that applies a single function or operator
is so common that Retrospect provides a more compact notation for it. For
example, these two assignments are equivalent:

```go
transformedA = a^.count
transformedA = a | -> #.count
```

(think of the `^` symbol as indicating that the `.` operator should be
distributed over the elements of the adjacent collection).

We can also use distribution with the unary and binary mathematical operators,
placing the `^` on the same side of the operator as the collection to be
distributed over (e.g. `x ^- y` subtracts `y` from each element of `x`, while `x
-^ y` subtacts each element of `y` from `x`).

Distibuted operators can be nested, so these are all equivalent:

```go
transformedA = -^(a^.count ^** 2)
transformedA = a | -> #.count | -> # ** 2 | -> -#
transformedA = a | -> -(#.count ** 2)
```

`^` can also be used with any function that has no inout arguments, e.g.

```go
x = gcd(12, ^[128, 5, 9]) | save     // x == [4, 1, 3]
```

(`save` ensures that the `gcd` function is called when this statement is
executed; without it we would lazily compute the gcd on each access of `x`).

`^` can be used on more than one of the arguments to a function or operator,
which invokes the `join()` function, e.g. these are equivalent:

```go
c = a ^+^ b
c = join(a, b) | [a1, b1] -> a1 + b1
```

The details of `join(a, b)` depend on the types of `a` and `b`, but in general
it returns a collection with the same keys, whose values are 2-element arrays of
the corresponding elements from `a` and `b`. For example, if `a` and `b` are
matrices with the same dimensions and `alpha` is a number,

```go
c = alpha *^ a ^+^ (1 - alpha) *^ b | save
```

constructs a new matrix of the same dimensions, by element-wise linear
combination of the given matrices. If the dimensions of `a` and `b` didn't match
the `join` would throw an error.

The standard mathematical operators (`+`, `-`, `*`, `/`) are implicitly
distributed if one argument is a matrix and the other is a number or another
matrix, so the previous example could also just be written

```go
c = alpha * a + (1 - alpha) * b | save
```

### Expression syntax summary

> Summary:
>
> *Expression:*
>
> -   *NumberLiteral*
> -   *StringLiteral*
> -   *VariableName*
> -   `(`{.syntax} *Expression* `)`{.syntax} {.libug}
> -   `[`{.syntax} [ [ `^`{.syntax} ] *Expression* { `,`{.syntax} [ `^`{.syntax}
>     ] *Expression* } ] `]`{.syntax} {.libug}
> -   `{`{.syntax} [ *StructElement* {`,`{.syntax} *StructElement* } ]
>     `}`{.syntax} {.libug}
> -   *Expression* [ `^`{.syntax} ] *ExpIndex*
> -   *PrefixOperator* [ `^`{.syntax} ] *Expression*
> -   *Expression* [ `^`{.syntax} ] *BinaryOperator* [ `^`{.syntax} ]
>     *Expression*
> -   *Expression* [ `^`{.syntax} ] *SuffixOperator*
> -   *Expression* `?`{.syntax} [ *Expression* ] `:`{.syntax} *Expression*
> -   *Expression* [ `^`{.syntax} ] `is`{.syntax} [`not`{.syntax}] *TypeName*
> -   [ *ExtractLhs* ] `->`{.syntax} *Expression*
> -   `#`{.syntax} {.libug}
> -   *FunctionCall*
>
> *PrefixOperator:*
>
> -   [ `-`{.syntax} | `not`{.syntax} | `..`{.syntax} ]
>
> *BinaryOperator:*
>
> -   [ `+`{.syntax} | `-`{.syntax} | `*`{.syntax} | `/`{.syntax} | `%`{.syntax}
>     | `**`{.syntax} ]
> -   [ `..`{.syntax} | `++`{.syntax} | `|`{.syntax} ]
> -   [ `==`{.syntax} | `!=`{.syntax} | `<`{.syntax} | `>`{.syntax} |
>     `<=`{.syntax} | `>=`{.syntax} ]
> -   \[ `and`{.syntax} | `or`{.syntax} \]
>
> *SuffixOperator:*
>
> -   `..`{.syntax} {.libug}
>
> *StructElement:*
>
> -   ( *UncapitalizedName* | *StringLiteral* ) `:`{.syntax} [ `^`{.syntax} ]
>     *Expression*
> -   *VariableName*
>
> *FunctionCall:*
>
> -   *FunctionName* [ `(`{.syntax} [ *Arg* {`,`{.syntax} *Arg* } ] `)`{.syntax}
>     ]
>
> *Arg:*
>
> -   [ `^`{.syntax} ] *Expression*
> -   *VariableName* { *Index* } [`=`{.syntax} | `<<`{.syntax}]
>
> *ExpIndex:*
>
> -   `[` [ [ `^`{.syntax} ] *Expression* { `,`{.syntax} [ `^`{.syntax} ]
>     *Expression* } ] `]`{.syntax} {.libug}
> -   `.`{.syntax} *UncapitalizedName*
> -   `@`{.syntax} [ `^`{.syntax} ] *Expression*
> -   `_`{.syntax} {.libug}

## Emitting and collector variables

We have touched on collector variables and the emit operation in passing, but
postponed going into the details until now because it seems easiest to put them
all in one place rather than scattered across function declarations, multiple
statement types, and expressions.

The `collect { x =| y }` clause of a `for` loop actually introduces *three*
variables:

*   `x`, a normal (mutable) variable whose value is defined on exit from the
    loop (only on non-break exit for parallel loops);
*   `x_collector`, a read-only variable only visible within the loop body, set
    to `y` (or a value derived from `y`) before the loop is started; and
*   `x_state`, a mutable variable only visible within the loop body, initialized
    to a value derived from `y` before the loop is started.

(The details of how `x_collector` and `x_state` are initialized are in the
enumeration API doc.)

The emit statement `x << someExpression` is equivalent to

```go
emit(x_collector, x_state=, someExpression)
```

Most of the time these details are not important, but they clarify the behavior
of functions with emitter (`<<`) arguments. For example, this function
declaration

```go
procedure emitSomeOf(collection, output<<)
```

is just an alternate syntax for a pair of arguments:

```go
procedure emitSomeOf(collection, output_collector, output_state=)
```

so a call to `output << x` in the body of its method can be translated to
`emit()` as above, and a call to `emitSomeOf(whatever, x<<)` in a loop body with
a collector `x` will behave as expected.
