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
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import org.retrolang.code.*;
import org.retrolang.util.Bits;

/// An Op with optional @RC.In and @RC.Out annotations.
public class RcOp extends Op {

  /// True if each caller of this Op is responsible for decrementing the root count of the Op's
  /// result. See [RC.Out].
  final boolean resultIsRcOut;

  /// If `argIsRcIn` contains `i` then `i < argTypes.size()` and this Op takes responsibility for
  /// (eventually) decrementing root count of argument `i`. Unless the caller has reason to know
  /// that the reference count will still be non-zero, it should not re-use the argument after
  /// invoking the Op. See [RC.In].
  final Bits argIsRcIn;

  /// Returns true if each caller of `op` is responsible for decrementing the root count of the
  /// result. See [RC.Out].
  public static boolean isResultRcOut(Op op) {
    return (op instanceof RcOp rcOp) && rcOp.resultIsRcOut;
  }

  /// Returns a Bits that contains `i` if the corresponding arg of `op` is annotated [RC.In].
  public static Bits isArgRcIn(Op op) {
    return (op instanceof RcOp rcOp) ? rcOp.argIsRcIn : Bits.EMPTY;
  }

  /// A Builder is used to construct a new RcOp.
  public static class RcOpBuilder extends Op.Builder {
    private boolean resultIsRcOut;
    private Bits argIsRcIn = Bits.EMPTY;

    RcOpBuilder(Op op) {
      super(op);
    }

    @CanIgnoreReturnValue
    RcOpBuilder resultIsRcOut() {
      this.resultIsRcOut = true;
      this.hasSideEffect();
      return this;
    }

    @CanIgnoreReturnValue
    RcOpBuilder argIsRcIn(Bits argIsRcIn) {
      this.argIsRcIn = argIsRcIn;
      return this;
    }

    @CanIgnoreReturnValue
    RcOpBuilder argIsRcIn(int index) {
      argIsRcIn = argIsRcIn.set(index);
      return this;
    }

    /// Returns an RcOp if there has been at least one call to [#resultIsRcOut()] or
    /// [#argIsRcIn(int)] or [#argIsRcIn(Bits)] (with a non-empty Bits); otherwise returns an Op.
    @Override
    public Op build() {
      return (!resultIsRcOut && argIsRcIn.isEmpty()) ? super.build() : new RcOp(this);
    }
  }

  private RcOp(RcOpBuilder builder) {
    super(builder);
    this.resultIsRcOut = builder.resultIsRcOut;
    this.argIsRcIn = builder.argIsRcIn;
    assert (resultType != null && !resultType.isPrimitive()) || !resultIsRcOut;
    assert argIsRcIn.stream().noneMatch(i -> argTypes.get(i).isPrimitive());
  }

  /// A version of [Op#SET_OBJ_ARRAY_ELEMENT] with its third argument (the object stored) marked
  /// as @RC.In. Should always be used in preference to [Op#SET_OBJ_ARRAY_ELEMENT] when generating
  /// refCount-aware code.
  static final Op SET_OBJ_ARRAY_ELEMENT =
      new RcOpBuilder(Op.SET_OBJ_ARRAY_ELEMENT).argIsRcIn(2).build();

  /// Returns an RcOpBuilder for the given MethodHandle; caller is expected to call `resultIsRcOut`
  /// and/or `argIsRcIn` on the result before calling `build`.
  public static RcOpBuilder forMethodHandle(String name, MethodHandle mh) {
    return new RcOpBuilder(Op.forMethodHandle(name, mh).build());
  }

  /// Returns an RcOpBuilder for an Op that calls the specified method, which must be public. Sets
  /// `resultIsRcOut` and `argIsRcIn` based on the method's @Rc.Out and @RC.In annotations.
  public static RcOpBuilder forRcMethod(Class<?> klass, String name, Class<?>... argTypes) {
    return forRcMethod(Handle.lookup, klass, name, argTypes);
  }

  /// Returns an RcOpBuilder for an Op that calls the specified method, which must be accessible
  /// with the given Lookup. Sets `resultIsRcOut` and `argIsRcIn` based on the method's @Rc.Out
  /// and @RC.In annotations.
  public static RcOpBuilder forRcMethod(
      MethodHandles.Lookup lookup, Class<?> klass, String name, Class<?>... argTypes) {
    return forRcMethod(lookup, getDeclaredMethod(klass, name, argTypes));
  }

  /// Returns an RcOpBuilder for an Op that calls the specified method, which must be accessible
  /// with the given Lookup. Sets `resultIsRcOut` and `argIsRcIn` based on the method's @Rc.Out
  /// and @RC.In annotations.
  public static RcOpBuilder forRcMethod(MethodHandles.Lookup lookup, Method m) {
    Op op = Op.forMethod(lookup, m).build();
    Parameter[] params = m.getParameters();
    int firstParam = Modifier.isStatic(m.getModifiers()) ? 0 : 1;
    assert firstParam + params.length == op.argTypes.size();
    Bits argIsRcIn =
        Bits.fromPredicate(
            op.argTypes.size() - 1,
            i -> (i >= firstParam && params[i - firstParam].isAnnotationPresent(RC.In.class)));
    boolean resultIsRcOut = m.isAnnotationPresent(RC.Out.class);
    RcOpBuilder builder = new RcOpBuilder(op).argIsRcIn(argIsRcIn);
    if (resultIsRcOut) {
      builder.resultIsRcOut();
    }
    return builder;
  }
}
