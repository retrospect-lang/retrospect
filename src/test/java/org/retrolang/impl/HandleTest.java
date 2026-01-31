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
import static org.junit.Assert.assertThrows;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.retrolang.code.Op;

@RunWith(JUnit4.class)
public class HandleTest {

  // Test class for reflection operations
  public static class TestClass {
    public String publicField = "public";
    private String privateField = "private";

    public TestClass() {}

    public TestClass(String value) {
      this.publicField = value;
    }

    public String getPublicField() {
      return publicField;
    }

    public void setPublicField(String value) {
      this.publicField = value;
    }

    public static String staticMethod(String input) {
      return "static:" + input;
    }

    private String privateMethod() {
      return "private:" + privateField;
    }
  }

  @Test
  public void forConstructorNoArgs() throws Throwable {
    MethodHandle mh = Handle.forConstructor(TestClass.class);

    assertThat(mh).isNotNull();
    Object instance = mh.invoke();
    assertThat(instance).isInstanceOf(TestClass.class);
  }

  @Test
  public void forConstructorWithArgs() throws Throwable {
    MethodHandle mh = Handle.forConstructor(TestClass.class, String.class);

    assertThat(mh).isNotNull();
    Object instance = mh.invoke("test");
    assertThat(instance).isInstanceOf(TestClass.class);
    assertThat(((TestClass) instance).publicField).isEqualTo("test");
  }

  @Test
  public void forMethodPublic() throws Throwable {
    MethodHandle mh = Handle.forMethod(TestClass.class, "getPublicField");

    assertThat(mh).isNotNull();
    TestClass obj = new TestClass("value");
    String result = (String) mh.invoke(obj);
    assertThat(result).isEqualTo("value");
  }

  @Test
  public void forMethodStatic() throws Throwable {
    MethodHandle mh = Handle.forMethod(TestClass.class, "staticMethod", String.class);

    assertThat(mh).isNotNull();
    String result = (String) mh.invoke("input");
    assertThat(result).isEqualTo("static:input");
  }

  @Test
  public void forMethodWithReturnType() throws Throwable {
    MethodHandle mh =
        Handle.forMethod(String.class, TestClass.class, "getPublicField");

    assertThat(mh).isNotNull();
    TestClass obj = new TestClass("test");
    String result = (String) mh.invoke(obj);
    assertThat(result).isEqualTo("test");
  }

  @Test
  public void forVarPublicField() throws Throwable {
    VarHandle vh = Handle.forVar(TestClass.class, "publicField", String.class);

    assertThat(vh).isNotNull();
    TestClass obj = new TestClass();
    vh.set(obj, "newValue");
    assertThat((String) vh.get(obj)).isEqualTo("newValue");
  }

  @Test
  public void forVarFromField() throws Throwable {
    Field field = TestClass.class.getDeclaredField("publicField");
    VarHandle vh = Handle.forVar(field);

    assertThat(vh).isNotNull();
    TestClass obj = new TestClass();
    vh.set(obj, "fromField");
    assertThat((String) vh.get(obj)).isEqualTo("fromField");
  }

  @Test
  public void simpleNameBasicClass() {
    String name = Handle.simpleName(String.class);
    assertThat(name).isEqualTo("String");
  }

  @Test
  public void simpleNameNestedClass() {
    String name = Handle.simpleName(TestClass.class);
    assertThat(name).isEqualTo("TestClass");
  }

  @Test
  public void simpleNameArrayClass() {
    String name = Handle.simpleName(String[].class);
    assertThat(name).isEqualTo("String[]");
  }

  @Test
  public void simpleNamePrimitiveClass() {
    String name = Handle.simpleName(int.class);
    assertThat(name).isEqualTo("int");
  }

  @Test
  public void opForMethod() throws Throwable {
    Op.Builder builder = Handle.opForMethod(Math.class, "max", int.class, int.class);

    assertThat(builder).isNotNull();
    Op op = builder.build();
    assertThat(op).isNotNull();
  }

  @Test
  public void opForMethodString() throws Throwable {
    Op.Builder builder = Handle.opForMethod(String.class, "concat", String.class);

    assertThat(builder).isNotNull();
    Op op = builder.build();
    assertThat(op).isNotNull();
  }

  @Test
  public void forMethodNonExistent() {
    assertThrows(
        NoSuchMethodException.class,
        () -> Handle.forMethod(TestClass.class, "nonExistentMethod"));
  }

  @Test
  public void forVarNonExistent() {
    assertThrows(
        NoSuchFieldException.class,
        () -> Handle.forVar(TestClass.class, "nonExistentField", String.class));
  }

  @Test
  public void forConstructorWithMultipleArgs() throws Throwable {
    MethodHandle mh = Handle.forConstructor(StringBuilder.class, String.class);

    assertThat(mh).isNotNull();
    Object instance = mh.invoke("initial");
    assertThat(instance).isInstanceOf(StringBuilder.class);
    assertThat(instance.toString()).isEqualTo("initial");
  }

  @Test
  public void forMethodWithPrimitives() throws Throwable {
    MethodHandle mh = Handle.forMethod(Math.class, "abs", int.class);

    assertThat(mh).isNotNull();
    int result = (int) mh.invoke(-5);
    assertThat(result).isEqualTo(5);
  }

  @Test
  public void simpleNameMultiDimensionalArray() {
    String name = Handle.simpleName(int[][].class);
    assertThat(name).isEqualTo("int[][]");
  }

  @Test
  public void forMethodVoidReturn() throws Throwable {
    MethodHandle mh =
        Handle.forMethod(TestClass.class, "setPublicField", String.class);

    assertThat(mh).isNotNull();
    TestClass obj = new TestClass();
    mh.invoke(obj, "updated");
    assertThat(obj.publicField).isEqualTo("updated");
  }

  @Test
  public void opForMethodStaticInteger() throws Throwable {
    Op.Builder builder = Handle.opForMethod(Integer.class, "parseInt", String.class);

    assertThat(builder).isNotNull();
    Op op = builder.build();
    assertThat(op).isNotNull();
  }
}