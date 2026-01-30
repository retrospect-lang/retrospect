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
   * If this is the loopCodeGen of a LoopMethodMemo, a link to the nearest enclosing CodeGenLink.
   * Null if this is the extra of an exlined MethodMemo.
   */
  private CodeGenParent cgParent;

  /**
   * For exlined MethodMemos, incremented each time this method is started or completed; for loops,
   * incremented on each backward branch to the LoopContinuation. Reset each time some part of the
   * memo's structure changes in a way that might affect code generation. This serves as a measure
   * of how long the memo state has remained unchanged; when the counter reaches a threshold we deem
   * it time to try generating code for the MethodMemo.
   *
   * <p>Only used to trigger the initial code generation for this MethodMemo; re-generation is based
   * on the current CodeGenTarget's stability counter.
   */
  private int stabilityCounter;

  /**
   * If non-null, a BranchTargetCodeGen that is ready to be called. Updated asynchronously, so
   * should only be read by the {@link #ready()} method or the scope's current codegen thread.
   */
  @Keep private CodeGenTarget ready;

  /**
   * A CodeGenParent that points to this CodeGenLink; used by TStates that are currently executing
   * the method body and by nested loops.
   */
  private CodeGenParent selfLink;

  /**
   * If non-null, this CodeGenLink has been deprecated in favor of another. Only set while holding
   * the MemoMerger lock.
   */
  private CodeGenLink forwardedTo;

  CodeGenLink(MethodMemo mm, Kind kind) {
    this.mm = mm;
    this.kind = kind;
    this.cgParent = (kind == Kind.EXLINED) ? null : CodeGenParent.INVALID;
    this.selfLink = new CodeGenParent(this);
  }

  private static final VarHandle CG_PARENT =
      Handle.forVar(MethodHandles.lookup(), CodeGenLink.class, "cgParent", CodeGenParent.class);

  private static final VarHandle STABILITY_COUNTER =
      Handle.forVar(MethodHandles.lookup(), CodeGenLink.class, "stabilityCounter", int.class);

  private static final VarHandle READY =
      Handle.forVar(MethodHandles.lookup(), CodeGenLink.class, "ready", CodeGenTarget.class);

  private static final VarHandle SELF_LINK =
      Handle.forVar(MethodHandles.lookup(), CodeGenLink.class, "selfLink", CodeGenParent.class);

  private static final VarHandle FORWARDED_TO =
      Handle.forVar(MethodHandles.lookup(), CodeGenLink.class, "forwardedTo", CodeGenLink.class);

  /**
   * Called when execution of this exlined method or loop body completes; if called enough times
   * without an intervening call to {@link #resetStabilityCounter} the link will become eligible for
   * code generation.
   *
   * <p>If we have already generated code for this link, the stability counter will only be
   * incremented if {@code unwoundFrom} matches the most-recently generated code.
   */
  boolean incrementStabilityCounter(CodeGenTarget unwoundFrom, CodeGenDebugging debug) {
    CodeGenTarget ready = ready();
    if (ready != null) {
      if (ready == unwoundFrom) {
        if (debug != null) {
          debug.append(ready, ready.stabilityCounter() < 30 ? "⊕" : "⊕!");
        }
        ready.incrementStabilityCounter();
        return true;
      } else if (debug != null) {
        debug.append(this, "*");
      }
    } else {
      if (debug != null) {
        debug.append(this, stabilityCounter() < 30 ? "+" : "+!");
      }
      var unused = (int) STABILITY_COUNTER.getAndAdd(this, 1);
    }
    return false;
  }

  /**
   * Called when execution of this exlined method or loop body modifies the MethodMemo in a way that
   * might affect code generation.
   */
  void resetStabilityCounter(CodeGenDebugging debug) {
    CodeGenTarget ready = ready();
    if (ready == null) {
      if (debug != null) {
        debug.append(this, stabilityCounter() == 0 ? "-" : "∘");
      }
      STABILITY_COUNTER.setOpaque(this, 0);
    } else {
      if (debug != null) {
        debug.append(ready, ready.stabilityCounter() == 0 ? "⊝" : "⊚");
      }
      ready.resetStabilityCounter();
    }
  }

  private int stabilityCounter() {
    return (int) STABILITY_COUNTER.getOpaque(this);
  }

  /** Returns the target to be used for this link. May block to generate code if appropriate. */
  CodeGenTarget checkReady(TState tstate) {
    CodeGenManager manager = tstate.scope().codeGenManager;
    int threshold = manager.threshold(kind);
    CodeGenTarget target = ready();
    if (threshold < 0) {
      // Auto-codegen is disabled.
      return target;
    } else if ((target == null ? stabilityCounter() : target.stabilityCounter()) < threshold) {
      // We haven't been stable long enough to make re-generation worthwhile
      return target;
    }
    synchronized (manager) {
      target = ready;
      // While we waited for the lock it's possible that some other thread generated new code for
      // us, so check again.
      if (target == null || target.stabilityCounter() >= threshold) {
        CodeGenGroup group = new CodeGenGroup(manager);
        group.setup(this);
        group.generateCode();
        target = ready;
      }
    }
    return target;
  }

  /**
   * If this is the loopCodeGen of a LoopMethodMemo, a link to the nearest enclosing CodeGenLink.
   * Null if this is the extra of an exlined MethodMemo.
   */
  CodeGenParent cgParent() {
    // Use getAcquire since this pointer may have been written asynchronously by another thread
    return (CodeGenParent) CG_PARENT.getAcquire(this);
  }

  void setCgParent(CodeGenParent newParent) {
    assert kind == Kind.LOOP;
    CG_PARENT.setRelease(this, newParent);
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
   * Returns the most recently completed target for this link, or null if code generation for this
   * link has not yet completed.
   */
  CodeGenParent selfLink() {
    CodeGenLink link = this;
    // If this CodeGenLink has been forwarded, get the selfLink from its replacement.
    for (; ; ) {
      CodeGenLink forwardedTo = link.forwardedTo();
      if (forwardedTo == null) {
        // Use getAcquire since this pointer may have been written asynchronously by another thread
        return (CodeGenParent) SELF_LINK.getAcquire(link);
      }
      link = forwardedTo;
    }
  }

  /**
   * Called when a new CodeGenLink has been created to own part of the MethodMemo tree we previously
   * owned (either by exlining a method or by introducing a CodeGenLink for a LoopMethodMemo).
   * Creates a new {@link #selfLink} and invalidates the previous one, so that anyone that thought
   * they were a child will re-check. Also resets our stability counter.
   */
  CodeGenParent invalidateSelfLink(CodeGenDebugging debug) {
    CodeGenParent current = selfLink();
    if (current.link() != this) {
      // Either this link has been forwarded, or we raced against someone else calling
      // invalidateSelfLink; either way this result is OK
      debug.append(this, "I");
      return current;
    }
    CodeGenParent result = new CodeGenParent(this);
    CodeGenParent prev = (CodeGenParent) SELF_LINK.compareAndExchangeRelease(this, current, result);
    if (prev != current) {
      // We raced against someone else calling invalidateSelfLink; keep their result
      debug.append(this, "I!");
      return prev;
    }
    current.clear();
    resetStabilityCounter(debug);
    return result;
  }

  CodeGenLink forwardedTo() {
    // Use getAcquire since this pointer may have been written asynchronously by another thread
    return (CodeGenLink) FORWARDED_TO.getAcquire(this);
  }

  void setForwardedTo(CodeGenLink other) {
    assert forwardedTo == null && other != this && MemoMerger.isLocked();
    CodeGenParent selfLink = selfLink();
    FORWARDED_TO.setRelease(this, other);
    selfLink.clear();
  }

  /**
   * Called during code generation when we encounter a call to another MethodMemo with a
   * CodeGenLink; returns null if this is a good time to generate new code for that MethodMemo.
   */
  CodeGenTarget checkTarget(CodeGenManager manager) {
    CodeGenTarget ready = ready();
    if (ready != null && ready.stabilityCounter() < manager.threshold(kind)) {
      // There's already code, and we haven't seen enough escapes to merit regenerating it.  We will
      // probably stick with the old code, but first do one more check: has the signature of the
      // code changed?  If so, we'd rather our caller used the new signature than the old one.
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
