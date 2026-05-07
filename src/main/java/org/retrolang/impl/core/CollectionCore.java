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

package org.retrolang.impl.core;

import static org.retrolang.impl.Value.addRef;

import org.retrolang.impl.BaseType;
import org.retrolang.impl.BuiltinMethod;
import org.retrolang.impl.BuiltinMethod.Caller;
import org.retrolang.impl.BuiltinMethod.Fn;
import org.retrolang.impl.Condition;
import org.retrolang.impl.Core;
import org.retrolang.impl.Err;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.impl.RC;
import org.retrolang.impl.RValue;
import org.retrolang.impl.Singleton;
import org.retrolang.impl.TState;
import org.retrolang.impl.Value;
import org.retrolang.impl.VmFunctionBuilder;

/** Core methods providing support for collections. */
public final class CollectionCore {

  /**
   * {@code private compound Filter is Lambda}
   *
   * <p>Filter wraps a Boolean-valued Lambda to create a Lambda that returns either its argument (if
   * the wrapped Lambda returns True) or Absent (if it returns False).
   */
  @Core.Private static final BaseType.Named FILTER = Core.newBaseType("Filter", 1, Core.LAMBDA);

  /** {@code function filter(lambda)} */
  @Core.Public
  static final VmFunctionBuilder filterFn = VmFunctionBuilder.create("filter", 1).isOpen();

  /** {@code private compound WithKeys is Collection} */
  @Core.Private
  static final BaseType.Named WITH_KEYS = Core.newBaseType("WithKeys", 1, Core.COLLECTION);

  /** {@code private compound WithKeysMatrix is Matrix} */
  @Core.Private
  static final BaseType.Named WITH_KEYS_MATRIX = Core.newBaseType("WithKeysMatrix", 1, Core.MATRIX);

  /** {@code function withKeys(collection)} */
  @Core.Public
  static final VmFunctionBuilder withKeys = VmFunctionBuilder.create("withKeys", 1).isOpen();

  @Core.Public static final VmFunctionBuilder join = VmFunctionBuilder.create("join", 2).isOpen();

  /**
   * {@code private compound TransformedLambda is Lambda}
   *
   * <p>Elements are {@code first}, {@code second} (both Lambdas).
   *
   * <p>Applying a TransformedLambda applies its elements in sequence (aka lambda composition). If
   * the result of {@code first} is Absent, {@code second} is skipped and the overall result is
   * Absent.
   */
  @Core.Private
  static final BaseType.Named TRANSFORMED_LAMBDA =
      Core.newBaseType("TransformedLambda", 2, Core.LAMBDA);

  /**
   * {@code private compound TransformedCollection is Collection}
   *
   * <p>Elements are {@code base} (a Collection) and {@code lambda}.
   *
   * <p>A TransformedCollection has the same keys as its {@code base}; its value at each key is the
   * the result of applying {@code lambda} to the corresponding value of {@code base} (unless that
   * value is Absent).
   *
   * <p>If Retrospect allowed compound types to be subtypes of other compound types it would make
   * sense for TransformedCollection to be a subtype of TransformedLambda, and at least one method
   * definition (AtTransformed) treats them that way. Without complicating the type system we could
   * also get this effect by making TransformedLambda a union containing two compounds
   * (TransformedCollection and TransformedNonCollection), but just using a more complex
   * MethodPredicate (and implicitly relying on them having their elements in the same order) seems
   * like the least clunky alternative.
   */
  @Core.Private
  static final BaseType.Named TRANSFORMED_COLLECTION =
      Core.newBaseType("TransformedCollection", 2, Core.COLLECTION);

  /**
   * {@code private compound TransformedMatrix is Matrix}
   *
   * <p>Elements are {@code base}, {@code lambda}.
   *
   * <p>Following the comments on {@link #TRANSFORMED_COLLECTION}, this would be a subtype of
   * TransformedCollection if such a thing were possible.
   */
  @Core.Private
  public static final BaseType.Named TRANSFORMED_MATRIX =
      Core.newBaseType("TransformedMatrix", 2, Core.MATRIX);

