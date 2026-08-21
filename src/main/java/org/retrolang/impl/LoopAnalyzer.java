package org.retrolang.impl;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import org.retrolang.util.Bits;

/// To use LoopAnalyzer, call [#analyze] on a newly-constructed instance with symbols for the loop
/// and the item that will be passed to `nextState()`, then check the results by calling
/// [#filters()] and [#canUseLimit()].
public class LoopAnalyzer {

  /// A Scenario represents a combination of conditions that would lead to the point in the loop
  /// that is currently being analyzed.
  ///
  /// For example, if the first step in the loop is to test if `row.x > 7`, then the two possible
  /// next steps would be analyzed with different scenarios, one representing `row.x` being greater
  /// than 7 and the other representing it being less than or equal to 7.
  ///
  /// There are three kinds of conditions that might be included in a scenario:
  ///
  /// * Conditions that might be optimized by the collection are represented by a unique index,
  ///   assigned by a call to [#newSplit]. The [#splits] field of a Scenario can represent any
  ///   combination of the assigned splits being positive or negative (eee [Bits#bitForProp] for how
  ///   the bits are interpreted).
  ///
  /// * Some conditions that cannot be optimized by the collection may still be represented by an
  ///   index, assigned by a call to `newSplit(null)`. For example, even though a Parquet collection
  ///   cannot optimize a comparison between fields (such as `row.x > row.y`), representing it as
  ///   another split may enable the analysis to determine e.g. that `filter(row -> row.x > row.y
  ///   and row.y > 0)` could still use `row.y > 0` as a filter.
  ///
  /// * A condition that cannot be optimized and might lead to an error (such as a call to a method
  ///   that cannot be analyzed) creates a scenario in which no optimization will be possible, but
  ///   we may still want to continue analyzing to determine which symbols may be used. To represent
  ///   that we increment the Scenario's [#otherSplits] field.
  public record Scenario(Bits splits, int otherSplits) {
    /// Returns the number of times this scenario has been divided (due to a conditional branch or
    /// other unknown behavior).
    public int splitCount() {
      return splits.count() + otherSplits;
    }

    /// Don't construct arbitrarily large conjunctions; if the code is really that complex something
    /// has probably gone wrong, and we don't want to end up using exponential resources.
    static int MAX_CONJUNCTION_SIZE = 16;

    /// Returns the Scenarios that results from adding the positive and negative version of the
    /// specified condition to this Scenario. If the condition is already present, one of the
    /// results will be null and the other will be `this`.
    public Split split(int split) {
      Boolean prev = splits.getProp(split);
      if (prev != null) {
        return prev ? new Split(this, null) : new Split(null, this);
      }
      int size = splits.count();
      if (size >= MAX_CONJUNCTION_SIZE) {
        Scenario s = split();
        return new Split(s, s);
      }
      Scenario ifTrue = new Scenario(splits.setProp(split, true), otherSplits);
      Scenario ifFalse = new Scenario(splits.setProp(split, false), otherSplits);
      return new Split(ifTrue, ifFalse);
    }

    /// Returns a new Scenario by splitting on a non-representable condition.
    public Scenario split() {
      return new Scenario(splits, otherSplits + 1);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      for (int i : splits) {
        sb.append(first ? "{" : ", ")
            .append(Bits.propIsPositive(i) ? "" : "!")
            .append(Bits.propFromBit(i));
        first = false;
      }
      return sb.append(first ? "+" : "}+").append(otherSplits).toString();
    }
  }

  /// A pair of scenarios separated by some condition.
  public record Split(Scenario ifTrue, Scenario ifFalse) {}

  /// A Symbol represents the value of a variable during symbolic execution. The simplest
  /// implementation of Symbol, [ForValue], is used when the Value is known, but other
  /// implementations can be used to represent e.g. the collection element currently being
  /// processed.
  ///
  /// Since some Symbol values depend on the scenario, most methods on Symbol take the current
  /// scenario as an argument. Since many methods may return more than result (by splitting the
  /// scenario they are passed), they usually take a LoopAnalyzer argument and return their results
  /// by making one or more calls to [#addResult].
  public interface Symbol {

    /// Returns all possible BaseTypes for this symbol, or null if unknown.
    List<BaseType> baseTypes();

    /// Returns a Symbol representing the result of calling [Value#element] on this symbol's value.
    Symbol element(int index, Scenario scenario);

    /// Records that execution depends on the value of this symbol.
    void markUsed();

    /// `baseType` must be an element of [#baseTypes], and not the last element. Returns the
    /// scenarios in which this element is or is not the given baseType; either side of the split
    /// may be null.
    ///
    /// The default implementation always throws an exception, which is appropriate for Symbol
    /// implementations with only a single baseType.
    default Split isa(BaseType baseType, Scenario scenario) {
      throw new AssertionError();
    }

    /// Either calls `analyzer.addResult()` with the result of a call to `equal(this, other)` and
    /// returns true, or returns false. If this method returns true, it should have called
    /// [#markUsed] as appropriate on itself and on `other`. The default method returns false.
    default boolean equal(Symbol other, Scenario scenario, LoopAnalyzer analyzer) {
      return false;
    }

    /// Either calls `analyzer.addResult()` with the result of a call to `equal(this, other)` and
    /// returns true, or returns false. If this method returns true, it should have called
    /// [#markUsed] as appropriate. The default method returns false.
    ///
    /// `other` should not be a Singleton; comparisons with singletons will be handled by [#isa].
    default boolean equal(Value other, Scenario scenario, LoopAnalyzer analyzer) {
      return false;
    }

    /// Either calls `analyzer.addResult()` with the result of a call to `lessThan(this, other)` and
    /// returns true, or returns false. If this method returns true, it should have called
    /// [#markUsed] as appropriate on itself and on `other`. The default method returns false.
    default boolean lessThan(Symbol other, Scenario scenario, LoopAnalyzer analyzer) {
      return false;
    }

