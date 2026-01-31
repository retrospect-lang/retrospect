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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.retrolang.code.Op;

/** A static utility class with convenience methods for creating MethodHandles and VarHandles. */
class Handle {

  static final MethodHandles.Lookup lookup = MethodHandles.lookup();

  private Handle() {}

  /** Returns a MethodHandle for the specified constructor. */
  static MethodHandle forConstructor(Class<?> klass, Class<?>... argTypes) {
    try {
      return lookup.unreflectConstructor(klass.getDeclaredConstructor(argTypes));
    } catch (ReflectiveOperationException e) {
      throw new LinkageError("Shouldn't happen", e);
    }
  }

  /** Returns a MethodHandle for the specified method. */
  static MethodHandle forMethod(Class<?> klass, String name, Class<?>... argTypes) {
    return forMethod(lookup, klass, name, argTypes);
  }

  /** Returns a MethodHandle for the specified method. */
  static MethodHandle forMethod(
      MethodHandles.Lookup lookup, Class<?> klass, String name, Class<?>... argTypes) {
    try {
      return lookup.unreflect(klass.getDeclaredMethod(name, argTypes));
    } catch (ReflectiveOperationException e) {
      throw new LinkageError("Shouldn't happen", e);
    }
  }

  /** Returns a MethodHandle for the specified method. */
  static MethodHandle forMethod(Method m) {
    try {
      return lookup.unreflect(m);
    } catch (ReflectiveOperationException e) {
      throw new LinkageError("Shouldn't happen", e);
    }
  }

  /** Returns an {@link Op.Builder} for the specified method. */
  static Op.Builder opForMethod(Class<?> klass, String name, Class<?>... argTypes) {
    return Op.forMethod(lookup, klass, name, argTypes);
  }

  /** Returns a VarHandle for the specified field. */
  static VarHandle forVar(Class<?> klass, String name, Class<?> type) {
    return forVar(lookup, klass, name, type);
  }

  /** Returns a VarHandle for the specified field. */
  static VarHandle forVar(MethodHandles.Lookup lookup, Class<?> klass, String name, Class<?> type) {
    try {
      return lookup.findVarHandle(klass, name, type);
    } catch (ReflectiveOperationException e) {
      throw new LinkageError("Shouldn't happen", e);
    }
  }

  /** Returns a VarHandle for the specified field. */
  static VarHandle forVar(Field field) {
    try {
      return lookup.unreflectVarHandle(field);
    } catch (ReflectiveOperationException e) {
      throw new LinkageError("Shouldn't happen", e);
    }
  }

  /** Returns {@code c.simpleName()}, but with the names of any containing classes removed. */
  static String simpleName(Class<?> c) {
    String result = c.getSimpleName();
    int lastDollar = result.lastIndexOf("$");
    return (lastDollar >= 0) ? result.substring(lastDollar + 1) : result;
  }
}