  /**
   * {@code private compound PipedCollection is Collection}
   *
   * <p>Elements are {@code base}, {@code step}.
   *
   * <p>{@code step} is not a Lambda (if it were, we would use a TransformedCollection or
   * TransformedMatrix). The resulting collection does not implement {@code at()}, {@code size()},
   * or {@code keys()} the only thing you can really do with it is iterate through its elements.
   */
  @Core.Private
  public static final BaseType.Named PIPED_COLLECTION =
      Core.newBaseType("PipedCollection", 2, Core.COLLECTION);

  /**
   * {@code private compound JoinedCollection is Collection}
   *
   * <p>Elements are {@code c1}, {@code c2}.
   */
  @Core.Private
  static final BaseType.Named JOINED_COLLECTION =
      Core.newBaseType("JoinedCollection", 2, Core.COLLECTION);

  /**
   * {@code private compound JoinedMatrix is Matrix}
   *
   * <p>Elements are {@code c1}, {@code c2}.
   *
   * <p>Following the comments on {@link #TRANSFORMED_COLLECTION}, this would be a subtype of
   * JoinedCollection if such a thing were possible.
   */
  @Core.Private
  static final BaseType.Named JOINED_MATRIX = Core.newBaseType("JoinedMatrix", 2, Core.MATRIX);

  /**
   * {@code private compound MatchFinder is Lambda}
   *
   * <p>Elements are {@code c2}, {@code eKind}.
   */
  @Core.Private
  static final BaseType.Named MATCH_FINDER = Core.newBaseType("MatchFinder", 2, Core.LAMBDA);

  /** {@code singleton DuplicateKey is Lambda} */
  @Core.Private
  public static final Singleton DUPLICATE_KEY = Core.newSingleton("DuplicateKey", Core.LAMBDA);

  /** {@code method filter(lambda) = Filter_(lambda) // v -> (lambda @ v) ? v : Absent} */
  @Core.Method("filter(Lambda)")
  static Value filter(TState tstate, @RC.In Value lambda) {
    return tstate.compound(FILTER, lambda);
  }

  /** {@code method at(Filter filter, x) = filter_ @ x ? x : Absent} */
  static class AtFilter extends BuiltinMethod {
    static final Caller at = new Caller("at:2", "afterAt");

    @Core.Method("at(Filter, _)")
    static void begin(TState tstate, Value filter, @RC.In Value x) {
      tstate.startCall(at, filter.element(0), addRef(x)).saving(x);
    }

    @Continuation
    static Value afterAt(TState tstate, Value pass, @Saved Value x) throws BuiltinException {
      if (pass instanceof RValue) {
        return Condition.fromBoolean(pass).choose(x, Core.ABSENT);
      } else {
        return testBoolean(pass) ? addRef(x) : Core.ABSENT;
      }
    }
  }

  /** {@code default method withKeys(c) = c is Matrix ? WithKeysMatrix_(c) : WithKeys_(c)} */
  @Core.Method("withKeys(Collection) default")
  static Value withKeys(TState tstate, @RC.In Value c) {
    return c.isa(Core.MATRIX)
        .choose(() -> tstate.compound(WITH_KEYS_MATRIX, c), () -> tstate.compound(WITH_KEYS, c));
  }

  /**
   * <pre>
   * method at(WithKeys wk, key) {
   *   v = wk_ @ key
   *   return v is Absent ? Absent : [key, v]
   * }
   * </pre>
   */
  static class AtWithKeys extends BuiltinMethod {
    static final Caller at = new Caller("at:2", "afterAt");

    @Core.Method("at(WithKeys, _)")
    static void begin(TState tstate, Value wk, @RC.In Value key) {
      tstate.startCall(at, wk.element(0), addRef(key)).saving(key);
    }

