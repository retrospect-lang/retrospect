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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.regex.Pattern;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.TraceClassVisitor;

/**
 * A DebugInfo aggregates information from a single CodeBuilder instance that may be useful for
 * interpreting stack traces (e.g. from an exception thrown while running the generated code) or
 * otherwise understanding CodeBuilder's results.
 */
public class DebugInfo {
  /**
   * A Block listing after optimizations but before finalization and register assignment. Only
   * created if {@link CodeBuilder#verbose} is set.
   */
  public String postOptimization;

  /**
   * A final Block listing, after all optimizations have been applied and after register assignment.
   * Block numbers in this listing are used as "line numbers" in the generated code.
   */
  public String blocks;

  /** The JVM bytecode emitted by the CodeBuilder. */
  public byte[] classBytes;

  /** The constants referenced by {@link #classBytes}. */
  public Object[] constants;

  /** Spam in the TraceClassVisitor output that we throw away. */
  private static final Pattern TRACE_CLUTTER =
      Pattern.compile(
          """
          // class version .*
          // access flags .*
          public final class .*
          \\s*(// compiled from:.*
          \\s*)?// access flags .*
          """);

  /**
   * Called with the value of {@link #classBytes}, returns a (relatively) human-readable listing of
   * the JVM instructions.
   */
  public static String printClassBytes(byte[] bytes) {
    if (bytes == null) {
      return "No bytecode\n";
    }
    // Most of the work is done by ASM's TraceClassVisitor.
    StringWriter output = new StringWriter();
    new ClassReader(bytes).accept(new TraceClassVisitor(new PrintWriter(output)), 0);
    String s = output.toString();
    // We make a few tweaks to the output to make it a little less verbose.
    s = TRACE_CLUTTER.matcher(s).replaceFirst("");
    s =
        s.replaceAll(
            "INVOKEVIRTUAL java/lang/invoke/MethodHandle\\.invokeExact.*",
            "INVOKEVIRTUAL MethodHandle.invokeExact");
    s = s.replaceAll("org.retrolang.impl", "cgeri");
    if (s.endsWith("}\n")) {
      s = s.substring(0, s.length() - 2);
    }
    return s;
  }

  /**
   * Returns the number of digits in the decimal representation of the given int. Not intended for
   * use with negative numbers (the current implementation will always return 1).
   */
  public static int numDigits(int n) {
    int result = 1;
    for (long powerOfTen = 10; n >= powerOfTen; powerOfTen *= 10) {
      ++result;
    }
    return result;
  }

  /**
   * Called with the value of {@link #constants}, returns a (relatively) readable listing of them.
   */
  @SuppressWarnings("AvoidObjectArrays")
  public static String printConstants(Object[] objs, MethodHandles.Lookup lookup) {
    if (objs == null || objs.length == 0) {
      return "No constants\n";
    }
    StringBuilder result = new StringBuilder();
    String fmt = "%" + numDigits(objs.length - 1) + "d";
    for (int i = 0; i < objs.length; i++) {
      Object obj = objs[i];
      if (obj == null) {
        continue;
      }
      result.append("const ").append(String.format(fmt, i)).append(": ");
      if (obj instanceof MethodHandle mh) {
        if (mh.getClass().getSimpleName().equals("DirectMethodHandle")) {
          obj = lookup.revealDirect(mh);
        }
        obj = obj.toString().replaceAll("org.retrolang.impl", "cgeri");
      } else {
        result.append("(").append(obj.getClass().getSimpleName()).append(") ");
      }
      result.append(obj).append("\n");
    }
    return result.toString();
  }
}