    /// Either calls `analyzer.addResult()` with the result of a call to `lessThan(other, this)` and
    /// returns true, or returns false. If this method returns true, it should have called
    /// [#markUsed] as appropriate. The default method returns false.
    default boolean greaterThan(Value other, Scenario scenario, LoopAnalyzer analyzer) {
      return false;
    }

    /// Either calls `analyzer.addResult()` with the result of a call to `at(this, arg)` and returns
    /// true, or returns false. If this method returns true, it should have called [#markUsed] as
    /// appropriate. The default implementation handles arrays and structs, and returns false for
    /// everything else.
    default boolean at(Value arg, Scenario scenario, LoopAnalyzer analyzer) {
      List<BaseType> baseTypes = baseTypes();
      // TODO: at present, implementations with multiple baseTypes may have to reimplement
      // this themselves; if that happens, consider rewriting this to be more flexible.
      if (baseTypes == null || baseTypes.size() != 1) {
        return false;
      }
      BaseType baseType = baseTypes.get(0);
      Symbol result = null;
      if (baseType.isArray()) {
        if (baseType.isCompositional() && arg.isArrayOfLength(1).asBoolean()) {
          int i = NumValue.asIntOrMinusOne(arg.peekElement(0));
          if (i > 0 && i <= baseType.size()) {
            result = element(i - 1, scenario);
          }
        }
      } else if (baseType instanceof StructType struct) {
        int i = StructType.keyToIndex(struct, arg);
        if (i >= 0) {
          result = element(i, scenario);
        }
      }
      if (result != null) {
        analyzer.addResult(result, scenario);
        return true;
      } else {
        return false;
      }
    }

    /// Calls `markUsed()` on each of the given symbols.
    static void markAll(Symbol... symbols) {
      for (Symbol s : symbols) {
        if (s != null) {
          s.markUsed();
        }
      }
    }

    /// A trivial Symbol implementation when the value is known.
    record ForValue(Value v) implements Symbol {
      @Override
      public List<BaseType> baseTypes() {
        return ImmutableList.of(v.baseType());
      }

      @Override
      public Symbol element(int i, Scenario scenario) {
        return new ForValue(v.peekElement(i));
      }

      @Override
      public void markUsed() {}

      @Override
      public boolean equal(Symbol other, Scenario scenario, LoopAnalyzer analyzer) {
        return other.equal(v, scenario, analyzer);
      }

      @Override
      public boolean equal(Value other, Scenario scenario, LoopAnalyzer analyzer) {
        analyzer.addResult(v.equals(other), scenario);
        return true;
      }

      @Override
      public boolean lessThan(Symbol other, Scenario scenario, LoopAnalyzer analyzer) {
        return other.greaterThan(v, scenario, analyzer);
      }

      @Override
      public boolean greaterThan(Value other, Scenario scenario, LoopAnalyzer analyzer) {
        if (v instanceof NumValue && other instanceof NumValue) {
          analyzer.addResult(NumValue.asDouble(v) > NumValue.asDouble(other), scenario);
          return true;
        } else if (v instanceof StringValue s1 && other instanceof StringValue s2) {
          analyzer.addResult(s1.value.compareTo(s2.value) > 0, scenario);
          return true;
        } else {
          return false;
        }
      }
    }

    /// A Symbol defined by a compositional baseType and a Symbol for each of its elements.
    record ForCompound(BaseType baseType, ImmutableList<Symbol> elements) implements Symbol {
      public ForCompound {
        assert baseType.size() == elements.size() && !baseType.isSingleton();
      }

      @Override
      public List<BaseType> baseTypes() {
        return ImmutableList.of(baseType);
      }

      @Override
      public Symbol element(int i, Scenario scenario) {
        return elements.get(i);
      }

      @Override
      public void markUsed() {
        elements.forEach(Symbol::markUsed);
      }

      @Override
      public boolean equal(Symbol other, Scenario scenario, LoopAnalyzer analyzer) {
        // If we're comparing this a symbol with the same baseType, we can do element-by-element
        // comparisons; otherwise throw up our hands.
        // TODO: this should first check whether this baseType uses the default implementation of
        // `equal()`, rather than assuming that it does.
        TypeCheck otherType = TypeCheck.of(other, scenario);
        if (otherType == null) {
          return false;
        }
        markUsed();
        other.markUsed();
        // The scenarios where all elements seen so far have been equal,
        // i.e. the ones where `equal()` might yet return true.
        List<Scenario> liveScenarios = otherType.isa(baseType);
        otherType.remaining().forEach(s -> analyzer.addResult(false, s));
        for (int i = 0; !liveScenarios.isEmpty(); i++) {
          int start = analyzer.resultsStart();
          // Call `equal` on the corresponding elements in each of the possible scenarios,
          // accumulating all the possible results.
          for (Scenario s : liveScenarios) {
            Symbol otherElement = other.element(i, s);
            if (otherElement == null) {
              analyzer.resultUnknownOrFail(s);
            } else {
              analyzer.analyzeEqual(elements.get(i), otherElement, s);
            }
          }
          if (i == baseType.size() - 1) {
            break;
          }
          // Any scenarios that returned `true` will be revisited in the next iteration.
          liveScenarios =
              analyzer.takeMatchingResults(TRUE_SYMBOL, start).stream()
                  .map(Result::scenario)
                  .toList();
        }
        return true;
      }

      @Override
      public boolean equal(Value other, Scenario scenario, LoopAnalyzer analyzer) {
        // It's easiest to just reuse the more general code.
        return equal(new ForValue(other), scenario, analyzer);
      }
    }
  }

  public abstract static class TypeCheck {
    public abstract List<Scenario> isa(Predicate<BaseType> predicate);

