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

import java.util.function.IntFunction;
import org.retrolang.code.CodeBuilder.PrintOptions;
import org.retrolang.code.ValueInfo.BinaryOps;
import org.retrolang.util.SmallIntMap;
import org.retrolang.util.SmallIntMapBase;

/// A Block that emits no byte codes but adds ValueInfos for one or more registers. Subsequent
/// instructions may assume that these assertions are true; if they are violated the results are
/// undefined.
public class RegisterInfoBlock extends Block.NonTerminal {

  /// ValueInfos that will be added to (intersected with) whatever our inlinks say. Mutable so that
  /// we can drop entries that are no longer useful.
  private final SmallIntMap.Builder<ValueInfo> registerInfo = new SmallIntMap.Builder<>();

  public RegisterInfoBlock(Register register, ValueInfo info) {
    this.registerInfo.put(register.index, info);
  }

  public RegisterInfoBlock(SmallIntMapBase<ValueInfo> registerInfo) {
    this.registerInfo.setFrom(registerInfo);
  }

  @Override
  boolean isSimple() {
    return true;
  }

  @Override
  boolean isDuplicate(Block other) {
    return other instanceof RegisterInfoBlock rib && registerInfo.equals(rib.registerInfo);
  }

  @Override
  IntFunction<ValueInfo> simpleInfos(IntFunction<ValueInfo> fromInlink) {
    return i -> {
      ValueInfo prev = fromInlink.apply(i);
      ValueInfo extra = registerInfo.get(i);
      if (extra == null) {
        return prev;
      } else if (prev instanceof Register prevReg) {
        prev = fromInlink.apply(prevReg.index);
      }
      return cb().binaryOps.intersection(prev, extra);
    };
  }

  @Override
  NonTerminal duplicateImpl() {
    return new RegisterInfoBlock(registerInfo);
  }

  @Override
  protected PropagationResult updateInfo() {
    BinaryOps binaryOps = cb().binaryOps;
    registerInfo.updateEntries(
        (i, v) -> {
          // When updateInfo is called, our outlink's info has already been set to the intersection
          // of the infos from our inlinks.
          ValueInfo fromInlink = next.info.register(i);
          // If it's a copy of another register, apply our info to the one that was copied
          if (fromInlink instanceof Register r) {
            i = r.index;
            fromInlink = next.info.register(i);
          }
          ValueInfo forOutlink = binaryOps.intersection(fromInlink, v);
          if (forOutlink.equals(fromInlink)) {
            // If we're no longer adding anything we can drop this part of our registerInfo
            // (link infos should only get stronger, so if it's true now it will always be true).
            return null;
          }
          next.info.updateInfo(i, forOutlink);
          return v;
        });
    return registerInfo.isEmpty() ? PropagationResult.REMOVE : PropagationResult.DONE;
  }

  @Override
  protected PropagationResult updateLive() {
    var unused = super.updateLive();
    // We don't need to update infos for registers that aren't live
    registerInfo.updateEntries((i, v) -> live.test(i) ? v : null);
    return registerInfo.isEmpty() ? PropagationResult.REMOVE : PropagationResult.DONE;
  }

  @Override
  public boolean isNoOp() {
    return true;
  }

  @Override
  public String toString(PrintOptions options) {
    StringBuilder sb = new StringBuilder("regInfo");
    registerInfo.forEachEntry(
        (i, v) -> {
          String r = cb().register(i).toString(options);
          sb.append(String.format(" %s:%s", r, v));
        });
    return sb.toString();
  }
}
