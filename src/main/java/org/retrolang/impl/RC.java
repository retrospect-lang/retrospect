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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.function.IntFunction;

/// The annotations in this class are used to enable local reasoning about global properties of
/// reference counts. See ref\_counts.md for details.
public class RC {
  // statics only
  private RC() {}

  /// Indicates that the method takes responsibility for (eventually) decrementing this argument's
  /// root count. Unless the caller has reason to know that the reference count will still be
  /// non-zero, it should not use this argument after the function call.
  ///
  /// When applied to a method, refers to the "this" argument, i.e. the method will decrement the
  /// root count of this and the caller should not use it after the function call.
  ///
  /// When applied to a variable arity parameter (one whose type is followed by "..."), this
  /// annotation indicates that the root count of each actual parameter will be decremented, but not
  /// that of the array containing them (which is usually allocated implicitly by the VM).
  @Target({ElementType.METHOD, ElementType.PARAMETER})
  @Retention(RetentionPolicy.RUNTIME)
  public @interface In {}

  /// Indicates that the function will have incremented the root count of the value returned as its
  /// result, and the caller is responsible for eventually decrementing it.
  @Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Out {}

  /// Indicates that the argument's root count will not be changed by the function, but memory it
  /// references may be modified. Should usually only be called with values that have root count 1.
  @Target({ElementType.METHOD, ElementType.PARAMETER})
  @Retention(RetentionPolicy.RUNTIME)
  public @interface InOut {}

  /// Indicates that the value returned by this function has not had its root count incremented, and
  /// may not be stored by the caller. Transient results are intended to be examined and then
  /// promptly discarded by the caller. The caller must _not_
  ///
  /// - call [Value#addRef] (or any other method that manipulates the reference count) on the
  ///   returned Value;
  /// - pass the returned Value as an `@RC.In` argument to any method; or
  /// - use the returned Value after passing any of the Values that were arguments to this function
  ///   call (including "this", if a Value) as an `@RC.In` argument to any method, or doing anything
  ///   else that might reduce its reference count.
  ///
  /// A Transient result may be converted to a storable, reference-counted Value by calling
  /// [Value#makeStorable].
  @Target({ElementType.METHOD})
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Transient {}

  /// Indicates that a Value-typed function parameter, function result, or local variable will only
  /// refer to singleton values, and so any reference-counting operations on it will be no-ops.
  @Target({
    ElementType.FIELD,
    ElementType.LOCAL_VARIABLE,
    ElementType.PARAMETER,
    ElementType.METHOD,
    ElementType.CONSTRUCTOR
  })
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Singleton {}

  /// When applied to a field, indicates this field is considered a counted reference; it will be
  /// visited by a call to [RefCounted#visitRefs] and is included in the value's visibleCount.
  ///
  /// When applied to a local variable or parameter (of type Object[]), indicates that value is a
  /// counted reference, so changing an element of the array will increment the root count of the
  /// previous element and decrement the root count of the new element.
  @Target({ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.PARAMETER})
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Counted {}

  /// A version of java.util.function.IntFunction whose `apply()` method has been annotated with
  /// [Out], i.e. the caller is responsible for decrementing the root count of each value returned.
  public interface RCIntFunction<T> extends IntFunction<T> {
    @Override
    @RC.Out
    T apply(int i);
  }
}