    @Continuation
    static Value afterAt(TState tstate, @RC.In Value v, @Saved @RC.In Value key) {
      return v.is(Core.ABSENT)
          .choose(
              () -> {
                tstate.dropValue(key);
                return Core.ABSENT;
              },
              () -> tstate.arrayValue(key, v));
    }
  }

  /**
   * <pre>
   * method element(WithKeysMatrix wk, Array key) {
   *   v = element(wk_, key)
   *   return v is Absent ? Absent : [key, v]
   * }
   * </pre>
   */
  static class ElementWithKeys extends BuiltinMethod {
    static final Caller element = new Caller("element:2", "afterElement");

    @Core.Method("element(WithKeysMatrix, Array)")
    static void begin(TState tstate, Value wk, @RC.In Value key) {
      tstate.startCall(element, wk.element(0), addRef(key)).saving(key);
    }

    @Continuation
    static Value afterElement(TState tstate, @RC.In Value v, @Saved @RC.In Value key) {
      return AtWithKeys.afterAt(tstate, v, key);
    }
  }

  /** {@code method keys(wk) (wk is WithKeys or wk is WithKeysMatrix) = keys(wk_)} */
  @Core.Method("keys(WithKeys|WithKeysMatrix)")
  static void keysWithKeys(TState tstate, Value wk, @Fn("keys:1") Caller keys) {
    tstate.startCall(keys, wk.element(0));
  }

  /**
   * <pre>
   * method iterator(wk, eKind, loop=, initialState=) (wk is WithKeys or wk is WithKeysMatrix) {
   *   if eKind is EnumerateValues {
   *     return iterator(wk_, EnumerateWithKeys, loop=, initialState=)
   *   }
   *   loop = transformLoop([k, v] -> [k, [k, v]], loop)
   *   return iterator(wk_, eKind, loop=, initialState=)
   * </pre>
   */
  @Core.Method("iterator(WithKeys|WithKeysMatrix, EnumerationKind, Loop, _)")
  static void iteratorWithKeys(
      TState tstate,
      Value wk,
      @RC.Singleton Value eKind,
      @RC.In Value loop,
      @RC.In Value initialState,
      @Fn("iterator:4") Caller iterator) {
    Value inner = wk.element(0);
    eKind
        .is(LoopCore.ENUMERATE_VALUES)
        .test(
            () ->
                tstate.startCall(iterator, inner, LoopCore.ENUMERATE_WITH_KEYS, loop, initialState),
            () -> {
              Value loop2 = tstate.compound(LoopCore.TRANSFORMED_LOOP, DUPLICATE_KEY, loop);
              tstate.startCall(iterator, inner, eKind, loop2, initialState);
            });
  }

  /** {@code method at(DuplicateKey, [k, v]) = [k, [k, v]] } */
  @Core.Method("at(DuplicateKey, Array)")
  static Value atDuplicateKey(TState tstate, Value duplicateKey, @RC.In Value pair)
      throws BuiltinException {
    Err.NOT_PAIR.unless(pair.isArrayOfLength(2));
    Value key = pair.element(0);
    return tstate.arrayValue(key, pair);
  }

  /**
   * <pre>
   * method pipe(PipelineStep left, PipelineStep right) default {
   *   if left is Collection {
   *     if right is not Lambda {
   *       return PipedCollection_({base: left, step: right})
   *     }
   *     if left is TransformedCollection or left is TransformedMatrix {
   *       // Rather than nest TransformedCollection we compose the Lambdas.
   *       // There's no clear reason to prefer one or the other.
   *       right = left_.lambda | right
   *       left = left_.base
   *     }
   *     if left is Matrix {
   *       return TransformedMatrix_({base: left, lambda: right})
   *     } else {
   *       return TransformedCollection_({base: left, lambda: right})
   *     }
   *   }
   *   if left is Lambda and right is Lambda {
   *     return TransformedLambda_({first: left, second: right})
   *   } else {
   *     return CompoundStep_({step1: left, step2: right})
   *   }
   * }
   * </pre>
   */
  @Core.Method("pipe(PipelineStep, PipelineStep) default")
  static Value pipeStepStep(TState tstate, @RC.In Value left, @RC.In Value right) {
    return left.isa(Core.COLLECTION)
        .choose(
            () -> pipeCollectionStep(tstate, left, right),
            () ->
                left.isa(Core.LAMBDA)
                    .and(right.isa(Core.LAMBDA))
                    .choose(
                        () -> tstate.compound(TRANSFORMED_LAMBDA, left, right),
                        () -> tstate.compound(LoopCore.COMPOUND_STEP, left, right)));
  }

