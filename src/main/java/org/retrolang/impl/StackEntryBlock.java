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
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.retrolang.code.Block;
import org.retrolang.code.CodeBuilder;
import org.retrolang.code.CodeBuilder.PrintOptions;
import org.retrolang.code.CodeValue;
import org.retrolang.code.CodeValue.Const;
import org.retrolang.code.Emitter;
import org.retrolang.code.Register;
import org.retrolang.code.SetBlock;
import org.retrolang.code.ValueInfo;
import org.retrolang.code.ValueInfo.IntRange;
import org.retrolang.impl.Template.Compound;
import org.retrolang.impl.Template.NumVar;
import org.retrolang.impl.Template.RefVar;
import org.retrolang.impl.Template.Union;
import org.retrolang.impl.TemplateBuilder.VarAllocator;
import org.retrolang.util.Bits;
import org.retrolang.util.SmallIntMap;

/// A Block that allocates a new stack entry frame, initializes it, and saves it in a register.
///
/// The straightforward implementation would be to construct a new RecordLayout from the RValue's
/// template, and emit instructions to allocate a frame with that layout and set each field from the
/// corresponding register. That would work, but is suboptimal if during optimization we find out
/// that a register has a known value (so didn't need to be saved at all) or two registers have the
/// same value (so we only needed to save one of them).
///
/// This implementation instead delays choosing the RecordLayout until all optimizations are
/// complete, minimizing the size of the frame we need. (If all registers are discovered to have
/// constant values we don't even need a RecordLayout or frame -- we can just use a constant
/// CompoundValue.)
class StackEntryBlock extends Block.NonTerminal {
  /// The register in which the stack entry will be stored.
  private final Register lhs;

  /// The stack entry to be saved. May be simplified by CodeBuilder optimizations.
  private Template.Compound rhs;

  /// A SmallIntMap with one entry for each register that appears in [#rhs]; the value is a Const or
  /// a Register, initially the same as the key.
  private final SmallIntMap.Builder<CodeValue> replacements = new SmallIntMap.Builder<>();

  /// Contains any registers that appear as union tags in [#rhs].
  private final Bits.Builder unionTags = new Bits.Builder();

  /// Scratch state for [#updateInfo].
  private boolean allConst;

  /// Scratch state for [#updateInfo].
  private boolean needSimplify;

  /// The registers that will be returned by [#input]; includes all the registers in [#replacements]
  /// plus the TState register.
  ///
  /// Computed on demand; may be null if we have not yet computed it or if a call to [#updateInfo]
  /// has invalidated it.
  private ImmutableList<Register> registerList;

  /// Registers that we should addRef before saving.
  private ImmutableList<Register> addRef;

  /// Returns a CodeValue for the given stack entry. If `stackEntry` is not an RValue, just returns
  /// a constant CodeValue; otherwise allocates a new register and emits a StackEntryBlock to
  /// initialize it.
  static CodeValue create(Value stackEntry, CodeBuilder cb) {
    assert stackEntry.baseType() instanceof BaseType.StackEntryType;
    if (!(stackEntry instanceof RValue rValue)) {
      return CodeValue.of(stackEntry);
    }
    Register result = cb.newRegister(Value.class);
    new StackEntryBlock(result, (Template.Compound) rValue.template, cb).addTo(cb);
    return result;
  }

  private StackEntryBlock(Register lhs, Template.Compound rhs, CodeBuilder cb) {
    this.lhs = lhs;
    this.rhs = rhs;
    findRegisters(rhs, cb);
  }

  /// Must be called exactly once before this StackEntryBlock is emitted.
  void setAddRef(ImmutableList<Register> addRef) {
    assert this.addRef == null;
    this.addRef = addRef;
  }

  /// Adds a self entry to [#replacements] for each numvar or refvar in the given template. Adds any
  /// union tags to [#unionTags].
  private void findRegisters(Template t, CodeBuilder cb) {
    int index;
    if (Template.isConstant(t)) {
      return;
    } else if (t instanceof NumVar nv) {
      index = nv.index;
    } else if (t instanceof RefVar rv) {
      index = rv.index;
    } else if (t instanceof Compound c) {
      for (int i = 0; i < c.baseType.size(); i++) {
        findRegisters(c.element(i), cb);
      }
      return;
    } else {
      Union u = (Union) t;
      if (u.tag != null) {
        for (int i = 0; i < u.numChoices(); i++) {
          findRegisters(u.choice(i), cb);
        }
        index = u.tag.index;
        unionTags.set(index);
      } else {
        // An untagged union only references a single register.
        index = u.untagged.index;
      }
    }
    replacements.put(index, cb.register(index));
  }

