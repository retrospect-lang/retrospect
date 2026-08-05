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

import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.retrolang.Vm;

/// A subclass of Vm.RuntimeError that saves the ResourceTracker and Retrospect thread stack.
class RuntimeError extends Vm.RuntimeError {

  final ResourceTracker tracker;
  private TStack stackHead;

  RuntimeError(Throwable cause, ResourceTracker tracker, TStack stackHead) {
    super(null, cause);
    this.tracker = tracker;
    this.stackHead = stackHead;
  }

  @Override
  public void close() {
    TStack stackHead;
    synchronized (this) {
      stackHead = this.stackHead;
      this.stackHead = null;
    }
    if (stackHead != null) {
      TState.dropReferenceWithTracker(tracker, stackHead);
    }
  }

  @Override
  public synchronized String getMessage() {
    if (stackHead == null) {
      return "Closed RuntimeError";
    } else {
      // For the java stack trace just show the top level of the Retrospect stack, which should
      // be the error.
      return stackHead.first().toString();
    }
  }

  @Override
  public synchronized @Nullable List<String> stack() {
    return (stackHead == null)
        ? null
        : stackHead.stringStream().collect(Collectors.toUnmodifiableList());
  }
}
