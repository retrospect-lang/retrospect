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
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.util.Arrays;
import java.util.stream.IntStream;
import org.retrolang.util.StringUtil;

/**
 * A ValueMemo is called repeatedly with Value tuples of a fixed size (e.g. the arguments each time
 * a function is called, or the results returned by each call to a function). It keeps information
 * about the structure of each Value it has seen, and coerces Values into consistent
 * representations.
 *
 * <p>For example, if the first value of the tuple always has the same BaseType but uses different
 * FrameLayouts, the ValueMemo will merge those layouts. Alternatively, if the first value is
 * sometimes a fixed-length array of length 2 and sometimes a fixed-length array of length 3, the
 * ValueMemo will create a VArray layout that can hold all of the elements it has seen and coerce
 * all arrays to that layout (expanding the layout as needed to so that it can represent the given
 * values).
 *
 * <p>A ValueMemo of size n is implemented by maintaining n TemplateBuilders, and updates the
 * corresponding value and/or TemplateBuilder so that the TemplateBuilder includes the value (a
 * process referred to as "harmonization").
 *
 * <p>A ValueMemo may be merged into another ValueMemo, after which the two ValueMemos are
 * equivalent and can be used interchangeably.
 */
public abstract class ValueMemo implements ResultsInfo {

  /**
   * A arbitrary threshold for when we think compile-time values will have become too complex and we
   * should introduce a Frame.
   */
  static final int TARGET_FRAME_WEIGHT = 8;

  /**
   * Non-null if this ValueMemo has been merged into another; all operations will be redirected to
   * {@code forwardedTo}.
   */
  @GuardedBy("this")
  ValueMemo forwardedTo;

  /**
   * True if this ValueMemo should only allow modifications when the caller is holding an extra lock
   * in addition to the ValueMemo's own lock.
   *
   * <p>Used to ensure that the {@link MethodMemo#argsMemo} for a heavy or exlined MethodMemo is
   * only modified while also holding the MemoMerger lock.
   */
  @GuardedBy("this")
  boolean writeRequiresExtraLock;

  private static final ValueMemo EMPTY = new MultiValueMemo(0);

  public abstract int size();

  @GuardedBy("this")
  abstract TemplateBuilder getBuilder(int i);

  @GuardedBy("this")
  abstract void setBuilder(int i, TemplateBuilder templateBuilder);

  /** Returns a new ValueMemo for the specified number of values. */
  static ValueMemo withSize(int size) {
    if (size == 0) {
      return EMPTY;
    } else if (size == 1) {
      return new SingleValueMemo();
    } else {
      return new MultiValueMemo(size);
    }
  }

  /** A subclass of ValueMemo used only for the (common) case of size == 1. */
  private static class SingleValueMemo extends ValueMemo {
    @GuardedBy("this")
    private TemplateBuilder contents;

    SingleValueMemo() {
      contents = Template.EMPTY;
    }

    @Override
    public int size() {
      return 1;
    }

    @Override
    @GuardedBy("this")
    TemplateBuilder getBuilder(int i) {
      assert i == 0;
      return contents;
    }

    @Override
    @GuardedBy("this")
    void setBuilder(int i, TemplateBuilder templateBuilder) {
      assert i == 0;
      contents = templateBuilder;
    }

    @Override
    public synchronized String toString() {
      return forwardedTo != null ? "(forwarded)" : "⟨" + contents + "⟩";
    }
  }

  /** A subclass of ValueMemo used when size != 1. */
  private static class MultiValueMemo extends ValueMemo {
    @GuardedBy("this")
    private final TemplateBuilder[] contents;

    MultiValueMemo(int size) {
      contents = new TemplateBuilder[size];
      Arrays.fill(contents, Template.EMPTY);
    }

    @Override
    @SuppressWarnings("GuardedBy") // checker thinks I need a lock to get the length of a final
    public int size() {
      return contents.length;
    }

    @Override
    @GuardedBy("this")
    TemplateBuilder getBuilder(int i) {
      return contents[i];
    }

