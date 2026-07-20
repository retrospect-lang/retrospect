# Unspecified Frame Elements and Phantom Objects

[TOC]

## Unspecified elements

Some operations on frames can leave one or more of their elements in an
unspecified and possibly invalid state:

* All of the elements of a newly-allocated Frame (returned by [`FrameLayout.alloc`][FrameLayout])
are unspecified.
* Calling `clearElement` (or storing `TO_BE_SET` in an element) leaves that element unspecified.
* Calling `removeRange` may add one or more unspecified elements.

The caller of these operations is responsible for using `setElement` or
`setElements` to ensure that each unspecified element's value is set (to
something other than TO_BE_SET) before it is subsequently read (e.g. with
`getElement` or `peekElement`).

Each of a frame's elements are represented by a combination of bytes
(interpreted as uint8s, int32s, and/or float64s) and pointers. An unspecified
element's bytes may have arbitrary values, but all of its pointers are expected
to be null.

[FrameLayout]: ../src/main/org/retrolang/impl/FrameLayout.java

## Frame Replacement

[FrameLayout evolution](frames.md#framelayout-evolution) will cause frames whose
layout has evolved since they were allocated to be automatically replaced with
equivalent frames using the newer layout. The replacement frames are initialized
by copying the contents of the older frame into the newer frame, converting to
the new layout as necessary.

Unfortunately the frame replacer has no way of knowing which of a frame's
elements are currently in an unspecified state. Often this is harmless; data is
copied from the old frame to the new frame, and then overwritten in the new
frame.

Because elements may be unspecified, the conversion process must be tolerant of
unexpected values. For example, if the source template had a union of two
singletons (say, True and False) a properly-initialized element would have a
value of 0 or 1 for the union's tag, but an unspecified element could have an
out-of-range value for the tag. On encountering an invalid tag value the
converter can safely assume that this element is unspecified, and leave the
corresponding element in the new frame uninitialized.

## Phantom Objects

Many layout evolutions simply require copying bytes and pointers from the old
frame to the new one, but some are more complex. For example:

*   Evolving a tagged union of singletons to an untagged union: For example,
    evolving `[]b0⸨0:False; 1:True⸩` to `[]⸨False; True; x0:String⸩` (i.e. a
    varray of booleans to a varray of pointers that may include string elements)
    will set each `x0` to either `True` or `False`.
*   Evolving a compound template to a Frame pointer: For example, evolving
    `[][i0, i1]` to `[]x0:[]i0` (i.e. a varray of int pairs to a varray of
    varrays of ints) will allocate a new varray Frame for each element,
    containing the pair of ints.

(Those examples use varray evolution but the same options arise when evolving to
a record layout.)

If the frame replacer is converting a frame with unspecified elements, it may
end up setting pointers for those elements to non-null values. In the first
example above, any elements whose tag byte is 0 or 1 will get a non-null value
for `x0` (either `True` or `False`). In the second example every element will
get a newly-allocated varray, since all int pairs are plausible values.

This means that after frame replacement we can no longer assume that all of an
unspecified element's pointers will be null; they may contain "phantom objects"
that were added by frame replacement. They're phantoms because they don't
correspond to any value in the program being executed; they resulted from
interpreting uninitialized bytes as values.

This means that if a frame with unspecified elements has been kept over some
action that might have resulted in frame evolution (e.g. a call to a generic
function), we must re-clear those elements (to drop the phantom objects) before
setting them.