    public abstract List<Scenario> isa(BaseType baseType);

    public List<Scenario> isa(VmType vmType) {
      return isa(vmType::contains);
    }

    public List<Scenario> remaining() {
      List<Scenario> result = remaining;
      remaining = ImmutableList.of();
      return result;
    }

    public static TypeCheck of(Symbol symbol, Scenario scenario) {
      List<BaseType> baseTypes = symbol.baseTypes();
      if (baseTypes == null) {
        return null;
      }
      if (baseTypes.size() == 1) {
        return new Trivial(baseTypes.get(0), scenario);
      } else {
        return new NonTrivial(symbol, baseTypes, scenario);
      }
    }

    List<Scenario> remaining;

    private TypeCheck(List<Scenario> scenarios) {
      this.remaining = scenarios;
    }

    static class Trivial extends TypeCheck {
      final BaseType baseType;

      Trivial(BaseType baseType, Scenario scenario) {
        super(ImmutableList.of(scenario));
        this.baseType = baseType;
      }

      @Override
      public List<Scenario> isa(Predicate<BaseType> predicate) {
        return predicate.test(baseType) ? remaining() : ImmutableList.of();
      }

      @Override
      public List<Scenario> isa(BaseType baseType) {
        return (baseType == this.baseType) ? remaining() : ImmutableList.of();
      }
    }

    static class NonTrivial extends TypeCheck {
      final Symbol symbol;
      final List<BaseType> baseTypes;

      NonTrivial(Symbol symbol, List<BaseType> baseTypes, Scenario scenario) {
        super(new ArrayList<>(2));
        remaining.add(scenario);
        this.symbol = symbol;
        this.baseTypes = baseTypes;
      }

      public List<Scenario> isa(Predicate<BaseType> predicate) {
        if (remaining.isEmpty()) {
          return ImmutableList.of();
        }
        Bits goodTypes =
            Bits.fromPredicate(baseTypes.size() - 1, i -> predicate.test(baseTypes.get(i)));
        if (goodTypes.isEmpty()) {
          return ImmutableList.of();
        } else if (goodTypes.count() == baseTypes.size()) {
          return remaining();
        }
        return doTest(goodTypes);
      }

      public List<Scenario> isa(BaseType baseType) {
        if (remaining.isEmpty()) {
          return ImmutableList.of();
        }
        int i = baseTypes.indexOf(baseType);
        if (i < 0) {
          return ImmutableList.of();
        } else {
          return doTest(Bits.of(i));
        }
      }

      private List<Scenario> doTest(Bits goodTypes) {
        int last = baseTypes.size() - 1;
        boolean lastIsGood = goodTypes.test(last);
        while (goodTypes.test(last - 1) == lastIsGood) {
          last--;
        }
        int n = remaining.size();
        assert last > 0 && n > 0;
        Scenario[] scenarios = remaining.toArray(new Scenario[n]);
        remaining = new ArrayList<>(n);
        ArrayList<Scenario> result = new ArrayList<>(n);
        for (Scenario scenario : scenarios) {
          for (int i = 0; ; i++) {
            if (i == last) {
              (lastIsGood ? result : remaining).add(scenario);
              break;
            }
            Split split = symbol.isa(baseTypes.get(i), scenario);
            if (split.ifTrue != null) {
              (goodTypes.test(i) ? result : remaining).add(split.ifTrue);
            }
            scenario = split.ifFalse;
            if (scenario == null) {
              break;
            }
          }
        }
        return result;
      }
    }
  }

  /// Each call to [#addResult] saves a pair of a scenario and the result returned in that scenario.
  public record Result(Symbol symbol, Scenario scenario) {}

  /// The total weight of all scenarios that have passed to [#fails]. In the very unlikely event
  /// that the calculation underflows (i.e. we have scenarios with a split count > 60) this will be
  /// odd; otherwise it will be even.
  private long failedWeight;

  /// The possible results of the call currently being analyzed.
  private final List<Result> results = new ArrayList<>();

  /// Identifying information for each identified split. These are not interpreted by the
  /// LoopAnalyzer; it just stores them for the client's use.
  private final List<Object> splitIds = new ArrayList<>();

  /// The number of steps that have been processed by [#analyzeInstructionBlock]; if this reaches
  /// [#STEP_LIMIT] the analysis will be cut short to prevent any potential for exponential (or
  /// infinite) resource consumption.
  private int stepCounter;

  /// The maximum number of instruction analysis steps.
  private static final int STEP_LIMIT = 5000;

  // Public API

  /// Asserts that if `scenario` is true, the current call will deterministically return `symbol`.
  ///
  /// Each call to `addResult` must provide a scenario that
  ///
  /// - is derived from the currentScenario at the start of the call (via zero or more splits), and
  /// - is disjoint from the scenario in any other `addResult` call.
  public void addResult(Symbol symbol, Scenario scenario) {
    results.add(new Result(symbol, scenario));
  }

  /// Asserts that if `scenario` is true, the current call will deterministically return the
  /// specified boolean value ([Core#TRUE] or [Core#FALSE]).
  public void addResult(boolean result, Scenario scenario) {
    addResult(result ? TRUE_SYMBOL : FALSE_SYMBOL, scenario);
  }

  /// Splits the given scenario on the given condition, and adds TRUE as the result for one of them
  /// and FALSE as the result for the other.
  public void addSplitAsResult(int split, boolean ifTrue, Scenario scenario) {
    Split s = scenario.split(split);
    if (s.ifTrue != null) {
      addResult(ifTrue, s.ifTrue);
    }
    if (s.ifFalse != null) {
      addResult(!ifTrue, s.ifFalse);
    }
  }

  /// Records that the given scenario will terminate with an error.
  public void fails(Scenario scenario) {
    failedWeight = addToWeight(scenario.splitCount(), failedWeight);
  }

