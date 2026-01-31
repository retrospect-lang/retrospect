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

import com.google.common.collect.ImmutableList;
import org.retrolang.Vm;

/** Implementation of Vm.VirtualMachine. Each instance will have a separate Scope. */
public class VirtualMachine implements Vm.VirtualMachine {

  final Scope scope = new Scope();

  public VirtualMachine() {}

  /**
   * If {@code enableDebugging()} is called before executing any code, {@link #getDebuggingSummary}
   * can be called when the computation completes to get a summary of the final FrameLayouts.
   */
  public void enableDebugging() {
    scope.evolver.enableDebugging();
  }

  public String getDebuggingSummary() {
    return scope.evolver.getSummary();
  }

  @Override
  public Vm.Module core() {
    return Core.core();
  }

  @Override
  public Vm.ModuleBuilder newModule(String name) {
    return new ModuleBuilder(name);
  }

  @Override
  public Vm.Expr asExpr(String s) {
    return VmExpr.Constant.of(StringValue.uncounted(s));
  }

  @Override
  public Vm.Expr asExpr(int i) {
    return VmExpr.Constant.of(NumValue.of(i, Allocator.UNCOUNTED));
  }

  @Override
  public Vm.Expr asExpr(double d) {
    return VmExpr.Constant.of(NumValue.of(d, Allocator.UNCOUNTED));
  }

  @Override
  public Vm.Compound arrayOfSize(int size) {
    return Core.FixedArrayType.withSize(size).compound;
  }

  @Override
  public Vm.Compound structWithKeys(String... keys) {
    return scope.compoundWithKeys(ImmutableList.copyOf(keys)).vmCompound;
  }

  @Override
  public ResourceTracker newResourceTracker(long limit, int maxTraces, boolean debug) {
    return new ResourceTracker(scope, limit, maxTraces, debug);
  }
}
