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

import org.retrolang.Vm;

/// Minimal implementation of Vm.Local.
class Local extends VmExpr implements Vm.Local {

  /// Used as a placeholder for unnamed arguments.
  static final Local UNNAMED = new Local(null, "_", -1);

  final InstructionBlock ib;
  final String name;
  final int index;

  Local(InstructionBlock ib, String name, int index) {
    this.ib = ib;
    this.name = name;
    this.index = index;
  }

  @Override
  public String toString() {
    return name;
  }
}