  /// Splits the given scenario on the given condition; one branch terminates with an error, and the
  /// other is returned.
  public Scenario failOnSplit(Scenario scenario, int split, boolean isTrue) {
    Split s = scenario.split(split);
    Scenario fail = isTrue ? s.ifTrue() : s.ifFalse();
    if (fail != null) {
      fails(fail);
    }
    return isTrue ? s.ifFalse() : s.ifTrue();
  }

  /// Records that the given scenario may terminate with an error, or may continue. Returns the
  /// scenario in which it continues.
  public Scenario mayFail(Scenario scenario) {
    Scenario result = scenario.split();
    fails(result);
    return result;
  }

  /// Records that the given scenario may terminate with an error or return an unknown value.
  public void resultUnknownOrFail(Scenario scenario) {
    addResult(UNKNOWN, scenario);
  }

  /// Records that the given scenario returns an unknown boolean.
  public void resultUnknownBoolean(Scenario scenario) {
    // Split the scenario; in one case we'll return TRUE, and in the other we'll return FALSE.
    if (scenario.otherSplits == 0) {
      addSplitAsResult(newSplit(null), true, scenario);
    } else {
      Scenario split = scenario.split();
      addResult(true, split);
      addResult(false, split);
    }
  }

  /// Assigns a new split index, for use with [Scenario#split(int)]. If `id` is non-null, the
  /// scenarios that include this split are candidates for inclusion in [#filters()].
  public int newSplit(Object id) {
    int result = splitIds.size();
    splitIds.add(id);
    return result;
  }

  /// Returns the `id` that was passed to the [#newSplit] call that returned `split`.
  public Object splitId(int split) {
    return splitIds.get(split);
  }

  public int resultsStart() {
    return results.size();
  }

  /// Weights are represented as fractions of TOTAL_WEIGHT.
  private static final long TOTAL_WEIGHT = Long.highestOneBit(Long.MAX_VALUE);

  /// Scenarios with a very (implausibly) high split count would underflow the weight calculation
  /// (since it uses a long to represent fractions of 1). In that case we will set the lowest bit of
  /// the weight to indicate that it is incomplete.
  private static final int SPLIT_COUNT_LIMIT = 60;

  /// Valid weights are zero through TOTAL_WEIGHT; any other value means we've accumulated more than
  /// TOTAL_WEIGHT, which shouldn't happen.
  private static boolean isValidWeight(long weight) {
    return weight >= 0 && weight <= TOTAL_WEIGHT;
  }

  /// Returns true if this weight is known to be wrong due to an underflow (see
  /// [#SPLIT_COUNT_LIMIT]), which just means that we have to skip the consistency check (which is
  /// the only reason we're tracking weights at all).
  private static boolean hadUnderflow(long weight) {
    return (weight & 1) != 0;
  }

  /// Adds the weight of a scenario to an accumulator.
  private static long addToWeight(int splitCount, long weight) {
    if (splitCount > SPLIT_COUNT_LIMIT) {
      // Unlikely, I think; the only consequence is that we can't run a full consistency check.
      return weight | 1;
    } else {
      weight += TOTAL_WEIGHT >>> splitCount;
      /// Since scenarios are supposed to be disjoint, we should never get a total greater than
      // TOTAL_WEIGHT.
      assert isValidWeight(weight);
      return weight;
    }
  }

  /// Returns and removes all pairs passed to [#addResult] since [#resultsStart] returned `start`.
  public List<Result> takeResults(int start) {
    int n = results.size() - start;
    if (n == 0) {
      return ImmutableList.of();
    } else if (n == 1) {
      return ImmutableList.of(results.removeLast());
    }
    List<Result> subList = results.subList(start, results.size());
    List<Result> copy = ImmutableList.copyOf(subList);
    subList.clear();
    return copy;
  }

  /// Returns and removes all pairs passed to [#addResult] with the given symbol since
  /// [#resultsStart] returned `start`.
  public List<Result> takeMatchingResults(Symbol symbol, int start) {
    return takeMatchingResults(symbol::equals, start);
  }

  /// Returns and removes all pairs passed to [#addResult] with a symbol that matches `predicate`
  /// since [#resultsStart] returned `start`.
  public List<Result> takeMatchingResults(Predicate<Symbol> predicate, int start) {
    // I expect there to almost always be zero or one matching results, so postpone creating the
    // Builder until we really need it.
    ImmutableList.Builder<Result> builder = null;
    Result first = null;
    for (int i = start; i < results.size(); ) {
      Result result = results.get(i);
      if (predicate.test(result.symbol)) {
        if (first == null) {
          first = result;
        } else {
          if (builder == null) {
            builder = ImmutableList.builder();
            builder.add(first);
          }
          builder.add(result);
        }
        results.remove(i);
      } else {
        i++;
      }
    }
    if (builder != null) {
      return builder.build();
    } else {
      return (first != null) ? ImmutableList.of(first) : ImmutableList.of();
    }
  }

  public static final Symbol UNKNOWN =
      new Symbol() {
        @Override
        public List<BaseType> baseTypes() {
          return null;
        }

        @Override
        public Symbol element(int index, Scenario scenario) {
          return UNKNOWN;
        }

        @Override
        public void markUsed() {}
      };

  public static final Symbol ABSENT_SYMBOL = new Symbol.ForValue(Core.ABSENT);
  public static final Symbol TRUE_SYMBOL = new Symbol.ForValue(Core.TRUE);
  public static final Symbol FALSE_SYMBOL = new Symbol.ForValue(Core.FALSE);

  /// The splits from Scenarios that are known to not execute the loop body, bucketed by size.
  private final List<List<Bits>> skipped = new ArrayList<>();

  /// True if the loop body is skipped by Scenarios with non-zero [Scenario#otherSplits].
  private boolean hasOtherSkips;