  @RC.Out
  private static Value pipeCollectionStep(TState tstate, @RC.In Value left, @RC.In Value right) {
    return right
        .isa(Core.LAMBDA)
        .choose(
            () ->
                left.isa(TRANSFORMED_COLLECTION)
                    .or(left.isa(TRANSFORMED_MATRIX))
                    .choose(
                        () -> {
                          Value newLeft = left.element(0);
                          Value newRight =
                              tstate.compound(TRANSFORMED_LAMBDA, left.element(1), right);
                          tstate.dropValue(left);
                          return pipeCollectionLambda(tstate, newLeft, newRight);
                        },
                        () -> pipeCollectionLambda(tstate, left, right)),
            () -> tstate.compound(PIPED_COLLECTION, left, right));
  }

  @RC.Out
  private static Value pipeCollectionLambda(TState tstate, @RC.In Value left, @RC.In Value right) {
    return left.isa(Core.MATRIX)
        .choose(
            () -> tstate.compound(TRANSFORMED_MATRIX, left, right),
            () -> tstate.compound(TRANSFORMED_COLLECTION, left, right));
  }

  /**
   * <pre>
   * method at(TransformedLambda tLambda, x) {
   *   x = tLambda_.first @ x
   *   return x is Absent ? Absent : tLambda_.second @ x
   * }
   *
   * method at(tc, x) (tc is TransformedCollection) {
   *   x = tc_.base @ x
   *   return x is Absent ? Absent : tc_.lambda @ x
   * }
   * </pre>
   *
   * <p>Note that we use the same method for TransformedLambda and TransformedCollection; see
   * comment on TransformedCollection. Also note that this method is *not* used for
   * TransformedMatrix; that uses the default {@code at(Matrix, _)} method in MatrixCore to ensure
   * that ranges are handled properly.
   */
  static class AtTransformed extends BuiltinMethod {
    static final Caller at = new Caller("at:2", "afterAt");

    @Core.Method("at(TransformedLambda|TransformedCollection, _)")
    static void begin(TState tstate, Value tLambda, @RC.In Value x) {
      tstate.startCall(at, tLambda.element(0), x).saving(tLambda.element(1));
    }

    @Continuation
    static void afterAt(
        TState tstate, @RC.In Value x, @Saved Value second, @Fn("at:2") Caller at2) {
      x.is(Core.ABSENT)
          .test(
              () -> tstate.setResult(Core.ABSENT), () -> tstate.startCall(at2, addRef(second), x));
    }
  }

  /**
   * <pre>
   * method element(TransformedMatrix tm, Array x) {
   *   x = element(tm_.base, x)
   *   return x is Absent ? Absent : tm_.lambda @ x
   * }
   * </pre>
   */
  static class ElementTransformed extends BuiltinMethod {
    static final Caller element = new Caller("element:2", "afterElement");

    @Core.Method("element(TransformedMatrix, Array)")
    static void begin(TState tstate, Value tm, @RC.In Value x) {
      tstate.startCall(element, tm.element(0), x).saving(tm.element(1));
    }

    @Continuation
    static void afterElement(
        TState tstate, @RC.In Value x, @Saved Value lambda, @Fn("at:2") Caller at2) {
      AtTransformed.afterAt(tstate, x, lambda, at2);
    }
  }

