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

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.retrolang.impl.TemplateBuilder.CompoundBase;

/**
 * An Evolver implements two operations for updating FrameLayouts:
 *
 * <ul>
 *   <li>expand a FrameLayout to include the values described by a Template, and
 *   <li>merge two FrameLayouts to create a new FrameLayout that includes all their values.
 * </ul>
 *
 * <p>Both operations mark their input FrameLayout(s) as having evolved to their output; the intent
 * is that all RefVars using the previous FrameLayout will, when encountered, be updated to use its
 * replacement instead.
 *
 * <p>Note that any evolution operation can cause cascading evolution of other FrameLayouts (those
 * referenced by RefVars in the original FrameLayout templates). Using a centralized (per-scope)
 * Evolver for all evolution operations gives us an easy way to ensure that we can do that without
 * deadlocks.
 */
class Evolver {

  /** Any FrameLayout that has been evolved will link to an Evolution object. */
  static class Evolution {
    final FrameLayout oldLayout;
    final FrameLayout newLayout;

    /** Implements this Evolution's {@link #replace} method. */
    private final FrameReplacer replacer;

    /**
     * If non-null, both {@link #oldLayout} and {@link #newLayout} are VArrayLayouts and the Frames
     * returned by {@link #replace} will share some of their element arrays with the source Frame.
     * These are the indices in {@link #oldLayout} of the element arrays that are shared, and
     * therefore must not be released when the source Frame is released.
     */
    private final int[] sharedArrayElements;

    Evolution(FrameLayout oldLayout, FrameLayout newLayout) {
      this.oldLayout = oldLayout;
      this.newLayout = newLayout;
      if (oldLayout instanceof RecordLayout) {
        this.sharedArrayElements = null;
        this.replacer = (tstate, src) -> oldLayout.simpleReplace(tstate, src, newLayout);
      } else {
        VArrayReplacer replacer =
            VArrayReplacer.create((VArrayLayout) oldLayout, (VArrayLayout) newLayout);
        this.replacer = replacer;
        this.sharedArrayElements =
            replacer.sharedInSrc.isEmpty() ? null : replacer.sharedInSrc.toArray();
      }
    }

    /**
     * Given a Frame using {@link #oldLayout}, allocates and initializes a Frame using {@link
     * #newLayout} that has the same contents as {@code src}. Should only be called from {@link
     * Coordinator}.
     */
    @RC.Out
    Frame replace(TState tstate, Frame src) {
      // TODO: think about where in here there might be big allocations and add reservations
      // and a clean exit if we run out of memory
      return replacer.copy(tstate, src);
    }

    /**
     * May be used to shortcut the usual frame replacement steps if you know you've got the only
     * pointer to a Frame.
     */
    @RC.Out
    Frame replaceAndDrop(TState tstate, @RC.In Frame src) {
      assert src.layout() == oldLayout && src.isNotShared();
      Frame result = replacer.copy(tstate, src);
      if (sharedArrayElements != null) {
        // Clear any fields in f that were arrays shared by its replacement; then it will be safe to
        // do the usual release.
        ((VArrayLayout) oldLayout).clearElementArrays(src, sharedArrayElements);
      }
      tstate.dropReference(src);
      return result;
    }

    /**
     * Will be called exactly once on each frame that we replaced, to release its pointers *except*
     * for arrays that were shared with its replacement.
     */
    void clearOriginal(Frame f, RefVisitor releaseVisitor) {
      if (sharedArrayElements != null) {
        // Clear any fields in f that were arrays shared by its replacement; then it will be safe to
        // do the usual release.
        ((VArrayLayout) oldLayout).clearElementArrays(f, sharedArrayElements);
      }
      long unused = oldLayout.visitRefs(f, releaseVisitor);
    }
  }

  /**
   * FrameReplacers are used to create replacement frames. The default implementation is {@link
   * FrameLayout#simpleReplace}, but {@link VArrayReplacer} provides a more efficient implementation
   * when replacing varrays.
   */
  interface FrameReplacer {
    @RC.Out
    Frame copy(TState tstate, Frame source);
  }

