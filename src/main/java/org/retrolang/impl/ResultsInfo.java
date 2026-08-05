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

/// Information passed to a method implementation about how its results will be represented.
public interface ResultsInfo {

  /// A trivial ResultsInfo that returns default values for every TProperty.
  public static final ResultsInfo EMPTY =
      new ResultsInfo() {
        @Override
        public <T> T result(int resultNum, TProperty<T> property) {
          return property.fn.apply(Template.EMPTY);
        }
      };

  /// Returns the given property applied to the specified result.
  <T> T result(int resultNum, TProperty<T> property);

  /// Returns the given property applied to the first result.
  default <T> T result(TProperty<T> property) {
    return result(0, property);
  }
}
