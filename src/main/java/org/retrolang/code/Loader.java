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

import com.google.common.base.Preconditions;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.retrolang.util.Pair;

/// A Loader implements the low-level operation of creating a new MethodHandle from the bytecode for
/// a method. The lifecycle of a Loader is
///
/// - Create a new Loader using one of the [#newLoader] methods.
/// - Call [#initialize] on it.
/// - Write the method body to the result of [#methodVisitor()], using [#emitLoadConstant] to
///   reference existing objects.
/// - Call [#load] to get the MethodHandle and to populate a [DebugInfo] with information useful for
///   debugging any exceptions that might be thrown by the generated code.
public final class Loader {

  private ClassWriter classWriter;
  private MethodVisitor methodVisitor;
  private Lookup lookup;
  private String methodName;
  private MethodType methodType;

  /// We use CONSTANT\_Dynamic entries in the constant pool to implement [#emitLoadConstant], which
  /// requires a bootstrap method to provide its value (see the package docs for `java.lang.invoke`
  /// for details). Fortunately [MethodHandles#classDataAt] is designed to serve this purpose, so we
  /// just need to construct an ASM Handle for it.
  private static final Handle CLASS_DATA_AT =
      new Handle(
          Opcodes.H_INVOKESTATIC,
          asmType(MethodHandles.class),
          "classDataAt",
          MethodType.methodType(Object.class, Lookup.class, String.class, Class.class, int.class)
              .toMethodDescriptorString(),
          false);

  /// Maps each object that has been passed to [#emitLoadConstant] to the ConstantDynamic that we
  /// created for it and the associated type.
  private final IdentityHashMap<Object, Pair<ConstantDynamic, Class<?>>> constants =
      new IdentityHashMap<>();

  /// Returns the JVM class file version that will be used by this Loader.
  public int classFileVersion() {
    return Opcodes.V15;
  }

  /// Writes an instruction to [#methodVisitor()] to push the given object on the stack, as an
  /// instance of the given type.
  ///
  /// Only intended for objects that are not supported directly by the Java constant pool; strings
  /// and numbers should be pushed with the appropriate methods on [#methodVisitor()].
  public void emitLoadConstant(Object x, Class<?> type) {
    Pair<ConstantDynamic, Class<?>> pair = constants.get(x);
    if (pair == null) {
      // Create a new CONSTANT_Dynamic that will be initialized by calling classDataAt() with the
      // next available index.
      // "_" is ConstantDescs.DEFAULT_NAME once we're off Java 11.
      ConstantDynamic cd =
          new ConstantDynamic(
              "_", org.objectweb.asm.Type.getDescriptor(type), CLASS_DATA_AT, constants.size());
      pair = new Pair<>(cd, type);
      constants.put(x, pair);
    }
    methodVisitor().visitLdcInsn(pair.x);
    // Unlikely, but if we created this constant with a weaker type and we're now trying to use it
    // with a stronger type we'll need to throw in a cast to make the verifier happy.
    if (!type.isAssignableFrom(pair.y)) {
      methodVisitor().visitTypeInsn(Opcodes.CHECKCAST, asmType(type));
    }
  }

  /// Should be called after a complete method body has been written to [#methodVisitor()]; returns
  /// a MethodHandle that can be used to call it. The given DebugInfo will be populated with various
  /// bits of information that might be useful for understanding or debugging the generated code.
  public MethodHandle load(DebugInfo debugInfo) {
    byte[] bytes = classWriterToBytes(debugInfo);
    // Build a simple index to object array.  Unlike OldApiLoader where we needed two arrays
    // because there were two different indexes, there's only one index here so we can use the
    // same array for DebugInfo and the class loader.
    Object[] constArray = new Object[constants.size()];
    debugInfo.constants = constArray;
    for (Map.Entry<Object, Pair<ConstantDynamic, Class<?>>> entry : constants.entrySet()) {
      constArray[(int) entry.getValue().x.getBootstrapMethodArgument(0)] = entry.getKey();
    }
    @SuppressWarnings("JdkImmutableCollections")
    Object classData = List.of(constArray);
    Lookup newLookup;
    try {
      newLookup = lookup().defineHiddenClassWithClassData(bytes, classData, /* initialize= */ true);
    } catch (Throwable e) {
      throw new IllegalArgumentException(e);
    }
    return loadMethod(newLookup.lookupClass());
  }

  /// Creates a Loader suitable for the current JVM version.
  public static Loader newLoader() {
    return new Loader();
  }

  /// Prepares a newly-created loader to start constructing the method body. The new method will be
  /// defined as a static, public method in a new class in the package of the `lookup` argument.
  /// (The class name will be "GeneratedCode", but it is not accessible by that name and multiple
  /// Loaders can be constructed in the same package without conflicting.)
  ///
  /// @param methodName will appear in any stack traces thrown while executing the constructed
  ///   method
  /// @param methodType the signature of the constructed method
  /// @param lookup a [MethodHandles.Lookup] for the package in which the generated class will be
  ///   loaded, and which it will have access to
  /// @param sourceFileName if non-null, will appear in any stack traces thrown while executing the
  ///   constructed method
  public void initialize(
      String methodName, MethodType methodType, Lookup lookup, String sourceFileName) {
    Preconditions.checkState(classWriter == null);
    this.methodName = methodName;
    this.methodType = methodType;
    this.lookup = lookup;
    String className =
        lookup.lookupClass().getPackageName().replace('.', '/') + "/" + "GeneratedCode";
    classWriter = new ClassWriter(0);
    classWriter.visit(
        classFileVersion(),
        Opcodes.ACC_FINAL + Opcodes.ACC_SUPER + Opcodes.ACC_PUBLIC,
        className,
        null,
        "java/lang/Object",
        null);
    if (sourceFileName != null) {
      classWriter.visitSource(sourceFileName, null);
    }
    methodVisitor =
        classWriter.visitMethod(
            Opcodes.ACC_STATIC + Opcodes.ACC_PUBLIC,
            methodName,
            methodType.toMethodDescriptorString(),
            null,
            null);
    methodVisitor.visitCode();
  }

  /// Returns the string used by the JVM to identify the given class.
  static String asmType(Class<?> type) {
    // Replace with type.descriptorString() after we're off Java 11
    return org.objectweb.asm.Type.getInternalName(type);
  }

  /// Returns the [MethodVisitor] that should be used to write the method's body. Only valid after
  /// calling [#initialize].
  MethodVisitor methodVisitor() {
    return methodVisitor;
  }

  /// Returns the `lookup` argument that was passed to [#initialize].
  Lookup lookup() {
    return lookup;
  }

  /// Returns the JVM byte encoding of the class to be loaded. Also saves those bytes in
  /// `debugInfo`.
  private byte[] classWriterToBytes(DebugInfo debugInfo) {
    methodVisitor().visitEnd();
    classWriter.visitEnd();
    byte[] bytes = classWriter.toByteArray();
    debugInfo.classBytes = bytes;
    return bytes;
  }

  /// Given the newly-loaded class, returns a MethodHandle for the method.
  private MethodHandle loadMethod(Class<?> newClass) {
    try {
      return lookup().findStatic(newClass, methodName, methodType);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