  /**
   * During an Evolver operation ({@link #merge} or {@link #evolve}) we accumulate a list of
   * in-progress operations, each of which will eventually result in a new or existing FrameLayout.
   *
   * <p>When no Evolver operations are in progress this list will be empty.
   *
   * <p>This list and the {@link FrameLayout#pending} field together implement a {@code
   * Map<FrameLayout,Pending>} for the duration of a top-level operation. This implementation gives
   * very fast lookups and updates at the cost of an extra field in every FrameLayout; eventually we
   * should do some benchmarking to see if this was the right trade-off.
   */
  private final List<Pending> pendings = new ArrayList<>();

  /**
   * If debugging is enabled, we keep all active (non-evolved) FrameLayouts in this table, each
   * mapped to a distinct integer in the order they were first created. Evolved layouts inherit the
   * lowest index of the FrameLayouts that evolved to them. This table is used only to generate a
   * summary of active layouts when the computation completes.
   *
   * <p>Null if debugging is disabled.
   */
  private IdentityHashMap<FrameLayout, Integer> activeLayouts;

  /**
   * If debugging is enabled, the index that will be assigned to the next non-evolved FrameLayout.
   * Not used if debugging is disabled.
   */
  private int nextLayoutIndex;

  /**
   * If {@code enableDebugging()} is called before any FrameLayouts are created, {@link #getSummary}
   * can be called when the computation completes to get a summary of the final FrameLayouts.
   */
  synchronized void enableDebugging() {
    assert activeLayouts == null;
    activeLayouts = new IdentityHashMap<>();
  }

  /**
   * Should be called whenever a new FrameLayout is created from scratch (i.e. not by evolving an
   * existing layout).
   */
  void recordNewLayout(FrameLayout layout) {
    // It's safe to check activeLayouts before acquiring the lock because enableDebugging() will be
    // called before we start creating additional threads.
    if (activeLayouts != null) {
      synchronized (this) {
        Integer prev = activeLayouts.put(layout, nextLayoutIndex++);
        assert prev == null;
      }
    }
  }

  /**
   * Returns a FrameLayout that can store all values that can be stored by {@code x} or {@code y}.
   * The result may be identical to {@code x} or {@code y}. On return {@code x.latest()} and {@code
   * y.latest()} are identical.
   *
   * <p>Requires that {@code x.baseType().sortOrder == y.baseType().sortOrder}.
   */
  @CanIgnoreReturnValue
  synchronized FrameLayout merge(FrameLayout x, FrameLayout y) {
    assert x.baseType().sortOrder == y.baseType().sortOrder;
    assert x.scope == y.scope && x.scope.evolver == this;
    // Since we're now holding the Evolver lock, latest() can't change asynchronously
    x = x.latest();
    y = y.latest();
    if (x == y) {
      return x;
    }
    if (pendings.isEmpty()) {
      FrameLayout yFinal = y;
      return doTopLevel(x, pending -> pending.merge(yFinal));
    }
    // If this is a re-entrant call (i.e. a merge triggered by an in-progress merge() or evolve()
    // call) we might already have started to evolve x and/or y.
    Pending xPending = x.pending();
    if (xPending != null) {
      xPending.merge(y);
    } else {
      getPending(y).merge(x);
    }
    // We don't yet know what the merged FrameLayout will be, but when the top-level operation
    // completes x will have been evolved to it.
    return x;
  }

  /**
   * Evolves the given FrameLayout to be able to store all values that can be stored by {@code
   * newTemplate} (if {@code isVarrayElement} is false) or to be a Varray that can store those
   * values as elements (if {@code isVarrayElement} is true).
   *
   * <p>Requires that {@code layout.baseType().sortOrder == (isVarrayElement ? SORT_ORDER_ARRAY :
   * newTemplate.baseType().sortOrder)}.
   */
  @CanIgnoreReturnValue
  synchronized FrameLayout evolve(
      FrameLayout layout, TemplateBuilder newTemplate, boolean isVarrayElement) {
    assert layout.scope.evolver == this;
    layout = layout.latest();
    if (pendings.isEmpty()) {
      return doTopLevel(layout, pending -> pending.evolve(newTemplate, isVarrayElement));
    }
    getPending(layout).evolve(newTemplate, isVarrayElement);
    return layout;
  }

