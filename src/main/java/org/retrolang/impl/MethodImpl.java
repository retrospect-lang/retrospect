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

interface MethodImpl {
  /// Either saves results in tstate if the method completes successfully, or starts unwinding if it
  /// does not.
  ///
  /// @param results information about how the results of this call will be represented
  /// @param mMemo the MethodMemo that should be used while executing this method
  void execute(TState tstate, ResultsInfo results, MethodMemo mMemo, @RC.In Object[] args);

  /// Emits instructions that are equivalent to calling [#execute].
  void emit(CodeGen codeGen, ResultsInfo results, MethodMemo mMemo, Object[] args);

  /// Returns the module that defined this method.
  default VmModule module() {
    return Core.CORE;
  }
}