  /**
   * <pre>
   * method keys(tc) (tc is TransformedCollection or tc is TransformedMatrix) = keys(tc_.base)
   * </pre>
   */
  @Core.Method("keys(TransformedCollection|TransformedMatrix)")
  static void keysTransformed(TState tstate, Value tc, @Fn("keys:1") Caller keys) {
    tstate.startCall(keys, tc.element(0));
  }

  /**
   * <pre>
   * method size(tc) (tc is TransformedCollection or tc is TransformedMatrix) = size(tc_.base)
   * </pre>
   */
  @Core.Method("size(TransformedCollection|TransformedMatrix)")
  static void sizeTransformed(TState tstate, Value tc, @Fn("size:1") Caller size) {
    tstate.startCall(size, tc.element(0));
  }

  /**
   * <pre>
   * method iterator(tc, eKind, loop=, initialState=)
   *      (tc is TransformedCollection or tc is TransformedMatrix or tc is PipedCollection) {
   *   addLoopStep(tc_.step, eKind, loop=, initialState=)
   *   return iterator(tc_.base, eKind, loop=, initialState=)
   * }
   * </pre>
   */
  static class IteratorPiped extends BuiltinMethod {
    static final Caller addLoopStep = new Caller("addLoopStep:4", "afterAddLoopStep");

    @Core.Method(
        "iterator(TransformedCollection|TransformedMatrix|PipedCollection, EnumerationKind, Loop,"
            + " _)")
    static void begin(
        TState tstate,
        Value tc,
        @RC.Singleton Value eKind,
        @RC.In Value loop,
        @RC.In Value initialState) {
      Value base = tc.element(0);
      Value step = tc.element(1);
      tstate.startCall(addLoopStep, step, eKind, loop, initialState).saving(base, eKind);
    }

    @Continuation
    static void afterAddLoopStep(
        TState tstate,
        @RC.In Value loop,
        @RC.In Value initialState,
        @Saved @RC.In Value base,
        @RC.Singleton Value eKind,
        @Fn("iterator:4") Caller iterator) {
      tstate.startCall(iterator, base, eKind, loop, initialState);
    }
  }

  /**
   * <pre>
   * method at(JoinedCollection jc, key) {
   *   x1 = jc_.c1 @ key
   *   x2 = jc_.c2 @ key
   *   return (x1 is Absent and x2 is Absent) ? Absent : [x1, x2]
   * }
   * </pre>
   */
  static class AtJoinedCollection extends BuiltinMethod {
    static final Caller atC1 = new Caller("at:2", "afterAtC1");
    static final Caller atC2 = new Caller("at:2", "afterAtC2");

    @Core.Method("at(JoinedCollection, _)")
    static void begin(TState tstate, Value jc, @RC.In Value key) {
      tstate.startCall(atC1, jc.element(0), addRef(key)).saving(jc.element(1), key);
    }

    @Continuation
    static void afterAtC1(
        TState tstate, @RC.In Value x1, @Saved @RC.In Value c2, @RC.In Value key) {
      tstate.startCall(atC2, c2, key).saving(x1);
    }

    @Continuation(order = 2)
    static Value afterAtC2(TState tstate, @RC.In Value x2, @Saved @RC.In Value x1) {
      return x1.is(Core.ABSENT)
          .and(x2.is(Core.ABSENT))
          .choose(() -> Core.ABSENT, () -> tstate.arrayValue(x1, x2));
    }
  }

  /**
   * <pre>
   * method element(JoinedMatrix jc, key) {
   *   x1 = element(jc_.c1, key)
   *   x2 = element(jc_.c2, key)
   *   return (x1 is Absent and x2 is Absent) ? Absent : [x1, x2]
   * }
   * </pre>
   */
  static class ElementJoinedMatrix extends BuiltinMethod {
    static final Caller elementC1 = new Caller("element:2", "afterElementC1");
    static final Caller elementC2 = new Caller("element:2", "afterElementC2");