  /// The total weight of all scenarios that are known to skip the loop body.
  private long skippedWeight;

  /// True if analysis reached a LimitedLoop value (the result of a call to `limit(n)`)
  private boolean hasLimit;

  /// The total weight of scenarios that passed all filters to reach the loop body.
  private long bodyWeight;

  /// When analysis is complete, the optimized split sets are saved here.
  private List<Bits> filters;

  /// Records that the loop body will be skipped in the given scenario.
  void addSkipped(Scenario scenario) {
    if (scenario.otherSplits == 0) {
      // This scenario might be convertible to a filter.
      addSkipped(scenario.splits);
    } else {
      // This scenario cannot be converted to a filter.
      hasOtherSkips = true;
    }
    skippedWeight = addToWeight(scenario.splitCount(), skippedWeight);
  }

  private static final Scenario INITIAL = new Scenario(Bits.EMPTY, 0);

  /// Symbolically executes `loop` with `item` (and an unknown state); the results of the analysis
  /// can be retrieved by calling [#filters()] and [#canUseLimit()].
  ///
  /// Should only be called once on each LoopAnalyzer instance.
  public void analyze(Symbol loop, Symbol item) {
    assert filters == null;
    analyzeLoop(loop, item, INITIAL);
    assert hadUnderflow(skippedWeight)
        || hadUnderflow(bodyWeight)
        || hadUnderflow(failedWeight)
        || skippedWeight + bodyWeight + failedWeight == TOTAL_WEIGHT;
    filters = buildFilters();
  }

  /// Each Bits in the returned list represents a conjunction of positive or negative splits that
  /// together imply that the loop body will be skipped. If `i` is an element of one of these Bits,
  /// `splitId(Bits.propFromBit(i))` returns the split identifier and `Bits.propIsPositive(i)`
  /// determines whether it is negated. None of the returned conjunctions includes splits with a
  /// null id.
  public List<Bits> filters() {
    return filters;
  }

  /// Returns true if analysis shows that the loop body will be skipped for all possible inputs.
  public boolean allRowsSkipped() {
    return filters.size() == 1 && filters.get(0).isEmpty();
  }

  /// Returns true if the loop body is wrapped in a LimitedLoop (i.e. it begins with a `limit()`),
  /// and all of the scenarios that can cause it to be skipped are included in [#filters()]; this
  /// means that the loop state is a pair whose first element is the maximum number of element that
  /// will be used from the filtered collection.
  public boolean canUseLimit() {
    return hasLimit && !hasOtherSkips;
  }

  /// Called after analysis has completed, to construct the list of filters from the list of skipped
  /// scenarios.
  ///
  /// There are two things going on here:
  ///
  /// * We can simplify scenarios based on overlap with simpler scenarios; and
  ///
  /// * We omit from our final list any scenarios that reference a split with null id (since those
  ///   do not correspond to a representable filter).
  ///
  /// As an example of simplification, suppose that the loop body looks like `filter(x > 0 and y >
  /// 0) | ...`. Analysis will construct splits corresponding to the tests `x > 0` and `y > 0` (call
  /// them `s0` and `s1`), and add two scenarios to [#skipped]: `{!s0}` and `{s0, !s1}` (i.e. the
  /// loop body will be skipped if `x > 0` is false (`{!s0}`), or if `x > 0` is true and `y > 0` is
  /// false (`{s0, !s1}`).
  ///
  /// Constructing filters directly from those sets of splits would result in something like `x > 0
  /// and (x <= 0 or y > 0)`, which while logically equivalent to `x > 0 and y > 0` might be handled
  /// much less efficiently. Instead the simplification step calls [Bits#simplifyConjunction], which
  /// given `{s0, !s1}` and `{!s0}` returns `{!s1}` (i.e. it removes the redundant `x <= 0` from the
  /// more complex conjunction). More generally, if we have already included a conjunction C0 in our
  /// filters, any other conjunction C1 can be simplified by assuming that C0 is false.
  ///
  /// Note that this simplification is valid even if `s0` does not correspond to a representable
  /// filter (and thus `{!s0}` will be dropped from the final list of filters). For example, if the
  /// loop body looks like `filter(sin(x) > 0 and y > 0) | ...` the same steps will be followed, but
  /// since the `s0` split has null id (assuming the collection can't represent the filter `sin(x) >
  /// 0`) the final list of filters will just be `{!s1}` (resulting in a filter of `y > 0`).
  private List<Bits> buildFilters() {
    ImmutableList.Builder<Bits> builder = new ImmutableList.Builder<>();
    // We've already saved all the skipped scenarios in buckets according to size, so process the
    // buckets from smallest to largest.
    for (int i = 0; i < skipped.size(); ) {
      List<Bits> bucket = skipped.get(i);
      if (bucket.isEmpty()) {
        // Move on to the next larger bucket.
        i++;
        continue;
      }
      // Pick one conjunction out of this bucket and process it.
      Bits first = bucket.removeFirst();
      if (first.stream().allMatch(j -> splitIds.get(Bits.propFromBit(j)) != null)) {
        // All of this scenario's splits are representable, so hand it on to the collection.
        builder.add(first);
      } else {
        // If we're dropping an unrepresentable filter, the limit can't be used.
        hasOtherSkips = true;
      }
      // Look in all larger buckets for elements that we can simplify now that we've chosen this one
      for (int j = i + 1; j < skipped.size(); j++) {
        if (simplifyBucket(skipped.get(j), first)) {
          // There's a (very) small chance that a chain of simplifications could place a Bits in a
          // lower bucket than the one we're looking at, so go back and see if any are non-empty
          // before continuing with this one.
          i = 0;
        }
      }
    }
    return builder.build();
  }

