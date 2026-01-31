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

package org.retrolang.util;

import java.util.function.IntFunction;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Static-only class with methods for building, escaping, and unescaping strings. */
public class StringUtil {

  private StringUtil() {}

  /**
   * Constructs a string by calling the given IntFunction for each int from 0 to size-1, calling
   * {@code String.valueOf()} on each element, separating them with {@code ", "}, and adding the
   * given prefix and suffix.
   */
  public static String joinElements(
      String prefix, String suffix, int size, IntFunction<Object> elements) {
    assert size >= 0;
    return IntStream.range(0, size)
        .mapToObj(i -> String.valueOf(elements.apply(i)))
        .collect(Collectors.joining(", ", prefix, suffix));
  }

  /**
   * Given a (quoted) Retrospect string literal, returns the corresponding Java string.
   *
   * <p>Caller is responsible for ensuring that the literal is well formed, i.e. that it begins and
   * ends with {@code "} and that any {@code \} characters are part of a valid escape sequence.
   */
  public static String unescape(String s) {
    // Drop the beginning and ending '"'.
    s = s.substring(1, s.length() - 1);
    // If there are no escapes, we're done.
    int escape = s.indexOf('\\');
    if (escape < 0) {
      return s;
    }
    StringBuilder result = new StringBuilder(s.length() - 1);
    int next = 0;
    for (; ; ) {
      result.append(s, next, escape);
      char c = s.charAt(escape + 1);
      next = escape + 2;
      switch (c) {
        case '"':
        case '\'':
        case '\\':
          result.append(c);
          break;
        case 'b':
          result.append('\b');
          break;
        case 't':
          result.append('\t');
          break;
        case 'n':
          result.append('\n');
          break;
        case 'f':
          result.append('\f');
          break;
        case 'r':
          result.append('\r');
          break;
        case 'u':
          result.appendCodePoint(Integer.parseUnsignedInt(s.substring(next, next + 4), 16));
          next += 4;
          break;
        default:
          throw new AssertionError();
      }
      escape = s.indexOf('\\', next);
      if (escape < 0) {
        return result.append(s, next, s.length()).toString();
      }
    }
  }

  private static final Pattern NEEDS_ESCAPE = Pattern.compile("[\"\b\t\n\f\r\\\\]");

  private static String escapeChar(MatchResult mr, String s) {
    return switch (s.charAt(mr.start())) {
      case '\"' -> "\\\\\"";
      case '\\' -> "\\\\\\\\";
      case '\b' -> "\\\\b";
      case '\t' -> "\\\\t";
      case '\n' -> "\\\\n";
      case '\f' -> "\\\\f";
      case '\r' -> "\\\\r";
      default -> throw new AssertionError();
    };
  }

  /** Given a string, return an equivalent quoted Retrospect string literal. */
  public static String escape(String s) {
    if (s == null) {
      return "null";
    }
    Matcher m = NEEDS_ESCAPE.matcher(s);
    String escaped = m.replaceAll(mr -> escapeChar(mr, s));
    return "\"" + escaped + "\"";
  }

  /**
   * Call {@link String#valueOf} but swallow any errors; intended for debugging or formatting errors
   * when the system is already known to be in a bad state.
   *
   * <p>If {@code x} is an array of objects, formats as {@link java.util.Arrays#toString} (but uses
   * {@link #safeToString} for each element).
   */
  public static String safeToString(Object x) {
    if (x instanceof Object[] array) {
      return joinElements("[", "]", array.length, i -> safeToString(array[i]));
    }
    try {
      return String.valueOf(x);
    } catch (RuntimeException | AssertionError nested) {
      return "(can't print)";
    }
  }

  private static final int ID_LENGTH = 4;

  /** Returns a short, arbitrary string useful for identifying this object. */
  public static String id(Object x) {
    if (x == null) {
      return "null";
    }
    String hash = Integer.toHexString(System.identityHashCode(x));
    return hash.substring(Math.max(0, hash.length() - ID_LENGTH));
  }
}