    @Override
    @GuardedBy("this")
    void setBuilder(int i, TemplateBuilder templateBuilder) {
      contents[i] = templateBuilder;
    }

    @Override
    public synchronized String toString() {
      return forwardedTo != null
          ? "(forwarded)"
          : StringUtil.joinElements("⟨", "⟩", contents.length, i -> contents[i]);
    }
  }

  @Override
  public <T> T result(int resultNum, TProperty<T> property) {
    if (property == TProperty.IS_DISCARDED) {
      // We don't need to go through the whole synchronization dance for a function that ignores its
      // argument anyway.
      return property.fn.apply(null);
    }
    // The for / synchronized / update-vMemo-and-continue dance ensures that we do the right thing
    // if this ValueMemo has been merged into another.
    ValueMemo vMemo = this;
    for (; ; ) {
      synchronized (vMemo) {
        if (vMemo.forwardedTo != null) {
          vMemo = vMemo.forwardedTo;
          continue;
        }
        return property.fn.apply(vMemo.getBuilder(resultNum));
      }
    }
  }

  /**
   * Sets this ValueMemo so that its state can only be changed by calls to {@link #harmonizeAll}
   * with {@code haveExtraLock} true.
   */
  synchronized void setWriteRequiresExtraLock(boolean writeRequiresExtraLock) {
    assert forwardedTo == null;
    this.writeRequiresExtraLock = writeRequiresExtraLock;
  }

  /**
   * Coerces the given value and/or updates this ValueMemo to be consistent. Should not be called on
   * ValueMemos where {@link #writeRequiresExtraLock} is true.
   */
  @RC.Out
  Value harmonize(TState tstate, int index, @RC.In Value v) {
    assert RefCounted.isValidForStore(v);
    ValueMemo vMemo = this;
    for (; ; ) {
      synchronized (vMemo) {
        if (vMemo.forwardedTo != null) {
          vMemo = vMemo.forwardedTo;
          continue;
        }
        assert !vMemo.writeRequiresExtraLock;
        TemplateBuilder builder = vMemo.getBuilder(index);
        Value harmonized = builder.cast(tstate, v);
        if (harmonized == null) {
          harmonized = vMemo.updateBuilder(tstate, index, builder, v);
          tstate.methodMemoUpdated = true;
        }
        tstate.dropValue(v);
        return harmonized;
      }
    }
  }

  /**
   * Updates one of our TemplateBuilders to include the given value, and then casts the value with
   * the resulting TemplateBuilder.
   */
  @GuardedBy("this")
  @RC.Out
  private Value updateBuilder(TState tstate, int index, TemplateBuilder builder, Value v) {
    assert forwardedTo == null && builder == getBuilder(index);
    builder = builder.add(v);
    if (builder.totalWeight() > 2 * TARGET_FRAME_WEIGHT) {
      // This builder is getting big enough that we should introduce a Frame for part of it.
      builder = builder.insertFrameLayout(TARGET_FRAME_WEIGHT);
    }
    setBuilder(index, builder);
    Value result = builder.cast(tstate, v);
    assert result != null;
    return result;
  }

  /**
   * The result of {@link #harmonizeAll}, one of
   *
   * <ul>
   *   <li>NO_CHANGE_REQUIRED: the values could all be harmonized with no change to the ValueMemo;
   *   <li>CHANGED: the ValueMemo was updated; either {@link #writeRequiresExtraLock} is false or
   *       the {@code haveExtraLock} parameter was true; or
   *   <li>CHANGE_REQUIRES_EXTRA_LOCK: harmonizing at least one of the values would require an
   *       update to this ValueMemo but {@link #writeRequiresExtraLock} is true and {@code
   *       haveExtraLock} was false (note that in this case some of the values may have already been
   *       harmonized).
   * </ul>
   */
  enum Outcome {
    NO_CHANGE_REQUIRED,
    CHANGED,
    CHANGE_REQUIRES_EXTRA_LOCK
  };

