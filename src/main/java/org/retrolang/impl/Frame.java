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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.retrolang.code.Op;
import org.retrolang.impl.Evolver.Evolution;
import org.retrolang.util.SizeOf;
import org.retrolang.util.StringUtil;

/**
 * Frame is the base class for a set of generic subclasses (defined in Frames) of varying sizes,
 * which can be used to create more space-efficient representations of Values.
 *
 * <p>Frame subclasses have two fixed fields: a ref count (inherited from RefCounted) and a
 * FrameLayout. The FrameLayout determines the interpretation of the remaining fields; it serves a
 * similar purpose to the virtual method table in Java or C++ objects. All Frame instances that
 * share a FrameLayout have the same baseType and structure. If a Frame's FrameLayout evolves, the
 * {@link #layoutOrReplacement} field may be repurposed to point to a replacement Frame with the new
 * layout.
 *
 * <p>The remaining fields of a Frame instance can be used to store bytes, ints, doubles, and
 * pointers as required by the FrameLayout (Frame subclasses only define int fields, but Unsafe
 * methods are used to reinterpret them as bytes or doubles). Each Frame subclass provides a
 * different combination of pointer and non-pointer fields, so that each FrameLayout can be paired
 * with a Frame subclass that best matches its requirements.
 *
 * <p>If a FrameLayout requires more pointer or non-pointer fields than are available in any Frame
 * subclass, each instance will use a byte[] and/or Object[] overflow array to store the extras; to
 * simplify the logic any overflow arrays are always stored in the first one or two pointer fields
 * (with the byte[] overflow first, if both are required).
 *
 * <p>Most of the standard methods (from Value, RefCounted, and Object) are implemented by
 * redirecting to a corresponding method on the FrameLayout.
 */
public abstract class Frame extends RefCounted implements Value {
  static final long BASE_SIZE = RefCounted.BASE_SIZE + SizeOf.PTR;

  /** A superclass shared by FrameLayout and Replacement. */
  abstract static class LayoutOrReplacement {
    /** Implements RefCounted.visitRefs() for a Frame that uses this LayoutOrReplacement. */
    abstract long visitRefs(Frame f, RefVisitor visitor);
  }

  /**
   * The FrameLayout that determines how the remaining fields are interpreted, or a Replacement if
   * this Frame has been replaced.
   */
  LayoutOrReplacement layoutOrReplacement;

  private static final VarHandle LAYOUT_OR_REPLACEMENT =
      Handle.forVar(Frame.class, "layoutOrReplacement", LayoutOrReplacement.class);

  static final Op GET_LAYOUT_OR_REPLACEMENT =
      Op.getField(Frame.class, "layoutOrReplacement", LayoutOrReplacement.class);
  static final Op SET_LAYOUT_OR_REPLACEMENT = GET_LAYOUT_OR_REPLACEMENT.setter().build();

  /** Returns the index of this Frame's subclass. */
  abstract int frameClassIndex();

  /**
   * Returns true if this Frame has been replaced. May transition from false to true asynchronously,
   * but will never transition from true to false.
   */
  boolean isReplaced() {
    return layoutOrReplacement instanceof Replacement;
  }

  /** Called from the Coordinator to link this frame to its replacement. */
  void setReplacement(Replacement replacement) {
    assert !isReplaced();
    // Other threads will read this without having synchronized, so use setRelease() to ensure that
    // they see it fully initialized.
    LAYOUT_OR_REPLACEMENT.setRelease(this, replacement);
  }

  /**
   * If this frame's layout has not evolved, return null. Otherwise return a {@link Replacement}
   * that points to its replacement. If there has been a sequence of replacements the Replacement
   * returned will point to the most recent version of this frame.
   */
  static Replacement getReplacement(Frame f) {
    Replacement result = null;
    for (; ; ) {
      // Do the initial checks as cheaply as possible.  If this Frame still has a FrameLayout and
      // the layout hasn't evolved, we're done.
      LayoutOrReplacement layoutOrReplacement = f.layoutOrReplacement;
      if (layoutOrReplacement instanceof FrameLayout) {
        if (!((FrameLayout) layoutOrReplacement).hasEvolved()) {
          return result;
        }
      }
      // If one or the other of those checks failed we need to be more careful.
      layoutOrReplacement = (LayoutOrReplacement) LAYOUT_OR_REPLACEMENT.getAcquire(f);
      if (layoutOrReplacement instanceof FrameLayout) {
        // This must be the same layout we checked earlier; since we didn't take the exit then it
        // must have evolved.
        assert ((FrameLayout) layoutOrReplacement).hasEvolved();
        if (f.isRefCounted()) {
          TState tstate = TState.get();
          if (tstate == null || tstate.tracker() == null) {
            // This thread isn't bound to a ResourceTracker (probably it's just trying to toString()
            // a Value it encountered somewhere), so it's not allowed to modify anything.
            return result;
          }
          return tstate.replace(f);
        } else {
          // The only Frames that aren't refcounted are the empty VArrayLayouts.
          VArrayLayout layout = (VArrayLayout) layoutOrReplacement;
          assert f == layout.empty;
          // We've pre-allocated a non-refcounted Replacement in order to make this simple.
          return ((VArrayLayout) layout.latest()).emptyReplacement;
        }
      }
      result = (Replacement) layoutOrReplacement;
      f = result.newFrame;
    }
  }