  /// Calls `Bits.simplifyConjunction(b, other)` for each `b` in `bucket`; if that returns a
  /// non-null (i.e. simplified) result, removes `b` and adds the result to [#skipped]. Returns true
  /// if any elements were removed.
  private boolean simplifyBucket(List<Bits> bucket, Bits other) {
    return bucket.removeIf(bits -> {
      Bits simplified = bits.simplifyConjunction(other);
      if (simplified != null) {
        // Simplification always makes the conjunction smaller, so we don't have to worry that
        // we're modifying the bucket we called `removeIf` on.
        assert simplified.count() < bits.count();
        addSkipped(simplified);
        return true;
      }
      return false;
    });
  }

  /// Adds the given conjunction to [#skipped].
  private void addSkipped(Bits splits) {
    // `skipped.get(i)` only contains conjunctions with exactly `i` conjuncts.
    int n = splits.count();
    // If this is larger than any conjunction we've seen before we need to create an empty bucket
    // (and possibly more than one).
    while (skipped.size() < n + 1) {
      skipped.add(new ArrayList<>());
    }
    // Before we add it to the bucket, compare it with any other conjunctions (of the same size)
    // that are already there.  If one of them differs only in the sign (positive or negative) of
    // a single split (e.g. this is `{..., s5, ...}` and there's already a `{..., !s5, ...}`,
    // where the rest of the sets are identical) we can combine them into a single, simpler
    // conjunction (by dropping that split).
    for (; ; ) {
      List<Bits> forSize = skipped.get(n);
      // Look at each of the conjunctions already in this bucket.
      for (int i = 0; ; i++) {
        if (i == forSize.size()) {
          // None of them could be combined with this one, so just add it and we're done.
          forSize.add(splits);
          return;
        }
        Bits combined = splits.simplifyConjunction(forSize.get(i));
        if (combined != null) {
          // We found a conjunction to combine it with.  Remove that one from the bucket, and start
          // over adding the simplified conjunction (with one fewer splits) to the next smaller
          // bucket (since it's possible that we'll be able to do this more than once).
          forSize.remove(i);
          splits = combined;
          --n;
          assert n == splits.count();
          break;
        }
      }
    }
  }

  /// Calls `addResult()` with all possible results of a call to `equal(arg0, arg1)`.
  public void analyzeEqual(Symbol arg0, Symbol arg1, Scenario scenario) {
    if (arg0 instanceof Symbol.ForValue(Value v) && v instanceof Singleton s) {
      addTypeCheckAsResult(arg1, s.baseType, scenario);
    } else if (arg1 instanceof Symbol.ForValue(Value v) && v instanceof Singleton s) {
      addTypeCheckAsResult(arg0, s.baseType, scenario);
    } else if (!arg0.equal(arg1, scenario, this)) {
      analyzeCallGeneric(Core.equal.fn(), new Symbol[] {arg0, arg1}, scenario);
    }
  }

  /// Calls `addResult()` with all possible results of the specified function call.
  public void analyzeCall(VmFunction fn, Symbol[] args, Scenario scenario) {
    // addResult() only supports single-result functions; multi-result functions shouldn't get here.
    assert fn.numResults == 1;
    if (fn == Core.at.fn()) {
      analyzeAt(args[0], args[1], scenario);
      return;
    } else if (fn == Core.equal.fn()) {
      analyzeEqual(args[0], args[1], scenario);
      return;
    } else if (fn == Core.lessThan.fn()) {
      if (args[0].lessThan(args[1], scenario, this)) {
        return;
      }
    } else if (fn == Core.not.fn()) {
      TypeCheck argType = TypeCheck.of(args[0], scenario);
      if (argType == null) {
        args[0].markUsed();
        resultUnknownBoolean(mayFail(scenario));
      } else {
        argType.isa(Core.TRUE.baseType).forEach(s -> addResult(false, s));
        argType.isa(Core.FALSE.baseType).forEach(s -> addResult(true, s));
        argType.remaining().forEach(this::fails);
      }
      return;
    } else if (fn.numArgs == 1) {
      Symbol arg0 = args[0];
      List<Scenario> extractorApplicable = isExtractor(fn, arg0, scenario);
      if (extractorApplicable != null) {
        extractorApplicable.forEach(s -> addResult(arg0.element(0, s), s));
        return;
      }
    }
    analyzeCallGeneric(fn, args, scenario);
  }

  /// Adds True as a result in scenarios where `symbol` has `baseType`, and False in other
  /// scenarios.
  private void addTypeCheckAsResult(Symbol symbol, BaseType baseType, Scenario scenario) {
    TypeCheck symbolType = TypeCheck.of(symbol, scenario);
    if (symbolType == null) {
      symbol.markUsed();
      resultUnknownBoolean(scenario);
    } else {
      symbolType.isa(baseType).forEach(s -> addResult(true, s));
      symbolType.remaining().forEach(s -> addResult(false, s));
    }
  }

  private static final Predicate<Symbol> IS_NOT_ABSENT = s -> !ABSENT_SYMBOL.equals(s);

