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
import com.google.errorprone.annotations.Keep;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.stream.IntStream;

/**
 * Objects (such as exlined methods) that may have generated code use a CodeGenLink to maintain a
 * link to the currently runnable code and to any in-progress-but-not-yet-runnable code.
 */
class CodeGenLink {

  /**
   * The relationship between the MethodMemo and the generated code. Currently there is only one
   * option.
   */
  enum Kind {
    /** The MethodMemo is an exlined method, and the generated code executes the method. */
    EXLINED("x") {
      @Override
      ValueMemo argsMemo(MethodMemo mm) {
        return mm.argsMemo;
      }
    },
    // Use "r" ("repeat", I guess) rather than "l" which is too easily misread as "1"
    LOOP("r") {
      @Override
      ValueMemo argsMemo(MethodMemo mm) {
        BuiltinSupport.BuiltinImpl impl = (BuiltinSupport.BuiltinImpl) mm.method().impl;
        return impl.loopContinuation().valueMemo(null, mm);
      }
    };

    final String code;

    Kind(String code) {
      this.code = code;
    }

    abstract ValueMemo argsMemo(MethodMemo mm);
  }

  final MethodMemo mm;

  final Kind kind;

  /**
   * If non-null, a BranchTargetCodeGen that is ready to be called. Updated asynchronously, so
   * should only be read by the {@link #ready()} method or the scope's current codegen thread.
   */
  @Keep private CodeGenTarget ready;

  CodeGenLink(MethodMemo mm, Kind kind) {
    this.mm = mm;
    this.kind = kind;
  }

  private static final VarHandle READY =
      Handle.forVar(MethodHandles.lookup(), CodeGenLink.class, "ready", CodeGenTarget.class);

  /** Returns the target to be used for this link. May block to generate code if appropriate. */
  CodeGenTarget checkReady(TState tstate) {
    // TODO: decide if we should generate code
    return ready();
  }

  /**
   * Returns the most recently completed target for this link, or null if code generation for this
   * link has not yet completed.
   */
  CodeGenTarget ready() {
    // Use getAcquire since this pointer may have been written asynchronously by another thread
    return (CodeGenTarget) READY.getAcquire(this);
  }

  /**
   * Called during code generation when we encounter a call to another MethodMemo with a
   * CodeGenLink; returns null if this is a good time to generate new code for that MethodMemo.
   */
  CodeGenTarget checkTarget(CodeGenManager manager) {
    // Only called from the group thread, so we don't need extra synchronization here.
    if (ready != null) {
      // There's already code; has its signature changed?
      // If so, we'd rather our caller used the new signature than the old one.
      ValueMemo argsMemo = kind.argsMemo(mm);
      for (int i = 0; i < ready.args.size(); i++) {
        if (!argsMemo.result(
            i, TProperty.isSubsetOf(ready.args.get(i), TemplateBuilder.TestOption.NO_EVOLUTION))) {
          // If we generate new code now it will accept a broader range of arguments, so let's do
          // that
          return null;
        }
      }
      return ready;
    }
    return null;
  }

  /** Creates a new target for this link. */
  CodeGenTarget newTarget(CodeGenGroup group) {
    int counter = group.manager.nextIndex();
    String name = String.format("%s_%s%d", mm.method().function.name, kind.code, counter);
    return new CodeGenTarget(
        name,
        group,
        this,
        ready,
        alloc -> build(kind.argsMemo(mm), alloc),
        alloc -> build(mm.resultsMemo, alloc));
  }

  /** Build a Template for each of the ValueMemo's elements. */
  private static ImmutableList<Template> build(ValueMemo memo, TemplateBuilder.VarAllocator alloc) {
    TProperty<Template> builder = TProperty.build(alloc);
    return IntStream.range(0, memo.size())
        .mapToObj(i -> memo.result(i, builder))
        .collect(ImmutableList.toImmutableList());
  }

  /** Called when we have completed code generation for all targets in the group. */
  void makeReady(CodeGenTarget next, CodeGenDebugging debug) {
    if (debug != null) {
      debug.append(next, "b");
    }
    READY.setRelease(this, next);
  }
}