  /// Simplifies the given template by looking up register values in the given replacements map and
  /// replacing any that are constants. Reinitializes [#replacements] and [#unionTags] to match the
  /// new template.
  private Template simplify(Template t, SmallIntMap<CodeValue> prevReplacements) {
    int index;
    if (Template.isConstant(t)) {
      return t;
    } else if (t instanceof NumVar nv) {
      index = nv.index;
    } else if (t instanceof RefVar rv) {
      index = rv.index;
    } else if (t instanceof Compound c) {
      Template[] elements =
          IntStream.range(0, c.baseType.size())
              .mapToObj(i -> simplify(c.element(i), prevReplacements))
              .toArray(Template[]::new);
      return Template.Compound.of(c.baseType, elements);
    } else {
      Union u = (Union) t;
      if (u.tag != null) {
        index = u.tag.index;
        CodeValue replacement = prevReplacements.get(index);
        if (replacement instanceof Const c) {
          // We know which choice is being taken, so this no longer needs to be a Union
          return simplify(u.choice(c.iValue()), prevReplacements);
        }
        replacements.put(index, replacement);
        unionTags.set(index);
        Template[] choices =
            IntStream.range(0, u.numChoices())
                .mapToObj(i -> simplify(u.choice(i), prevReplacements))
                .toArray(Template[]::new);
        return new Union(u.tag, null, choices);
      } else {
        index = u.untagged.index;
      }
    }
    CodeValue replacement = prevReplacements.get(index);
    if (replacement instanceof Const c) {
      return asTemplate(c);
    }
    replacements.put(index, replacement);
    // Note that even if prevReplacements maps a register to a different register, we leave the
    // template unchanged; doing so is simpler, and the final results are the same because
    // allocate() does a final lookup.
    return t;
  }

  @Override
  public int numInputs() {
    if (registerList == null) {
      ImmutableList.Builder<Register> builder = new ImmutableList.Builder<>();
      builder.add(cb().register(CodeGen.TSTATE_REGISTER_INDEX));
      // We could walk the template and transform each NumVar or RefVar, but we've already done that
      // once.
      replacements
          .streamValues()
          .forEach(
              cv -> {
                // If this NumVar or RefVar has a known constant value, we can leave it off our list
                if (cv instanceof Register r) {
                  builder.add(r);
                }
              });
      registerList = builder.build();
    }
    return registerList.size();
  }

  @Override
  public Register input(int index) {
    // No one should be calling this without first having called numInputs(), so registerList should
    // be ready.
    return registerList.get(index);
  }

  @Override
  protected PropagationResult updateInfo() {
    allConst = true;
    needSimplify = false;
    // This loop through template's vars does 4 things:
    //   1. update replacements to have the latest info for each var that wasn't already known to be
    //      constant
    //   2. set registerList to null if any of the replacements changed
    //   3. set allConst to false if at least one isn't a constant
    //   4. set needSimplify to true if one of the registers in unionTags is constant
    replacements.updateEntries(
        (i, v) -> {
          if (v instanceof Register r) {
            ValueInfo info = next.info.register(r.index);
            if (info instanceof Const || info instanceof Register) {
              v = (CodeValue) info;
              registerList = null;
              if (info instanceof Const && unionTags.test(i)) {
                needSimplify = true;
              }
            }
          }
          if (!(v instanceof Const)) {
            allConst = false;
          }
          return v;
        });
    if (allConst) {
      // If all the registers we were going to save are constant, we don't need a frame at all;
      // replace this with simple set of a constant stack entry.
      Template.Constant t = (Template.Constant) allocate(rhs, null, null);
      return replaceWith(new SetBlock(lhs, Const.of(t.value)));
    } else if (needSimplify) {
      SmallIntMap<CodeValue> prevReplacements = replacements.build();
      unionTags.clearAll();
      rhs = (Template.Compound) simplify(rhs, prevReplacements);
    }
    // Our lhs will be set to a newly-allocated frame, so its ValueInfo is ANY
    next.info.updateForAssignment(lhs, ValueInfo.ANY, cb());
    return PropagationResult.DONE;
  }

  @Override
  protected SubstitutionOutcome trySubstitute(Register register, CodeValue value) {
    // Support substitutions would be a hassle, and would rarely (maybe never?) be useful.
    return SubstitutionOutcome.NO;
  }

  @Override
  public void forEachModifiedRegister(Consumer<Register> consumer) {
    consumer.accept(lhs);
  }

  @Override
  public boolean mayReadAfterWrite() {
    // Because we save the newly-allocated frame in our lhs before setting the fields in it from our
    // rhs, RegisterAssigner needs to know that it can't alias the lhs with any of the rhs
    // registers.
    return true;
  }

