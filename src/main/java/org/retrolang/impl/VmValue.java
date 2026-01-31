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

import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import org.retrolang.Vm;

/**
 * Implements Vm.Value.
 *
 * <p>A VmValue is a wrapper around a refCounted Value (non-refcounted Values are handled by VmExpr,
 * which also implements Vm.Value). The {@link #close} method drops the wrapped Value and nulls it.
 */
public final class VmValue implements Vm.Value {
  /**
   * The {@code value} is both a {@link Value} and a {@link RefCounted}. Null if {@link #close} has
   * been called.
   */
  @RC.Counted private RefCounted value;

  /** The ResourceTracker that allocated {@link #value} and should be notified when it is closed. */
  final ResourceTracker tracker;

  /**
   * If false, the first use of this VmValue (via {@link asValue}) will close it and drop the
   * underlying Value. If true the value will be kept until {@link close} is called.
   */
  private boolean keep;

  /**
   * Wraps the given Value as a {@link Vm.Value}. If {@code value} is refCounted, it should have
   * been allocated by the given ResourceTracker and will be wrapped with a VmValue; otherwise it
   * will be wrapped as a {@link VmExpr.Constant} and {@code tracker} is ignored.
   */
  static Vm.Value of(@RC.In Value value, ResourceTracker tracker) {
    if (RefCounted.isRefCounted(value)) {
      Preconditions.checkArgument(tracker != null);
      return new VmValue((RefCounted) value, tracker);
    } else {
      return VmExpr.Constant.of(value);
    }
  }

  private VmValue(@RC.In RefCounted value, ResourceTracker tracker) {
    this.value = value;
    this.tracker = tracker;
  }

  /**
   * Unwraps the result of a previous call to {@link #of}. If {@code v} is a {@link
   * VmExpr.Constant}, the unwrapping is trivial. Otherwise:
   *
   * <ul>
   *   <li>It is an error if the VmValue has already been closed.
   *   <li>If {@code mustMatch} is non-null, it must match the ResourceTracker that was passed to
   *       {@link #of}.
   *   <li>If {@link #keep} has not been called on this VmValue, it is implicitly closed.
   * </ul>
   */
  @RC.Out
  static Value asValue(Vm.Value v, ResourceTracker mustMatch) {
    if (v instanceof VmExpr.Constant c) {
      return c.value;
    }
    VmValue vmValue = (VmValue) v;
    Preconditions.checkArgument(mustMatch == null || mustMatch == vmValue.tracker);
    RefCounted value;
    synchronized (vmValue) {
      value = vmValue.value;
      if (value instanceof Frame) {
        value = Frame.latest((Frame) value);
      }
      vmValue.value = vmValue.keep ? value : null;
    }
    Preconditions.checkArgument(value != null, "Already closed.");
    if (vmValue.keep) {
      value.addRef();
    }
    return (Value) value;
  }

  @CanIgnoreReturnValue
  @Override
  public synchronized VmValue keep() {
    Preconditions.checkState(value != null, "Already closed.");
    keep = true;
    return this;
  }

  @Override
  public void close() {
    RefCounted value;
    synchronized (this) {
      value = this.value;
      this.value = null;
    }
    if (value != null) {
      TState.dropReferenceWithTracker(tracker, value);
    }
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    } else if (other instanceof VmValue v) {
      return value.equals(v.value);
    } else if (other instanceof VmExpr.Constant c) {
      return value.equals(c.value);
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    if (value == null) {
      return "*closed*";
    } else {
      TState tstate = TState.getOrCreate();
      ResourceTracker prev = tstate.bindTo(tracker);
      try {
        return value.toString();
      } finally {
        tstate.bindTo(prev);
      }
    }
  }
}
