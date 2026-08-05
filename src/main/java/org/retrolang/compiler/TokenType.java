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

import com.google.common.collect.ImmutableMap;
import org.antlr.v4.runtime.Vocabulary;

/// A statics-only class providing convenient access to ANTLR token types (which are just ints).
///
/// ANTLR's Vocabulary class doesn't seem to provide any direct way to get the token type for a
/// particular token, so we just build a map of them (there are O(60)) and look up each one we're
/// interested in once.
class TokenType {

  // Statics only
  private TokenType() {}

  /// A Map from token name to token type. Token names are either literals enclosed in single quotes
  /// (e.g. "`'+'`") or symbolic names (e.g. "`NUMBER`").
  static final ImmutableMap<String, Integer> MAP;

  static {
    // The ANTLR Vocabulary class provides a way to map token types to their names.  We count on
    // token types being densely allocated starting from 1 to find them all.
    Vocabulary vocab = RetrospectLexer.VOCABULARY;
    ImmutableMap.Builder<String, Integer> builder = ImmutableMap.builder();
    for (int i = 1; ; i++) {
      String s = vocab.getLiteralName(i);
      if (s == null) {
        s = vocab.getSymbolicName(i);
        if (s == null) {
          // This seems to be a reliable way to determine that there are no more token types.
          break;
        }
      }
      builder.put(s, i);
    }
    MAP = builder.buildOrThrow();
  }

  /// Returns the token type for the given name. Throws an exception if there is no such token name.
  static int of(String name) {
    Integer result = MAP.get(name);
    if (result == null) {
      throw new IllegalArgumentException("No token named " + name);
    }
    return result;
  }

  static final int DOT = of("'.'");
  static final int UNDERSCORE = of("'_'");
  static final int AT = of("'@'");
  static final int MINUS = of("'-'");
  static final int GREATER_THAN = of("'>'");
  static final int GREATER_THAN_OR_EQUALS = of("'>='");
  static final int LESS_THAN_OR_EQUALS = of("'<='");
  static final int EQUALS = of("'='");
  static final int DOUBLE_LESS_THAN = of("'<<'");
  static final int NOT_EQUALS = of("'!='");
  static final int CONCAT_EQUALS = of("'&='");
  static final int PIPE_EQUALS = of("'|='");
  static final int LEFT_SQUARE = of("'['");
  static final int RIGHT_CURLY = of("'}'");
  static final int KEYWORD_AND = of("'and'");
  static final int KEYWORD_COMPOUND = of("'compound'");
  static final int KEYWORD_FUNCTION = of("'function'");
  static final int KEYWORD_IS = of("'is'");
  static final int KEYWORD_NOT = of("'not'");
  static final int KEYWORD_OPEN = of("'open'");
  static final int KEYWORD_OR = of("'or'");
  static final int KEYWORD_PRIVATE = of("'private'");
  static final int KEYWORD_PROCEDURE = of("'procedure'");
  static final int KEYWORD_SINGLETON = of("'singleton'");
  static final int NEW_LINE = of("NL");
}