  @Override
  public Block emit(Emitter emitter) {
    // RcCodeBuilder should have told us which registers need an addRef
    addRef.forEach(r -> RefCounted.ADD_REF_OP.result(r).push(emitter, void.class));
    // We've waited as late as possible, now it's time to choose the RecordLayout
    // vars maps register index to a NumVar or RefVar in the layout template
    SmallIntMap.Builder<Template> vars = new SmallIntMap.Builder<>();
    RecordLayout.VarAllocator alloc = RecordLayout.VarAllocator.newForRecordLayout();
    Template.Compound t = (Template.Compound) allocate(rhs, vars, alloc);
    RecordLayout layout = RecordLayout.newForStackEntry(t, alloc);
    // Any registers that we addRef'd should be among those we are saving!
    assert addRef.stream().allMatch(r -> vars.containsKey(r.index));
    // Emit instructions to allocate a frame with that layout and store it in lhs
    layout.emitAlloc(emitter.cb).push(emitter, lhs.type());
    emitter.store(lhs);
    // Emit instructions to set each field of the frame to the corresponding register
    vars.forEachEntry(
        (ri, field) -> {
          layout.setCodeValue(field, lhs, cb().register(ri)).emit(emitter);
        });
    return next.targetBlock();
  }

  /// Similar to building `t` with `alloc`, except that
  ///
  /// - NumVars and RefVars are first replaced with [#replacements]; and
  /// - we also build a mapping from registers to their locations in the new template, and if a
  ///   register occurs more than once we only want to allocate it once.
  private Template allocate(Template t, SmallIntMap.Builder<Template> vars, VarAllocator alloc) {
    int index;
    if (Template.isConstant(t)) {
      return t;
    } else if (t instanceof NumVar nv) {
      index = nv.index;
    } else if (t instanceof RefVar rv) {
      index = rv.index;
    } else if (t instanceof Compound compound) {
      Template[] elements =
          IntStream.range(0, compound.baseType.size())
              .mapToObj(i -> allocate(compound.element(i), vars, alloc))
              .toArray(Template[]::new);
      return Compound.of(compound.baseType, elements);
    } else {
      Union union = (Union) t;
      NumVar tag;
      RefVar untagged;
      if (union.tag == null) {
        Template untaggedTemplate = allocate(union.untagged, vars, alloc);
        if (untaggedTemplate instanceof Template.Constant c) {
          return c;
        }
        untagged = (RefVar) untaggedTemplate;
        tag = null;
      } else {
        Template tagTemplate = allocate(union.tag, vars, alloc);
        if (tagTemplate instanceof Template.Constant c) {
          int i = NumValue.toInt(c.value);
          return allocate(union.choice(i), vars, alloc);
        }
        tag = (NumVar) tagTemplate;
        untagged = null;
      }
      Template[] choices =
          IntStream.range(0, union.numChoices())
              .mapToObj(i -> allocate(union.choice(i), vars, alloc))
              .toArray(Template[]::new);
      return new Union(tag, untagged, choices);
    }
    // NumVar or RefVar
    // First see if the corresponding register has been replaced with a constant
    CodeValue replacement = replacements.get(index);
    if (replacement instanceof Const c) {
      return asTemplate(c);
    }
    // Or it may have been replaced with another register
    index = ((Register) replacement).index;
    // Now see if we've already allocated a place to store that register
    Template result = vars.get(index);
    if (result != null) {
      if (t instanceof RefVar rv) {
        return rv.withIndex(((RefVar) result).index);
      }
      return result;
    }
    // Allocate a place to store the register in this frame
    if (t instanceof NumVar nv) {
      if (nv.encoding != NumEncoding.FLOAT64) {
        // If an int register is known to contain only uint8 values, we can store it as a uint8
        nv =
            cb().binaryOps.containsAll(IntRange.UINT8, next.info.register(index))
                ? NumVar.UINT8
                : NumVar.INT32;
      }
      result = nv.build(alloc);
    } else {
      result = ((RefVar) t).build(alloc);
    }
    // ... and remember it, in case we see another reference to that register
    vars.put(index, result);
    return result;
  }

  /// Converts a constant CodeValue to the corresponding constant template.
  private static Template asTemplate(Const c) {
    // The value must either be a Value (used as-is) or a Number (converted to a NumValue).
    Value v =
        (c.value instanceof Value cv) ? cv : NumValue.of((Number) c.value, Allocator.UNCOUNTED);
    return Template.Constant.of(v);
  }

  @Override
  public String toString(PrintOptions options) {
    // When printing out a StackEntryBlock we replace each NumVar or RefVar with its current entry
    // in replacements.
    Template.Printer printer =
        new Template.Printer() {
          private String reg(int i) {
            return replacements.get(i).toString(options);
          }

          @Override
          public String toString(NumVar nv) {
            return reg(nv.index);
          }

          @Override
          public String toString(RefVar rv) {
            return reg(rv.index);
          }
        };
    String result =
        String.format("%s ← newStackEntry(%s)", lhs.toString(options), rhs.toString(printer));
    if (addRef == null || addRef.isEmpty()) {
      return result;
    }
    StringBuilder sb = new StringBuilder();
    for (Register r : addRef) {
      sb.append("addRef(").append(r.toString(options)).append("); ");
    }
    return sb.append(result).toString();
  }
}