  /// Calls `addResult()` with all possible results of a call to `at(lambda, arg)`.
  private void analyzeAt(Symbol lambda, Symbol arg, Scenario scenario) {
    TypeCheck lambdaType = TypeCheck.of(lambda, scenario);
    if (lambdaType == null) {
      lambda.markUsed();
      arg.markUsed();
      resultUnknownOrFail(scenario);
      return;
    }
    for (Scenario s : lambdaType.isa(Core.TRANSFORMED_LAMBDA)) {
      Symbol first = lambda.element(0, s);
      Symbol second = lambda.element(1, s);
      int start = resultsStart();
      analyzeAt(first, arg, s);
      for (Result notAbsentResult : takeMatchingResults(IS_NOT_ABSENT, start)) {
        analyzeAt(second, notAbsentResult.symbol, notAbsentResult.scenario);
      }
    }
    for (Scenario s : lambdaType.isa(Core.FILTER)) {
      int start = resultsStart();
      analyzeAt(lambda.element(0, s), arg, s);
      for (Result result : takeResults(start)) {
        TypeCheck resultType = TypeCheck.of(result.symbol, result.scenario);
        if (resultType == null) {
          arg.markUsed();
          resultUnknownOrFail(result.scenario);
        } else {
          resultType.isa(Core.TRUE.baseType).forEach(s2 -> addResult(arg, s2));
          resultType.isa(Core.FALSE.baseType).forEach(s2 -> addResult(ABSENT_SYMBOL, s2));
          resultType.remaining().forEach(this::fails);
        }
      }
    }
    for (Scenario s : lambdaType.remaining()) {
      if (arg instanceof Symbol.ForValue(Value v) && lambda.at(v, s, this)) {
        // The `at` call took care of it
      } else {
        analyzeCallGeneric(Core.at.fn(), new Symbol[] {lambda, arg}, s);
      }
    }
  }

  /// Symbolically executes `loop` with `item` (and an unknown state) in the given scenario.
  private void analyzeLoop(Symbol loop, Symbol item, Scenario scenario) {
    TypeCheck loopType = TypeCheck.of(loop, scenario);
    if (loopType == null) {
      analyzeLoopBody(loop, false, item, scenario);
      return;
    }
    for (Scenario s : loopType.isa(Core.TRANSFORMED_LOOP)) {
      Symbol lambda = loop.element(0, s);
      Symbol rest = loop.element(1, s);
      int start = resultsStart();
      analyzeAt(lambda, item, s);
      for (Result atResult : takeResults(start)) {
        TypeCheck atResultType = TypeCheck.of(atResult.symbol, atResult.scenario);
        if (atResultType == null) {
          hasOtherSkips = true;
          analyzeLoopBody(rest, false, atResult.symbol, atResult.scenario);
        } else {
          atResultType.isa(Core.ABSENT.baseType).forEach(this::addSkipped);
          atResultType.remaining().forEach(s2 -> analyzeLoop(rest, atResult.symbol, s2));
        }
      }
    }
    for (Scenario s : loopType.isa(Core.LIMITED_LOOP)) {
      analyzeLoopBody(loop.element(0, s), true, item, s);
    }
    for (Scenario s : loopType.remaining()) {
      analyzeLoopBody(loop, false, item, s);
    }
  }

  /// Called when `loop` is not a TransformedLoop or LimitedLoop. If `isInLimit` is true, it was
  /// wrapped in a LimitedLoop.
  private void analyzeLoopBody(Symbol loop, boolean isInLimit, Symbol item, Scenario scenario) {
    // If we reach this point multiple times (with different scenarios), `isInLimit` should be the
    // same each time.
    if (isInLimit) {
      assert hasLimit || bodyWeight == 0;
      hasLimit = true;
    } else {
      assert !hasLimit;
    }
    bodyWeight = addToWeight(scenario.splitCount(), bodyWeight);
    // We know that `count` doesn't use its argument; assume that everything else does.
    TypeCheck loopType = TypeCheck.of(loop, scenario);
    if (loopType != null) {
      var unused = loopType.isa(Core.COUNT.baseType);
      if (loopType.remaining().isEmpty()) {
        return;
      }
    }
    loop.markUsed();
    item.markUsed();
  }

  /// Implements [#analyzeCall] for cases that don't get special handling.
  private void analyzeCallGeneric(VmFunction fn, Symbol[] args, Scenario scenario) {
    // If there is a single matching method for these arg types and it's not a builtin, we can
    // try symbolically executing the instruction block.
    if (args.length != 0) {
      // TODO: this doesn't filter baseTypes based on the scenario; should it?
      List<List<BaseType>> argBaseTypes = Arrays.stream(args).map(arg -> arg.baseTypes()).toList();
      VmMethod method = fn.findMethodForBaseTypes(argBaseTypes);
      if (method != null && method.impl instanceof InstructionBlock ib) {
        // We can execute the instruction block to determine the function result.  Start with a
        // locals array initialized from the arguments.
        Symbol[] locals = Arrays.copyOf(args, ib.locals().size());
        analyzeInstructionBlock(ib, 0, locals, scenario);
        return;
      }
    }
    // Otherwise we just have to assume that all the args are used and that any outcome is possible.
    Symbol.markAll(args);
    resultUnknownOrFail(scenario);
  }

  /// Converts a VmExpr (appearing in an InstructionBlock) to the corresponding Symbol, or null if
  /// it references an undefined local.
  private static Symbol toSymbol(VmExpr expr, Symbol[] locals) {
    // There are three cases for a VmExpr, and each of them is simple:
    // * constant (return a constant symbol)
    // * compound (convert each of the element, and wrap them in a compound symbol)
    // * local (return the corresponding element of the locals array)
    return switch (expr) {
      case VmExpr.Constant c -> new Symbol.ForValue(c.value);
      case VmExpr.Compound c -> {
        int n = c.baseType.size();
        ImmutableList.Builder<Symbol> elements = ImmutableList.builderWithExpectedSize(n);
        for (int i = 0; i < n; i++) {
          Symbol e = toSymbol(c.element(i), locals);
          if (e == null) {
            yield null;
          }
          elements.add(e);
        }
        yield new Symbol.ForCompound(c.baseType, elements.build());
      }
      case Local local -> locals[local.index];
      default -> throw new AssertionError();
    };
  }