  /**
   * Returns true if {@code older.latest()} and {@code newer.latest()} are identical, after ensuring
   * that any evolver operations started by other threads have completed. Only used for assertions.
   */
  synchronized boolean evolvedTo(FrameLayout older, FrameLayout newer) {
    return older.latest() == newer.latest();
  }

  /**
   * Should only be called if {@link #enableDebugging} was called on this Evolver; returns a
   * description of the active layouts.
   */
  synchronized String getSummary() {
    // Copy all the still-active layouts into a list and sort by index.
    List<Map.Entry<FrameLayout, Integer>> actives = new ArrayList<>(activeLayouts.entrySet());
    Collections.sort(actives, Map.Entry.comparingByValue());
    // Each active layout will be identified as "$" followed by its position in the sorted list.
    IdentityHashMap<FrameLayout, String> ids = new IdentityHashMap<>();
    for (int i = 0; i < actives.size(); i++) {
      ids.put(actives.get(i).getKey(), "$" + i);
    }
    Template.Printer printer =
        new Template.Printer() {
          @Override
          public String toString(Template.RefVar rv) {
            if (rv instanceof Template.RefVar.ForFrame ff) {
              return String.format("x%d:%s", rv.index, ids.get(ff.frameLayout()));
            }
            return super.toString(rv);
          }
        };
    // Return "$0 = ...\n$1 = ..." etc.
    return IntStream.range(0, actives.size())
        .mapToObj(i -> "$" + i + " = " + actives.get(i).getKey().toString(printer))
        .collect(Collectors.joining("\n"));
  }

  /**
   * Performs a top-level evolver operation by creating a new Pending for {@code start} and applying
   * {@code update} to it. Returns the resulting layout after all operations have completed.
   */
  private FrameLayout doTopLevel(FrameLayout start, Consumer<Pending> update) {
    assert start.pending() == null && Thread.holdsLock(this);
    Scope scope = start.scope;
    try {
      update.accept(getPending(start));
      for (Pending pending : pendings) {
        pending.finish(scope, this);
      }
      pendings.clear();
      return start.latest();
    } finally {
      if (!pendings.isEmpty()) {
        // Something went wrong and we're throwing an exception.  Clean up all our in-progress
        // state so that any subsequent operations have a chance.
        for (Pending pending : pendings) {
          for (FrameLayout layout : pending.layouts) {
            if (layout.pending() != null) {
              layout.setPending(null, pending);
            }
          }
        }
        pendings.clear();
      }
    }
  }

  /** Returns the Pending for the given layout; creates a new one if necessary. */
  private Pending getPending(FrameLayout layout) {
    Pending result = layout.pending();
    if (result == null) {
      result = new Pending(layout);
      pendings.add(result);
    }
    return result;
  }

  /**
   * Each Pending represents a set of one or more FrameLayouts that will evolve to a common layout
   * when the top-level operation completes (either a new one, or a member of the set).
   *
   * <p>A Pending is only used for the duration of a single top-level operation, after which it is
   * dropped (when the Evolver's {@link #pendings} list is cleared).
   */
  static class Pending {

    /**
     * The FrameLayouts that this Pending will update.
     *
     * <p>All of these layouts have their {@link FrameLayout#pending} field set to this.
     */
    final List<FrameLayout> layouts = new ArrayList<>();

    /**
     * The template for a FrameLayout that includes all of the layouts in {@link #layouts} combined
     * with any calls to {@link #evolve}.
     *
     * <p>If {@link #isVarray} is true, the resulting layout will be a VArrayLayout and this is a
     * builder for the element; otherwise the resulting layout will be a RecordLayout using this
     * builder.
     */
    private TemplateBuilder builder;

    /** See {@link #builder} for the interpretation of this field. */
    private boolean isVarray;

    /** Creates a new Pending containing just the given layout. */
    Pending(FrameLayout layout) {
      layout.setPending(this, null);
      layouts.add(layout);
      builder = layout.template().toBuilder();
      isVarray = layout instanceof VArrayLayout;
    }

