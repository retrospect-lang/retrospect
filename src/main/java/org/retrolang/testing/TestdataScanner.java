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

package org.retrolang.testing;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A {@link TestParameterValuesProvider} that provides {@link TestProgram} arguments to tests
 * consisting of each Retrospect source fragment in a directory of test files.
 */
public abstract class TestdataScanner extends TestParameterValuesProvider {

  private final Path dir;
  private final Pattern endComment;

  protected TestdataScanner(Path dir, Pattern endComment) {
    this.dir = dir;
    this.endComment = endComment;
  }

  /** A test program written in Retrospect. */
  public record TestProgram(String name, String code, @Nullable String comment) {
    @Override
    public final String toString() {
      return name();
    }
  }

  /**
   * Matches one or more blank lines and/or comments at the beginning of the program; we discard
   * these so that changes to them won't affect line numbers in generated code.
   */
  private static final Pattern LEADING_COMMENTS_PATTERN =
      Pattern.compile("(?:[ \\t]*(?:\\n|//[^\\n]*|/\\*.*?\\*/))+", Pattern.DOTALL);

  /**
   * Returns a list of {@link TestProgram}, each a [name, code, comment] tuple corresponding to a
   * code chunk from a ".r8t" file in our testdata directory.
   *
   * <p>Each code chunk must be followed by a chunk that matches {@code endComment}. The name is the
   * file name suffixed with the line number of the first non-comment line. The code is everything
   * from the first non-comment line up to the chunk matching {@code endComment}. The comment is the
   * first group of the {@code endComment} match.
   */
  @Override
  public final ImmutableList<TestProgram> provideValues(Context context) {
    Pattern runOnly = Pattern.compile(System.getProperty("runOnly", ".*"));
    String roc = System.getProperty("runOnlyComment");
    Pattern runOnlyComment = (roc == null) ? null : Pattern.compile(".*" + roc);
    Predicate<TestProgram> selected =
        program ->
            runOnly.matcher(program.name()).matches()
                && (program.comment() == null
                    || runOnlyComment == null
                    || runOnlyComment.matcher(program.comment()).lookingAt());
    try (Stream<Path> files = Files.list(dir)) {
      return files
          .flatMap(file -> allProgramsInFile(file).stream().filter(selected))
          .collect(toImmutableList());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns a list of {@link TestProgram}, each a [name, code, comment] tuple corresponding to a
   * code chunk from the given file. See {@link #provideValues()} for details.
   */
  private ImmutableList<TestProgram> allProgramsInFile(Path path) {
    String name = path.getFileName().toString();
    if (!name.endsWith(".r8t")) {
      return ImmutableList.of();
    }
    String source;
    try {
      source = Files.readString(path);
    } catch (IOException e) {
      // We could return a tuple with a null comment if we wanted to go on and test the code chunks
      // in other files, but this shouldn't happen.
      throw new AssertionError(e);
    }
    Matcher leadingCommentsMatcher = LEADING_COMMENTS_PATTERN.matcher(source);
    Matcher endCommentMatcher = endComment.matcher(source);
    int start = 0;
    int skippedLines = 0;
    ImmutableList.Builder<TestProgram> results = ImmutableList.builder();
    for (; ; ) {
      // Skip over any leading comments & blank lines, incrementing skippedLines appropriately.
      leadingCommentsMatcher.region(start, source.length());
      if (leadingCommentsMatcher.lookingAt()) {
        skippedLines += countNewLines(leadingCommentsMatcher.group());
        start = leadingCommentsMatcher.end();
      }
      // If we made it to the end, return all the chunks we found.
      if (start == source.length()) {
        break;
      }
      String testName = name + "+" + skippedLines;
      // Find the first COMPILE comment, which marks the end of this program.
      if (!endCommentMatcher.find(start)) {
        // Code without a following endComment.  Include an entry with a null comment, which will
        // show up as a failed test.
        results.add(new TestProgram(testName, source.substring(start), null));
        break;
      }
      String code = source.substring(start, endCommentMatcher.start());
      String comment = endCommentMatcher.group(1);
      results.add(new TestProgram(testName, code, comment));
      int newStart = endCommentMatcher.end();
      skippedLines += countNewLines(source.substring(start, newStart));
      start = newStart;
    }
    return results.build();
  }

  private static int countNewLines(String s) {
    return (int) s.chars().filter(c -> c == '\n').count();
  }
}