    @Core.Method("element(JoinedMatrix, _)")
    static void begin(TState tstate, Value jc, @RC.In Value key) {
      tstate.startCall(elementC1, jc.element(0), addRef(key)).saving(jc.element(1), key);
    }

    @Continuation
    static void afterElementC1(
        TState tstate, @RC.In Value x1, @Saved @RC.In Value c2, @RC.In Value key) {
      tstate.startCall(elementC2, c2, key).saving(x1);
    }

    @Continuation(order = 2)
    static Value afterElementC2(TState tstate, @RC.In Value x2, @Saved @RC.In Value x1) {
      return x1.is(Core.ABSENT)
          .and(x2.is(Core.ABSENT))
          .choose(() -> Core.ABSENT, () -> tstate.arrayValue(x1, x2));
    }
  }

  /**
   * <pre>
   * method iterator(jc, eKind, loop=, initialState=) (jc is JoinedCollection or jc is JoinedMatrix) {
   *   loop = transformLoop(MatchFinder_({c2: jc_.c2, eKind: eKind}), loop)
   *   return iterator(jc_.c1, EnumerateAllKeys, loop=, initialState)
   * }
   * </pre>
   *
   * <p>Note that this implementation assumes that the joined collections have the same key set,
   * which is true for the only joined collections we have implemented so far (matrices). If we
   * start supporting joins between collections with different key sets we will need to revisit
   * this.
   */
  @Core.Method("iterator(JoinedCollection|JoinedMatrix, EnumerationKind, Loop, _)")
  static void iteratorJoined(
      TState tstate,
      Value jc,
      @RC.Singleton Value eKind,
      @RC.In Value loop,
      @RC.In Value initialState,
      @Fn("iterator:4") Caller iterator) {
    Value lambda = tstate.compound(MATCH_FINDER, jc.element(1), eKind);
    loop = tstate.compound(LoopCore.TRANSFORMED_LOOP, lambda, loop);
    tstate.startCall(iterator, jc.element(0), LoopCore.ENUMERATE_ALL_KEYS, loop, initialState);
  }

  /**
   * <pre>
   * method at(MatchFinder mf, [key, v1]) {
   *   v2 = mf_.c2 @ key
   *   if v1 is Absent and v2 is Absent and mf_.eKind is not EnumerateAllKeys {
   *     return Absent
   *   }
   *   v = [v1, v2]
   *   return (eKind is EnumerateValues) ? v : [key, v]
   * }
   * </pre>
   */
  static class AtMatchFinder extends BuiltinMethod {
    static final Caller at = new Caller("at:2", "afterAt");

    @Core.Method("at(MatchFinder, Array)")
    static void begin(TState tstate, Value mf, Value pair) throws BuiltinException {
      Err.NOT_PAIR.unless(pair.isArrayOfLength(2));
      Value key = pair.element(0);
      Value v1 = pair.element(1);
      Value c2 = mf.element(0);
      Value eKind = mf.element(1);
      tstate.startCall(at, c2, addRef(key)).saving(key, v1, eKind);
    }

    @Continuation
    static void afterAt(
        TState tstate,
        @RC.In Value v2,
        @Saved @RC.In Value key,
        @RC.In Value v1,
        @RC.Singleton Value eKind) {
      v1.is(Core.ABSENT)
          .and(v2.is(Core.ABSENT))
          .and(eKind.is(LoopCore.ENUMERATE_ALL_KEYS).not())
          .test(
              () -> {
                tstate.dropValue(key);
                tstate.setResult(Core.ABSENT);
              },
              () -> {
                Value v = tstate.arrayValue(v1, v2);
                tstate.setResult(
                    eKind
                        .is(LoopCore.ENUMERATE_VALUES)
                        .choose(
                            () -> {
                              tstate.dropValue(key);
                              return v;
                            },
                            () -> tstate.arrayValue(key, v)));
              });
    }
  }

  private CollectionCore() {}
}
