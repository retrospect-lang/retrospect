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

/// All Retrospect language errors detected at compile time throw a CompileError.
public class CompileError extends RuntimeException {
  public final String msg;
  public final int lineNum;
  public final int charPositionInLine;

  public CompileError(String msg, int lineNum, int charPositionInLine) {
    super(msg);
    this.msg = msg;
    this.lineNum = lineNum;
    this.charPositionInLine = charPositionInLine;
  }

  @Override
  public String getMessage() {
    return String.format("%s (%s:%s)", msg, lineNum, charPositionInLine);
  }
}