  /**
   * If this frame's layout has not evolved, return it; otherwise returns its most recent
   * replacement.
   */
  @RC.Out
  static Frame latest(@RC.In Frame f) {
    Replacement replacement = getReplacement(f);
    if (replacement == null) {
      return f;
    } else {
      Frame result = replacement.newFrame;
      result.addRef();
      TState.staticDropReference(f);
      return result;
    }
  }

  // Value methods

  @Override
  public final FrameLayout layout() {
    LayoutOrReplacement layoutOrReplacement = this.layoutOrReplacement;
    if (layoutOrReplacement instanceof FrameLayout layout) {
      return layout;
    } else {
      // We should have checked for Replacement when this Frame was first loaded, so if it has
      // been replaced since then we haven't synchronized with the Coordinator and this Frame
      // should still be intact.
      Replacement replacement = (Replacement) layoutOrReplacement;
      assert !replacement.isComplete();
      return replacement.evolution.oldLayout;
    }
  }

  @Override
  public final BaseType baseType() {
    return layout().baseType();
  }

  @Override
  public final int numElements() {
    return layout().numElements(this);
  }

  @Override
  @RC.Out
  public final Value element(int i) {
    return layout().element(this, i);
  }

  @Override
  public final Value peekElement(int i) {
    return layout().peekElement(this, i);
  }

  @Override
  @RC.Out
  @RC.In
  public final Value replaceElement(TState tstate, int index, @RC.In Value newElement) {
    return layout().replaceElement(tstate, this, index, newElement);
  }

  // RefCounted methods

  @Override
  final long visitRefs(RefVisitor visitor) {
    return layoutOrReplacement.visitRefs(this, visitor);
  }

  // Object methods

  @Override
  public final String toString() {
    LayoutOrReplacement layoutOrReplacement = this.layoutOrReplacement;
    if (getRefCntForDebugging() != 0 && layoutOrReplacement != null) {
      if (layoutOrReplacement instanceof FrameLayout layout) {
        return layout.toString(this);
      } else {
        return "replaced:" + ((Replacement) layoutOrReplacement).newFrame;
      }
    }
    // Released or not yet initialized
    return getClass().getSimpleName() + "@" + StringUtil.id(this);
  }

  @Override
  public final boolean equals(Object other) {
    return this == other || (other instanceof Value v && layout().equals(this, v));
  }

  @Override
  public int hashCode() {
    // hashCode() is only enabled for the canonical empty VArrays, since they might appear in
    // constant Templates.
    if (layout() instanceof VArrayLayout vlayout && vlayout.empty == this) {
      return Core.EMPTY_ARRAY.hashCode();
    }
    throw new AssertionError(VALUES_ARENT_HASHABLE);
  }

  static class Replacement extends LayoutOrReplacement {

    static final long OBJ_SIZE = SizeOf.object(2 * SizeOf.PTR + SizeOf.INT);

    /**
     * The Evolution that prompted this replacement. Used primarily to recover the original
     * FrameLayout. Set to null after all of the tracker's threads have synced past this
     * replacement's clock and the original Frame has been cleared.
     */
    private Evolution evolution;

    /** The Frame that should be used in place of the original. */
    @RC.Counted final Frame newFrame;

    static final VarHandle EVOLUTION =
        Handle.forVar(MethodHandles.lookup(), Replacement.class, "evolution", Evolution.class);

    Replacement(Evolution evolution, @RC.In Frame newFrame) {
      this.evolution = evolution;
      this.newFrame = newFrame;
    }

    /**
     * True if the original frame has been cleared and all threads are required to use the
     * replacement.
     */
    boolean isComplete() {
      return EVOLUTION.getVolatile(this) == null;
    }

    /**
     * Usually called twice: once from visitRefs when the original frame's refCount goes to zero,
     * and once from Coordinator once all of the ResourceTracker's active threads have synchronized
     * past our clock value. The first call will clear the original Frame; the second call will have
     * no effect.
     */
    void clearOriginal(Frame f, RefVisitor releaseVisitor) {
      Evolution evolution = (Evolution) EVOLUTION.getAndSet(this, null);
      if (evolution != null) {
        evolution.clearOriginal(f, releaseVisitor);
      }
    }

    @Override
    long visitRefs(Frame f, RefVisitor visitor) {
      visitor.visitRefCounted(newFrame);
      if (MemoryHelper.isReleaser(visitor)) {
        // The original frame's refcount has gone to zero.  Clear it unless the Coordinator has
        // already done that.
        clearOriginal(f, visitor);
      } else {
        // There are currently no other implementations of RefVisitor, so it's hard to be sure what
        // the right behavior is here.  Since another thread could be clearing f in parallel with
        // this call we either have to assume that the visitor is OK getting a partially or
        // completely cleared frame, or skip the visit completely.  For now we'll go ahead and visit
        // it, but may want to revisit this choice if it actually starts being used.
        Evolution evolution = this.evolution;
        if (evolution != null) {
          // The original hasn't been cleared yet
          return evolution.oldLayout.visitRefs(f, visitor) + OBJ_SIZE;
        }
      }
      // This frame has now been cleared (either by the code above or before we were called); all
      // that's left is the Frame object itself and the Replacement object.
      // We've lost our pointer to the original FrameClass (it was in evolution.oldLayout), but
      // we can recover it from the Frame class.
      return Frames.CLASSES.get(f.frameClassIndex()).byteSize + OBJ_SIZE;
    }
  }
}