    /** Expands this Pending to include the given layout. */
    void merge(FrameLayout layout) {
      Pending other = layout.pending();
      if (other == null) {
        // The new layout isn't yet associated with any Pending, so just add it to this one and
        // update our template accordingly.
        layout.setPending(this, null);
        layouts.add(layout);
        evolve(layout.template().toBuilder(), layout instanceof VArrayLayout);
      } else if (other != this) {
        // We need to merge two Pendings; just transfer all of the other Pending's layouts to this
        // Pending, and merge its template with ours.
        for (FrameLayout otherLayout : other.layouts) {
          otherLayout.setPending(this, other);
        }
        layouts.addAll(other.layouts);
        other.layouts.clear();
        evolve(other.builder, other.isVarray);
      }
    }

    /** Expands this Pending to include the given template. */
    void evolve(TemplateBuilder template, boolean isVarrayElement) {
      assert isVarrayElement || template instanceof CompoundBase;
      if (!isVarray && !isVarrayElement && template.baseType() == builder.baseType()) {
        // It's a record, and it's staying a record (at least for now)
        builder = builder.merge(template);
        return;
      }
      if (!isVarray) {
        // It was a record but we're merging it with a varray or a record with a different
        // length, so we need to convert it to a varray.
        builder = ((CompoundBase) builder).mergeElementsInto(Template.EMPTY);
        isVarray = true;
      }
      if (isVarrayElement) {
        // Merging a varray into a varray
        builder = builder.merge(template);
      } else {
        // Merging a record into a varray
        builder = ((CompoundBase) template).mergeElementsInto(builder);
      }
    }

    /**
     * Called once on each Pending after the top-level operation completes; determines the final
     * FrameLayout and marks each of this Pending's FrameLayouts as having evolved to it.
     */
    void finish(Scope scope, Evolver evolver) {
      assert Thread.holdsLock(evolver);
      FrameLayout newLayout = mergedLayout(scope);
      // minIndex is the least index of any of the input layouts, not counting the one we're
      // keeping (or MAX_VALUE if debugging is disabled)
      int minIndex = Integer.MAX_VALUE;
      // If newLayout is one of the input layouts, this is its index; MAX_VALUE if debugging is
      // disabled or newLayout is a newly-created layout.
      int oldIndex = Integer.MAX_VALUE;
      for (FrameLayout layout : layouts) {
        layout.setPending(null, this);
        if (layout != newLayout) {
          layout.setEvolution(newLayout);
        }
        if (evolver.activeLayouts != null) {
          if (layout == newLayout) {
            oldIndex = evolver.activeLayouts.get(layout);
          } else {
            minIndex = Math.min(minIndex, evolver.activeLayouts.remove(layout));
          }
        }
      }
      if (oldIndex > minIndex) {
        // We merged a lower-indexed layout into the one we kept, or we made a new one.
        Integer prev = evolver.activeLayouts.put(newLayout, minIndex);
        assert Objects.equals(prev, oldIndex == Integer.MAX_VALUE ? null : oldIndex);
      }
    }

    /**
     * Determines the final FrameLayout for layouts in this Pending. First checks to see if any of
     * our layouts will do; if not, creates a new one.
     */
    private FrameLayout mergedLayout(Scope scope) {
      // First (quick) check: if our builder is still a Template, it's likely that one
      // of our layouts is an exact match and can just be returned.
      if (builder instanceof Template) {
        for (FrameLayout layout : layouts) {
          if (isVarray == (layout instanceof VArrayLayout) && layout.template() == builder) {
            return layout;
          }
        }
      }
      // Build the new layout's template using the appropriate VarAllocator
      TemplateBuilder.VarAllocator allocator =
          isVarray
              ? new VArrayLayout.VarAllocator()
              : RecordLayout.VarAllocator.newForRecordLayout().setDropToBeSetFromUnions();
      Template template = builder.build(allocator);
      // Second check: do any of our layouts match the template we built?
      for (FrameLayout layout : layouts) {
        if (isVarray == (layout instanceof VArrayLayout) && template.equals(layout.template())) {
          return layout;
        }
      }
      // We need to create a new FrameLayout.
      return isVarray
          ? VArrayLayout.newWithAllocator(scope, template, (VArrayLayout.VarAllocator) allocator)
          : RecordLayout.newWithAllocator(
              scope, (Template.Compound) template, (RecordLayout.VarAllocator) allocator);
    }
  }
}
