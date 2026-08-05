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

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.retrolang.Vm.Access;

@RunWith(JUnit4.class)
public class MethodPredicateTest {

  /// A varargs wrapper for MethodPredicate#test, for convenience.
  private static boolean test(MethodPredicate mp, Value... values) {
    return mp.test(values).asBoolean();
  }

  /// Tests [MethodPredicate.Simple].
  @Test
  public void simple() {
    ModuleBuilder mb = new ModuleBuilder("MyModule");
    Singleton mySingleton = mb.newSingleton("MySingleton", Access.VISIBLE);

    // "arg0 is MySingleton"
    MethodPredicate mp = mySingleton.asType().argType(0, true);

    assertThat(test(mp, mySingleton)).isTrue();
    assertThat(test(mp, NumValue.ZERO)).isFalse();
    assertThat(mp.asArgRestriction(mb.module)).containsExactly(mp);
  }

  @Test
  public void and() {
    ModuleBuilder mb1 = new ModuleBuilder("Module1");
    Singleton m1Singleton = mb1.newSingleton("M1Singleton", Access.VISIBLE);
    ModuleBuilder mb2 = new ModuleBuilder("Module2");
    VmCompound m2Compound = mb2.newCompoundType("M2Compound", null, Access.VISIBLE);
    Value m2Value = m2Compound.baseType.uncountedOf(NumValue.ZERO);

    // "arg0 is M1Singleton and arg1 is M2Compound"
    MethodPredicate mp1 = m1Singleton.asType().argType(0, true);
    MethodPredicate mp2 = m2Compound.asType().argType(1, true);
    MethodPredicate mp = mp1.and(mp2);

    assertThat(test(mp, m1Singleton, m2Value)).isTrue();
    assertThat(test(mp, NumValue.ZERO, m2Value)).isFalse();
    assertThat(test(mp, m1Singleton, NumValue.ZERO)).isFalse();
    assertThat(mp.asArgRestriction(mb1.module)).containsExactly(mp1);
    assertThat(mp.asArgRestriction(mb2.module)).containsExactly(mp2);
  }

  @Test
  public void andNot() {
    // "arg0 is not Array and arg0 is Matrix"
    MethodPredicate mp1 = Core.ARRAY.argType(0, false);
    MethodPredicate mp2 = Core.MATRIX.argType(0, true);
    MethodPredicate mp = mp1.and(mp2);

    assertThat(test(mp, Core.NONE)).isFalse();
    assertThat(test(mp, Core.EMPTY_ARRAY)).isFalse();

    // "0..1"
    Value range = Core.RANGE.uncountedOf(NumValue.ZERO, NumValue.ONE);

    assertThat(test(mp, range)).isTrue();
    assertThat(mp.asArgRestriction(Core.core())).containsExactly(mp2);
  }
}