  /**
   * Coerces the given values and/or updates this ValueMemo to be consistent. Reads and writes the
   * first {@link #size} elements of {@code values}.
   */
  @CanIgnoreReturnValue
  Outcome harmonizeAll(TState tstate, Object[] values, boolean haveExtraLock) {
    ValueMemo vMemo = this;
    for (; ; ) {
      synchronized (vMemo) {
        if (vMemo.forwardedTo != null) {
          vMemo = vMemo.forwardedTo;
          continue;
        }
        int size = vMemo.size();
        assert values.length >= size;
        Outcome outcome = Outcome.NO_CHANGE_REQUIRED;
        for (int i = 0; i < size; i++) {
          Value v = (Value) values[i];
          TemplateBuilder builder = vMemo.getBuilder(i);
          Value harmonized = builder.cast(tstate, v);
          if (harmonized == null) {
            if (vMemo.writeRequiresExtraLock && !haveExtraLock) {
              return Outcome.CHANGE_REQUIRES_EXTRA_LOCK;
            }
            outcome = Outcome.CHANGED;
            tstate.methodMemoUpdated = true;
            harmonized = vMemo.updateBuilder(tstate, i, builder, v);
          }
          values[i] = harmonized;
          tstate.dropValue(v);
        }
        return outcome;
      }
    }
  }

  /**
   * Returns true if a call to {@link #harmonizeAll} with the given values would return
   * NO_CHANGE_REQUIRED.
   */
  boolean couldCast(Object[] values) {
    ValueMemo vMemoMutable = this;
    for (; ; ) {
      final ValueMemo vMemo = vMemoMutable;
      synchronized (vMemo) {
        if (vMemo.forwardedTo != null) {
          vMemoMutable = vMemo.forwardedTo;
          continue;
        }
        return IntStream.range(0, size())
            .allMatch(i -> vMemo.getBuilder(i).couldCast((Value) values[i]));
      }
    }
  }

  /**
   * Used by {@link MethodMemo#isSimilarEnough} to check for similarities between the args of two
   * MethodMemos. See the comment on {@link TemplateBuilder#overlaps} for more context.
   */
  boolean overlaps(ValueMemo other) {
    ValueMemo vm1 = this;
    for (; ; ) {
      synchronized (vm1) {
        if (vm1.forwardedTo != null) {
          vm1 = vm1.forwardedTo;
          continue;
        }
        ValueMemo vm2 = other;
        while (vm2 != vm1) {
          synchronized (vm2) {
            if (vm2.forwardedTo != null) {
              vm2 = vm2.forwardedTo;
              continue;
            }
            int size = vm1.size();
            assert vm2.size() == size;
            for (int i = 0; i < size; i++) {
              if (!vm1.getBuilder(i).overlaps(vm2.getBuilder(i))) {
                return false;
              }
            }
            return true;
          }
        }
        return true;
      }
    }
  }

  /**
   * Merge this ValueMemo into {@code other}; after this call the two ValueMemos are equivalent and
   * can be used interchangeably.
   *
   * <p>Note that concurrent calls to this method with the same ValueMemos may deadlock; the caller
   * is responsible for not doing that.
   */
  void mergeInto(ValueMemo other) {
    ValueMemo vm1 = this;
    for (; ; ) {
      synchronized (vm1) {
        if (vm1.forwardedTo != null) {
          vm1 = vm1.forwardedTo;
          continue;
        }
        ValueMemo vm2 = other;
        while (vm2 != vm1) {
          synchronized (vm2) {
            if (vm2.forwardedTo != null) {
              vm2 = vm2.forwardedTo;
              continue;
            }
            int size = vm1.size();
            assert vm2.size() == size;
            for (int i = 0; i < size; i++) {
              vm2.setBuilder(i, vm2.getBuilder(i).merge(vm1.getBuilder(i)));
            }
            vm1.forwardedTo = vm2;
            return;
          }
        }
        // vm1 == vm2 (probably one had already been forwarded to the other): nothing to do
        return;
      }
    }
  }
}
