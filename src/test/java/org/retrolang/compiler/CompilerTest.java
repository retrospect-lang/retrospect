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

package org.retrolang.compiler;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.antlr.v4.runtime.CharStreams;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.retrolang.Vm;
import org.retrolang.testing.TestdataScanner;
import org.retrolang.testing.TestdataScanner.TestProgram;

/// Compiles Retrospect source code from each of the .r8t files in the testdata directory, based on
/// comments in the files.
@RunWith(TestParameterInjector.class)
public class CompilerTest {

  private static final Path TESTDATA = Path.of("src/test/java/org/retrolang/compiler/testdata");

  /// Each .r8t file is expected to have source code followed by a comment that begins "`/* COMPILE
  /// (...)`". The parentheses enclose argument names (if any) for the code block.
  ///
  /// There are three variants for the COMPILE comment:
  ///
  /// - With no additional information before the end of the comment: the test passes if the file
  ///   compiles successfully.
  /// - With an error message (e.g. "`COMPILE (): No initialization of 'x'`"): the test passes if
  ///   the compilation results in an error containing the given message.
  /// - With expected output (e.g. "`COMPILE (n):`" followed by VM instructions up to the end of the
  ///   comment): the test passes if the file compiles and the resulting VM instructions match.
  ///
  /// For convenience a single file may contain multiple source programs, each followed by a COMPILE
  /// comment; each is compiled independently (such a file is not actually valid Retrospect source,
  /// since it may contain e.g. multiple main programs or multiple definitions of the same type
  /// name).
  private static final Pattern COMMENT_PATTERN =
      Pattern.compile("\n/\\* COMPILE (.*?)\\*/\\n*", Pattern.DOTALL);

  /// A pattern to parse the first line of a COMPILE comment (beginning immediately after the "/\*
  /// COMPILE ").
  private static final Pattern FIRST_LINE_PATTERN =
      Pattern.compile("\\(([, a-z0-9_]*)\\) *(:.*\n?)?");

  @Test
  public void compileTestProgram(
      @TestParameter(valuesProvider = AllPrograms.class) TestProgram testProgram) {
    checkNotNull(testProgram.comment(), "No COMPILE comment found");
    Matcher partsMatcher = FIRST_LINE_PATTERN.matcher(testProgram.comment());
    assertWithMessage("Bad COMPILE comment").that(partsMatcher.lookingAt()).isTrue();
    String[] args = splitAndTrim(partsMatcher.group(1));
    String errMsg = partsMatcher.group(2);
    String expected = null;
    if (errMsg != null) {
      errMsg = errMsg.trim();
      if (errMsg.length() == 1) {
        errMsg = null;
        expected = testProgram.comment().substring(partsMatcher.end());
      } else {
        // Drop the colon
        errMsg = errMsg.substring(1);
        assertWithMessage("Noise after error message in COMPILE comment")
            .that(partsMatcher.end())
            .isEqualTo(testProgram.comment().length());
      }
    }
    MockVM vm = new MockVM();
    Vm.ModuleBuilder module = vm.newModule("(input)");
    try {
      Vm.InstructionBlock insts =
          Compiler.compile(
              CharStreams.fromString(testProgram.code()), testProgram.name(), vm, module, args);
      assertWithMessage("Expected error, compiled OK").that(errMsg).isNull();
      module.build();
      String result = module.toString();
      if (insts != null) {
        result = result + "\n---\n" + insts;
      }
      System.out.format("** %s:\n%s\n", testProgram.name(), result);
      if (expected != null) {
        assertWithMessage("Compilation results don't match")
            .that(cleanLines(result))
            .isEqualTo(cleanLines(expected));
      }
    } catch (CompileError e) {
      errMsg = (errMsg == null) ? "(no error expected)" : errMsg.trim();
      assertWithMessage("Unexpected error %s", e).that(e.getMessage()).startsWith(errMsg);
    }
  }

  /// Returns an Iterator of three-element arrays, each a [name, code, comment] tuple corresponding
  /// to a code chunk from a ".r8t" file in our testdata directory.
  public static final class AllPrograms extends TestdataScanner {
    public AllPrograms() {
      super(TESTDATA, COMMENT_PATTERN);
    }
  }

  private static final Pattern COMMA = Pattern.compile(" *, *");

  private static String[] splitAndTrim(String s) {
    s = s.trim();
    return s.isEmpty() ? new String[0] : COMMA.split(s);
  }

  /// Removes all whitespace at the beginning and end of lines in the given output, and removes all
  /// completely blank lines.
  private static String cleanLines(String output) {
    return output.replaceAll(" *\n[ \n]*", "\n").trim();
  }
}
