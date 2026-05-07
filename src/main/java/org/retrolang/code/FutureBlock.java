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

/**
 * A FutureBlock is used as a placeholder for a not-yet-constructed block; when the block becomes
 * available, {@link LinkTarget#moveAllInLinks} is used to redirect the links from the FutureBlock.
 */
public final class FutureBlock extends LinkTarget {
  /** Not used by any code in this package; may be useful for storing debugging information. */
  public final Object tag;

  public FutureBlock(Object tag) {
    this.tag = tag;
  }

  public FutureBlock() {
    this(null);
  }

  static Object tag(LinkTarget target) {
    return (target instanceof FutureBlock fb) ? fb.tag : null;
  }
}
