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

package org.retrolang.code;

import com.google.common.base.Preconditions;
import java.util.function.IntFunction;
import org.retrolang.code.CodeBuilder.OpCodeType;
import org.retrolang.util.Bits;

/// A Register represents an entry in the local variable array of the JVM when running the code
/// being built. (When executing byte codes the JVM maintains an array of "local variable values",
/// each of which may be a pointer, an int, a long, or a double. Byte codes may load or store values
/// in that array; a Register represents one slot in it.)
///
/// Register also implements ValueInfo; when used as a ValueInfo, it indicates that one register
/// value has been copied to another.
///
/// (Note that most of the ValueInfo methods just throw exceptions; the caller should instead call
/// those methods on this register's info.)
public class Register extends CodeValue implements ValueInfo {

  /// Registers are assigned indices sequentially increasing from zero in the order they are
  /// created. Since arguments must be created before any other registers, they will have indices
  /// zero through `cb.numArgs()-1`.
  ///
  /// Unlike Blocks (which may be renumbered), Register indices never change and are never reused.
  public final int index;

  private Class<?> type;
  private int jvmLocal = -1;

  Register(int index, Class<?> type) {
    // The JVM doesn't support boolean, byte, short, or char local variables.
    Preconditions.checkArgument(type == int.class || !OpCodeType.isStoredAsInt(type));
    this.index = index;
    this.type = type;
  }

  /// Modifies the type of this register to a supertype of its current type. The type of a primitive
  /// register cannot be modified.
  void setType(Class<?> newType) {
    assert newType.isAssignableFrom(type);
    this.type = newType;
  }

  /// Sets the index of this register in the JVM's local array. Should only be called from
  /// RegisterAssigner.
  void setJvmLocal(int i) {
    this.jvmLocal = i;
  }

  /// Returns true if [#setJvmLocal] has been called.
  public boolean hasJvmLocal() {
    return jvmLocal >= 0;
  }

  /// Returns the index of this register in the JVM's local array. [#setJvmLocal] must have been
  /// called previously.
  public int jvmLocal() {
    assert hasJvmLocal();
    return jvmLocal;
  }

  /// Returns the OpCodeType for this register.
  OpCodeType opCodeType() {
    return OpCodeType.forType(type);
  }

  @Override
  public Class<?> type() {
    return type;
  }

  @Override
  public void push(Emitter emitter, Class<?> type) {
    emitter.push(this, type);
  }

  @Override
  public ValueInfo info(IntFunction<ValueInfo> registerInfo) {
    ValueInfo result = registerInfo.apply(index);
    assert result != null && !(result instanceof Register);
    return result;
  }

  @Override
  public CodeValue simplify(IntFunction<ValueInfo> registerInfo) {
    Register r = this;
    ValueInfo info = registerInfo.apply(index);
    if (info instanceof Register) {
      r = (Register) info;
      info = registerInfo.apply(r.index);
      // We don't allow copy-of-register chains.
      assert !(info instanceof Register);
    }
    return (info instanceof Const) ? ((Const) info) : r;
  }

  @Override
  public void getLive(boolean add, Bits.Builder live) {
    if (add) {
      live.set(index);
    } else {
      live.clear(index);
    }
  }

  @Override
  public boolean containsValue(Object value) {
    // You need to call this method on the source register's info.
    throw new AssertionError();
  }

  @Override
  public ValueInfo unionConst(Const constInfo) {
    // You need to call this method on the source register's info.
    throw new AssertionError();
  }

  @Override
  public ValueInfo removeConst(Const constInfo) {
    // You need to call this method on the source register's info.
    throw new AssertionError();
  }

  @Override
  public String toString(CodeBuilder.PrintOptions options) {
    return options.useJvmLocals() ? opCodeType().upperPrefix + jvmLocal : toString();
  }

  @Override
  public String toString() {
    if (jvmLocal < 0 || jvmLocal == index) {
      return opCodeType().prefix + index;
    } else {
      return opCodeType().prefix + jvmLocal + "/" + index;
    }
  }
}
