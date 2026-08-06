package org.retrolang.impl;

import com.google.common.collect.ImmutableSet;
import java.util.function.Consumer;
import org.retrolang.code.CodeValue;
import org.retrolang.code.FutureBlock;
import org.retrolang.code.Op;
import org.retrolang.code.TestBlock;
import org.retrolang.util.Canonical;

/// A [MethodMemo.Extension] that saves a set of arbitrary objects.
public record SetMemoExtension(ImmutableSet<Object> objs) implements MethodMemo.Extension {

  /// Adds `obj` to a SetMemoExtension stored in `mMemo`.
  public static void add(TState tstate, Object obj, MethodMemo.Extended mMemo) {
    SetMemoExtension prev = (SetMemoExtension) mMemo.getExtension();
    SetMemoExtension afterAdd;
    if (prev == null) {
      afterAdd = new SetMemoExtension(ImmutableSet.of(obj));
    } else if (prev.objs.contains(obj)) {
      return;
    } else {
      ImmutableSet.Builder<Object> builder =
          ImmutableSet.builderWithExpectedSize(prev.objs.size() + 1);
      addAll(builder, prev.objs);
      afterAdd = new SetMemoExtension(builder.add(obj).build());
    }
    mMemo.setExtension(tstate, afterAdd, prev);
  }

  /// If `element` is in `mMemo`'s SetMemoExtension, call `consumer` with it; otherwise escape.
  public static <T> void find(
      Class<T> klass,
      CodeGen codeGen,
      MethodMemo.Extended mMemo,
      CodeValue element,
      Consumer<T> consumer) {
    if (element instanceof CodeValue.Const c) {
      consumer.accept(klass.cast(c.value));
      return;
    }
    SetMemoExtension set = (SetMemoExtension) mMemo.getExtension();
    if (set == null) {
      codeGen.escape();
      return;
    }
    element = codeGen.materialize(element, klass);
    FutureBlock done = new FutureBlock();
    for (Object obj : set.objs) {
      T asT = klass.cast(obj);
      FutureBlock next = new FutureBlock();
      CodeValue isEquivalent = Op.EQUAL.result(CodeValue.of(asT), element);
      TestBlock.isFalse(isEquivalent).setBranch(true, next).addTo(codeGen.cb);
      consumer.accept(asT);
      codeGen.cb.branchTo(done);
      codeGen.cb.setNext(next);
    }
    codeGen.escape();
    codeGen.cb.setNext(done);
  }

  /// Returns a SetMemoExtension that is the union of `this` and `other`.
  @Override
  public SetMemoExtension combine(MethodMemo.Extension other) {
    SetMemoExtension larger = this;
    SetMemoExtension smaller = (SetMemoExtension) other;
    if (smaller.objs.size() > larger.objs.size()) {
      larger = smaller;
      smaller = this;
    }
    // Wait to construct the builder until we find an element of `smaller` that's not already
    // in `larger`.
    ImmutableSet.Builder<Object> builder = null;
    int alreadySeen = 0;
    for (Object fromSmaller : smaller.objs) {
      fromSmaller = canonicalize(fromSmaller);
      if (builder == null) {
        if (larger.objs.contains(fromSmaller)) {
          ++alreadySeen;
          continue;
        }
        builder =
            ImmutableSet.builderWithExpectedSize(
                larger.objs.size() + smaller.objs.size() - alreadySeen);
        addAll(builder, larger.objs);
      }
      builder.add(fromSmaller);
    }
    return (builder == null) ? larger : new SetMemoExtension(builder.build());
  }

  /// If `obj` is a [Canonical], return the result of [Canonical#getCanonical]; otherwise just
  /// return `obj`.
  private static Object canonicalize(Object obj) {
    return (obj instanceof Canonical c) ? c.getCanonical() : obj;
  }

  /// Adds each element of `set` to `builder`. If the elements are instances of Canonical, also
  /// takes the opportunity to canonicalize each of them in the process.
  private static void addAll(ImmutableSet.Builder<Object> builder, ImmutableSet<Object> set) {
    set.forEach(obj -> builder.add(canonicalize(obj)));
  }

  @Override
  public String toString() {
    return objs.toString();
  }
}
