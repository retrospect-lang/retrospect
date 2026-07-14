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

/**
 * A RefVisitor is passed to {@link RefCounted#visitRefs}, which should call the visitor with each
 * RefCounted, byte[], or Object[] that the RefCounted has a counted reference to.
 */
public interface RefVisitor {
  /** Called with a RefCounted that is directly reachable from the RefCounted being visited. */
  void visitRefCounted(RefCounted obj);

  /** Called with a byte[] that is directly reachable from the RefCounted being visited. */
  void visitByteArray(byte[] bytes);

  /** Called with an Object[] that is directly reachable from the RefCounted being visited. */
  void visitObjArray(Object[] objs);

  /**
   * A convenience method that calls {@link #visitRefCounted} if {@code obj} is RefCounted, {@link
   * #visitByteArray} if {@code obj} is a byte[], {@link #visitObjArray} if {@code obj} is an
   * Object[], and does nothing if it is none of those.
   */
  default void visit(Object obj) {
    assert !(obj instanceof Value.NotStorable || obj instanceof RValue);
    if (obj instanceof RefCounted rc) {
      visitRefCounted(rc);
    } else if (obj instanceof byte[] bytes) {
      visitByteArray(bytes);
    } else if (obj instanceof Object[] objs) {
      visitObjArray(objs);
    }
  }
}