  /// Symbolically executes an instruction block.
  ///
  /// Compare with [InstructionBlock#execute(TState, Object\[\], int, MethodMemo)].
  private void analyzeInstructionBlock(
      InstructionBlock ib, int pc, Symbol[] locals, Scenario scenario) {
    for (; ; ) {
      if (stepCounter == STEP_LIMIT) {
        Symbol.markAll(locals);
        resultUnknownOrFail(scenario);
        return;
      }
      ++stepCounter;
      Instruction instruction = ib.instructions().get(pc++);
      switch (instruction) {
        case Instruction.Return inst -> {
          // analyzeCall only supports single-result functions, so we wouldn't be here if this
          // was a multi-result method.
          assert inst.numResults() == 1;
          Symbol result = toSymbol(inst.result(0), locals);
          if (result == null) {
            fails(scenario);
          } else {
            addResult(result, scenario);
          }
          return;
        }
        case Instruction.Set inst -> {
          Symbol rhs = toSymbol(inst.input, locals);
          if (rhs == null) {
            fails(scenario);
            return;
          } else if (inst.output() != null) {
            locals[inst.output().index] = rhs;
          }
        }
        case Instruction.Branch inst -> {
          pc = inst.target.location() + 1;
        }
        case Instruction.BranchTarget inst -> {
          // pc has already been incremented, so nothing to do.
        }
        case Instruction.ConditionalBranch inst -> {
          Symbol input = toSymbol(inst.input, locals);
          if (input == null) {
            fails(scenario);
            return;
          }
          List<Scenario> scenarios = new ArrayList<>(2);
          // The first `trueCount` entries in `scenarios` should take the true branch, the rest
          // should take the false branch.
          int trueCount;
          TypeCheck inputType = TypeCheck.of(input, scenario);
          if (inputType == null) {
            input.markUsed();
            if (!(inst instanceof Instruction.TypeCheckBranch)) {
              scenario = mayFail(scenario);
            }
            scenario = scenario.split();
            scenarios.add(scenario);
            trueCount = 1;
            scenarios.add(scenario);
          } else if (inst instanceof Instruction.TypeCheckBranch tcb) {
            scenarios.addAll(inputType.isa(tcb.type));
            trueCount = scenarios.size();
            scenarios.addAll(inputType.remaining());
          } else {
            scenarios.addAll(inputType.isa(Core.TRUE.baseType));
            trueCount = scenarios.size();
            scenarios.addAll(inputType.isa(Core.FALSE.baseType));
            inputType.remaining().forEach(this::fails);
          }
          if (scenarios.isEmpty()) {
            return;
          }
          for (int i = 0; i < scenarios.size(); i++) {
            // Usually there's exactly one Scenario, so we only go through this loop once and set up
            // the state to continue execution with the next instruction, but if there's more than
            // one result we'll use recursive calls to analyzeInstructionBlock (with a copy of the
            // locals) to check the other possibilities.
            if (i != 0) {
              analyzeInstructionBlock(ib, pc, locals.clone(), scenario);
            }
            scenario = scenarios.get(i);
            pc = (((i < trueCount) == inst.branchIfTrue) ? inst.target : inst).location() + 1;
          }
        }
        case Instruction.Call inst -> {
          int numArgs = inst.fn.numArgs;
          Symbol[] args = new Symbol[numArgs];
          for (int i = 0; i < numArgs; i++) {
            Symbol arg = toSymbol(inst.input(i), locals);
            if (arg == null) {
              fails(scenario);
              return;
            }
            args[i] = arg;
          }
          // Check for extractors first, since this is the one place we can handle functions with
          // more than one result.
          List<Scenario> extractorApplicable =
              (numArgs == 0) ? null : isExtractor(inst.fn, args[0], scenario);
          if (extractorApplicable != null) {
            if (extractorApplicable.isEmpty()) {
              return;
            }
            boolean first = true;
            for (Scenario s : extractorApplicable) {
              if (!first) {
                analyzeInstructionBlock(ib, pc, locals.clone(), scenario);
              }
              for (int i = 0; i < inst.fn.numResults; i++) {
                Local output = inst.output(i);
                if (output != null) {
                  locals[output.index] = args[0].element(i, s);
                }
              }
              scenario = s;
              first = false;
            }
          } else if (inst.fn.numResults == 1) {
            // Generic function call handling
            int start = resultsStart();
            analyzeCall(inst.fn, args, scenario);
            List<Result> results = takeResults(start);
            if (results.isEmpty()) {
              return;
            }
            boolean first = true;
            for (Result result : results) {
              if (!first) {
                analyzeInstructionBlock(ib, pc, locals.clone(), scenario);
              }
              Local output = inst.output(0);
              if (output != null) {
                locals[output.index] = result.symbol;
              }
              scenario = result.scenario;
              first = false;
            }
          } else {
            // Multi-result function that isn't an extractor -- bail out
            Symbol.markAll(locals);
            resultUnknownOrFail(scenario);
            return;
          }
        }
        case Instruction.Trace trace -> {
          // pc has already been incremented, so nothing to do.
        }
        case Instruction.Fail fail -> {
          fails(scenario);
          return;
        }
        default -> throw new AssertionError();
      }
    }
  }

  /// If `fn` is an extractor function (i.e. one that just returns each element of its argument as a
  /// separate result), returns a list of scenarios in which the extractor is applicable to the
  /// given arg's type; otherwise returns null.
  private List<Scenario> isExtractor(VmFunction fn, Symbol arg, Scenario scenario) {
    if (fn instanceof VmFunction.Simple simple) {
      if (simple.impl instanceof VmCompound.Extractor extractor) {
        TypeCheck argType = TypeCheck.of(arg, scenario);
        if (argType != null) {
          List<Scenario> result = argType.isa(extractor.baseType);
          argType.remaining().forEach(this::fails);
          return result;
        }
      } else if (simple.impl instanceof VmModule.UnCompound extractor) {
        TypeCheck argType = TypeCheck.of(arg, scenario);
        if (argType != null) {
          List<Scenario> result = argType.isa(extractor::accepts);
          argType.remaining().forEach(this::fails);
          return result;
        }
      }
    }
    return null;
  }
}
