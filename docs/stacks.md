# Stack Entries and Stack Unwinding

[TOC]

This doc describes how the Retrospect VM can represent an in-progress
computation as an execution stack. These representations are constructed on
demand; during normal operation there is no explicit representation of the
Retrospect execution stack (it is implicit in the Java execution stack), but an
operation called *stack unwinding* is used to construct the representation when
needed.

These stack representations serve multiple purposes:

*   If an error stops execution, the stack is provided to the user to enable
    them to determine how far the program had got and what caused the error.
*   `trace` instructions save the current stack to give the user visibility into
    the execution of their program.
*   A function call that must block until some other event happens (e.g while
    waiting for a response to an RPC) unwinds the stack so that the Java thread
    can be used for another computation in the meantime.
*   A complete snapshot of the computation, consisting of all in-progress
    stacks, can be taken periodically during a long-running computation so that
    it need not be restarted from the beginning in the event of a machine
    failure.
*   Snapshots of long-running computations may also be requested by the user,
    and browsed to determine that it is progressing as expected or to estimate
    how much additional time will be required. If a computation is cancelled or
    times out, its final snapshot may provide valuable information to the user.
*   The relatively low limits on the size of the Java stack do not need to
    impose corresponding limits on Retrospect programs; if execution is in
    danger of hitting the Java stack limit, we can unwind it to an explicit
    representation and then continue execution from an empty Java stack (as if
    calling a function that blocked and then immediately unblocked).
*   For performance the Retrospect VM relies on emitting Java byte codes for
    frequently-executed Retrospect instruction blocks. This process makes
    assumptions about e.g. the values of variables based on information about
    their past values; these assumptions may turn out to be false in the future.
    If that happens, the compiled code uses the stack unwinding mechanism to
    construct a complete representation of the state at the moment the
    assumption was violated, enabling the VM to continue the computation
    correctly.

## Stack Entries, TStacks, and TStates

The state of an execution thread is represented by a sequence of *stack
entries*; the first entry in the sequence is the top of the stack, corresponding
to the most deeply nested function call.

Each stack entry identifies the code being executed and the values of any live
local variables. The "code being executed" is usually identified by a VM
instruction, but when executing a built-in method (implemented in Java) some
other identifier is used. (Since built-in methods may call non-built-in methods,
the stack is usually a mix.)

In order to construct stack entries the VM defines a new base type for each such
execution point. Although these are instances of BaseType, they do not have a
corresponding VmType; the "values" constructed with these base types (using e.g.
CompoundValue if they have local variables, or a Singleton if not) are not
usable as values by a Retrospect program, but can reuse all of the mechanisms
for representing and serializing values.

The sequence of entries comprising a stack is represented by a [`TStack`]
\("thread stack") object, which is a simple pair of `first` (a stack entry) and
`tail` (a TStack representing the remaining entries, or null if this is the last
entry in the stack). Since stack entries are ordered from most recent to oldest,
`rest.first` represents the call that led to `first`.

A newly-created TStack is uninitialized; a separate operation is used to fill in
`first` and `tail` (each TStack is write-once). This design has two nice
properties:

*   It is easy to construct a stack representation incrementally, from top to
    bottom, as we return from the corresponding Java methods.

*   It efficient to represent multiple stacks with a shared tail (this can
    happen with `trace` instructions, which save the current stack each time
    they are executed, or with periodic snapshots).

The execution state of the current thread is represented by a [`TState`]
\("thread state") object; for the purposes of this discussion the only relevant
fields are `stackHead` (an ininitialized TStack, non-null if the thread's
execution has been interrupted) and `stackTail` (an uninitialized TStack,
non-null if the call site of the currently-executing method is needed; always
non-null if `stackHead` is non-null).

For example, suppose we execute a Retrospect method that calls `trace` twice and
then encounters an error trying to use an undefined variable. The execution of
this method will return with

*   an uninitialized TStack in `stackTail`;
*   `stackHead` pointing to a sequence of linked TStacks:
    *   the head of the first is a stack entry for the "Undefined variable"
        error,
    *   the head of the second is a stack entry for the instruction that
        referenced the variable, and
    *   the third is the same (uninitialized) TStack that's in the TState's
        `stackTail`;
*   each `trace` instruction also created a TStack (stored separately from the
    TState) whose head is a stack entry for the `tstate` instruction and whose
    tail is the TState's `stackTail`.

On returning from a method, the VM always does two tests:

*   If `stackTail` is non-null, initialize it with a stack entry describing the
    call instruction we just finished executed (including any live locals that
    were saved during the execution), and a newly-created (uninitialized) TStack
    as its tail. Make that new TStack our `stackTail` (so *our* caller will
    populate it when we exit).
*   If `stackHead` is non-null, execution has been interrupted so skip the rest
    of this method and return immediately (since our caller does the same check,
    this leads to an immediate full unwind of the execution stack).

Since TStacks may have multiple references, they use the same reference counting
infrastructure as other Retrospect values.

(TStates, on the other hand, are not shared between threads and not referenced
from other objects, so there is no reason to reference count them.)

[`TStack`]: ../src/main/org/retrolang/impl/TStack.java
[`TState`]: ../src/main/org/retrolang/impl/TState.java
