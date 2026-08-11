package org.retrolang.impl.core;

import static org.retrolang.impl.Value.addRef;

import org.retrolang.impl.Allocator;
import org.retrolang.impl.BaseType;
import org.retrolang.impl.BuiltinMethod;
import org.retrolang.impl.Condition;
import org.retrolang.impl.ConditionalValue;
import org.retrolang.impl.Core;
import org.retrolang.impl.Err;
import org.retrolang.impl.NumValue;
import org.retrolang.impl.RC;
import org.retrolang.impl.RValue;
import org.retrolang.impl.ResultsInfo;
import org.retrolang.impl.Singleton;
import org.retrolang.impl.StringValue;
import org.retrolang.impl.TProperty;
import org.retrolang.impl.TState;
import org.retrolang.impl.Template;
import org.retrolang.impl.Value;
import org.retrolang.impl.ValueUtil;
import org.retrolang.impl.VmFunctionBuilder;
import org.retrolang.impl.VmType;

public final class ParserCore {

  @Core.Public
  public static final VmType.Union PARSER =
      Core.newOpenUnionWithSubTypes("Parser", Core.STRING.vmType());

  /// `function parse(parser, mode, pos=, source)`
  ///
  /// Returns an array or None; if it returns None, the value of `pos` is unspecified. `mode` is one
  /// of OutputsOrNone, EmptyOrNone, OutputsOrError, or EmptyOrError.
  @Core.Private
  static final VmFunctionBuilder parse4 = VmFunctionBuilder.create("parse", 4).hasInoutArg(2);

  /// `function parse(parser, stream=)`
  @Core.Public
  static final VmFunctionBuilder parse2 = VmFunctionBuilder.create("parse", 2).hasInoutArg(1);

  /// `function match(parser, stream=)`
  @Core.Public
  static final VmFunctionBuilder match = VmFunctionBuilder.create("match", 2).hasInoutArg(1);

  /// `function peek(parser, stream=)`
  @Core.Public static final VmFunctionBuilder peek = VmFunctionBuilder.create("peek", 2);

  @Core.Public public static final VmType.Union SOURCE = Core.newOpenUnion("Source");

  /// `open function atEnd(source, pos)`
  @Core.Public
  static final VmFunctionBuilder sourceAtEnd = VmFunctionBuilder.create("atEnd", 2).isOpen();

  /// `open function takeChar(source, pos=)`
  @Core.Public
  static final VmFunctionBuilder sourceTakeChar =
      VmFunctionBuilder.create("takeChar", 2).hasInoutArg(1).isOpen();

  /// `open function getString(source, startPos, endPos)`
  @Core.Public
  static final VmFunctionBuilder sourceGetString =
      VmFunctionBuilder.create("getString", 3).isOpen();

  /// `open function matchString(source, pos=, substring)`
  @Core.Public
  static final VmFunctionBuilder sourceMatchString =
      VmFunctionBuilder.create("matchString", 3).hasInoutArg(1).isOpen();

  /// `open function parseInt(source, pos=)`
  @Core.Public
  static final VmFunctionBuilder sourceParseInt =
      VmFunctionBuilder.create("parseInt", 2).hasInoutArg(1).isOpen();

  /// `private compound Stream`
  ///
  /// Elements are `pos`, `source`.
  @Core.Private static final BaseType.Named STREAM = Core.newBaseType("Stream", 2);

  @Core.Public static final VmFunctionBuilder stream = VmFunctionBuilder.create("stream", 1);

  @Core.Private public static final VmType.Union MODE = Core.newUnion("ParseMode");

  @Core.Private
  public static final VmType.Union KEEP_OUTPUTS_MODE = Core.newUnion("KeepOutputsMode", MODE);

  @Core.Private
  public static final VmType.Union ERROR_ON_FAIL_MODE = Core.newUnion("ErrorOnFailMode", MODE);

  @Core.Private
  public static final Singleton OUTPUTS_OR_NONE =
      Core.newSingleton("OutputsOrNone", KEEP_OUTPUTS_MODE);

  @Core.Private
  public static final Singleton OUTPUTS_OR_ERROR =
      Core.newSingleton("OutputsOrError", KEEP_OUTPUTS_MODE, ERROR_ON_FAIL_MODE);

  @Core.Private
  public static final Singleton EMPTY_OR_NONE = Core.newSingleton("EmptyOrNone", MODE);

  @Core.Private
  public static final Singleton EMPTY_OR_ERROR =
      Core.newSingleton("EmptyOrError", ERROR_ON_FAIL_MODE);

  @Core.Public
  public static final Singleton TRIVIAL_PARSER = Core.newSingleton("TrivialParser", PARSER);

  @Core.Public public static final Singleton AT_END = Core.newSingleton("AtEnd", PARSER);

  /// `function atEnd() = AtEnd`
  @Core.Public
  static final VmFunctionBuilder atEnd = VmFunctionBuilder.fromConstant("atEnd", AT_END);

  @Core.Public public static final Singleton NEW_LINE = Core.newSingleton("NewLine", PARSER);

  /// `function newLine() = NewLine`
  @Core.Public
  static final VmFunctionBuilder newLine = VmFunctionBuilder.fromConstant("newLine", NEW_LINE);

  /// `private compound OutputMatch is Parser`
  ///
  /// Element is `parser`.
  @Core.Private
  static final BaseType.Named OUTPUT_MATCH = Core.newBaseType("OutputMatch", 1, PARSER);

  /// `function outputMatch(parser)`
  @Core.Public
  static final VmFunctionBuilder outputMatch = VmFunctionBuilder.create("outputMatch", 1);

  /// `private compound Guarded`
  ///
  /// Elements are `guard` and `parser`.
  @Core.Private static final BaseType.Named GUARDED = Core.newBaseType("Guarded", 2);

  /// `private compound ChooseParser is Parser`
  ///
  /// Elements are `first` and `second`.
  @Core.Private
  static final BaseType.Named CHOOSE_PARSER = Core.newBaseType("ChooseParser", 2, PARSER);

  /// `function choose(first, second)`
  @Core.Public static final VmFunctionBuilder chooseParser = VmFunctionBuilder.create("choose", 2);

  /// `private compound OneOf is Parser`
  ///
  /// Element is `chars`.
  @Core.Private static final BaseType.Named ONE_OF = Core.newBaseType("OneOf", 1, PARSER);

  /// `function oneOf(chars)`
  @Core.Public static final VmFunctionBuilder oneOf = VmFunctionBuilder.create("oneOf", 1);

  // Should this include \u000B and \u000C?  regex.Pattern docs say that \R ("linebreak matcher")
  // includes them, but they are not considered "line terminators"?
  static final StringValue LINE_ENDS = StringValue.uncounted("\n\r\u0085\u2028\u2029");

  /// `function lineEnd() = oneOf("\\n\\r\\u0085\\u2028\\u2029")`
  @Core.Public
  static final VmFunctionBuilder lineEnd =
      VmFunctionBuilder.fromConstant("lineEnd", ONE_OF.uncountedOf(LINE_ENDS));

  /// `private compound OneOfRange is Parser`
  ///
  /// Elements are `firstChar`, `lastChar`.
  @Core.Private
  static final BaseType.Named ONE_OF_RANGE = Core.newBaseType("OneOfRange", 2, PARSER);

  /// `function oneOfRange(first, last)`
  @Core.Public
  static final VmFunctionBuilder oneOfRange = VmFunctionBuilder.create("oneOfRange", 2);

  /// `function digit() = oneOfRange("0", "9")`
  @Core.Public
  static final VmFunctionBuilder digit =
      VmFunctionBuilder.fromConstant(
          "digit",
          ONE_OF_RANGE.uncountedOf(
              NumValue.of('0', Allocator.UNCOUNTED), NumValue.of('9', Allocator.UNCOUNTED)));

  /// `private singleton ParseInt is Parser`
  @Core.Private public static final Singleton PARSE_INT = Core.newSingleton("ParseInt", PARSER);

  /// `function parseInt() = ParseInt`
  @Core.Public
  static final VmFunctionBuilder parseInt = VmFunctionBuilder.fromConstant("parseInt", PARSE_INT);

  /// `private compound ConcatParser is Parser`
  ///
  /// Elements are `p1`, `p2`.
  @Core.Private
  static final BaseType.Named CONCAT_PARSER = Core.newBaseType("ConcatParser", 2, PARSER);

  /// `private compound Repeat is Parser`
  ///
  /// Elements are `inner`, `min`, `max`.
  @Core.Private
  static final BaseType.Named REPEAT_PARSER = Core.newBaseType("RepeatParser", 3, PARSER);

  /// `method stream(String) = Stream_({pos: 0, source: s}`
  @Core.Method("stream(String)")
  static Value streamString(TState tstate, @RC.In Value s) {
    return tstate.compound(STREAM, NumValue.ZERO, s);
  }

  /// `method outputMatch(Parser parser) = OutputMatch_(parser)`
  @Core.Method("outputMatch(Parser)")
  static Value outputMatch(TState tstate, @RC.In Value parser) {
    return tstate.compound(OUTPUT_MATCH, parser);
  }

  /// `method oneOf(String chars) = OneOf_(chars)`
  @Core.Method("oneOf(String)")
  static Value oneOf(TState tstate, @RC.In Value chars) throws Err.BuiltinException {
    Err.INVALID_ARGUMENT.when(StringValue.lengthIs(tstate, chars, NumValue.ZERO));
    return tstate.compound(ONE_OF, chars);
  }

  /// `method oneOfRange(String firstChar, String lastChar) = ...`
  @Core.Method("oneOfRange(String, String)")
  static Value oneOfRange(TState tstate, Value firstChar, Value lastChar)
      throws Err.BuiltinException {
    Err.INVALID_ARGUMENT.unless(StringValue.lengthIs(tstate, firstChar, NumValue.ONE));
    Err.INVALID_ARGUMENT.unless(StringValue.lengthIs(tstate, lastChar, NumValue.ONE));
    Value first = StringValue.codePoint.call(tstate, firstChar, NumValue.ZERO);
    Value last = StringValue.codePoint.call(tstate, lastChar, NumValue.ZERO);
    Err.INVALID_ARGUMENT.unless(Condition.numericLessOrEq(first, last));
    return tstate.compound(ONE_OF_RANGE, first.makeStorable(tstate), last.makeStorable(tstate));
  }

  /// `method choose(Guarded first, Parser second) = Choose_({first, second})`
  @Core.Method("choose(Guarded, Parser)")
  static Value chooseGuarded(TState tstate, @RC.In Value first, @RC.In Value second) {
    return tstate.compound(CHOOSE_PARSER, first, second);
  }

  /// `method choose(Parser first, Parser second) = Choose_({first: Guarded_({guard: first, rest:
  /// Trivial}), second})`
  @Core.Method("choose(Parser, Parser)")
  static Value chooseParser(TState tstate, @RC.In Value first, @RC.In Value second) {
    Value guarded = tstate.compound(GUARDED, first, TRIVIAL_PARSER);
    return tstate.compound(CHOOSE_PARSER, guarded, second);
  }

  /// ```
  /// function parse(Parser parser, Stream stream=) = parse(parser, OutputsOrNone, stream_.pos=, stream_.source)
  /// ```
  static class Parse2 extends BuiltinMethod {
    static final Caller parse = new Caller("parse:4", "afterParse");

    @Core.Method("parse(Parser, Stream)")
    static void begin(TState tstate, @RC.In Value parser, @RC.In Value stream) {
      Value pos = stream.element(0);
      Value source = stream.element(1);
      tstate.startCall(parse, parser, OUTPUTS_OR_NONE, pos, source).saving(stream);
    }

    @Continuation
    static void afterParse(
        TState tstate, @RC.In Value result, @RC.In Value pos, @Saved @RC.In Value stream) {
      Value newStream =
          result
              .is(Core.NONE)
              .choose(
                  () -> {
                    tstate.dropValue(pos);
                    return stream;
                  },
                  () -> stream.replaceElement(tstate, 0, pos));
      tstate.setResults(result, newStream);
    }
  }

  /// ```
  /// function match(Parser parser, Stream stream=) = parse(parser, OutputsOrError, stream_.pos=, stream_.source)
  /// ```
  static class Match extends BuiltinMethod {
    static final Caller parse = new Caller("parse:4", "afterParse");

    @Core.Method("match(Parser, Stream)")
    static void begin(TState tstate, @RC.In Value parser, @RC.In Value stream) {
      Value pos = stream.element(0);
      Value source = stream.element(1);
      tstate.startCall(parse, parser, OUTPUTS_OR_ERROR, pos, source).saving(stream);
    }

    @Continuation
    static void afterParse(
        TState tstate, @RC.In Value result, @RC.In Value pos, @Saved @RC.In Value stream) {
      Value newStream = stream.replaceElement(tstate, 0, pos);
      tstate.setResults(result, newStream);
    }
  }

  /// ```
  /// function peek(Parser parser, Stream stream) {
  ///   tempPos = stream_.pos
  ///   return parse(parser, EmptyOrNone, tempPos=, stream_.source) is not None
  /// }
  /// ```
  static class Peek extends BuiltinMethod {
    static final Caller parse = new Caller("parse:4", "afterParse");

    @Core.Method("peek(Parser, Stream)")
    static void begin(TState tstate, @RC.In Value parser, Value stream) {
      Value pos = stream.element(0);
      Value source = stream.element(1);
      tstate.startCall(parse, parser, EMPTY_OR_NONE, pos, source);
    }

    @Continuation
    static Value afterParse(Value result, Value pos) {
      return result.is(Core.NONE).choose(Core.FALSE, Core.TRUE);
    }
  }

  /// ```
  /// method parse(AtEnd, mode, pos=, source) {
  ///   if atEnd(stream, pos) { return [] }
  ///   assert not isErrorOnFail(mode)
  ///   return None
  /// }
  /// ```
  static class ParseAtEnd extends BuiltinMethod {
    static final Caller atEnd = new Caller("atEnd:2", "afterAtEnd");

    @Core.Method("parse(AtEnd, ParseMode, _, _)")
    static void begin(
        TState tstate, Value unused, @RC.In Value mode, @RC.In Value pos, @RC.In Value source) {
      tstate.startCall(atEnd, source, addRef(pos)).saving(mode, pos);
    }

    @Continuation
    static void afterAtEnd(TState tstate, Value result, @Saved Value mode, @RC.In Value pos)
        throws Err.BuiltinException {
      Err.PARSE_FAILED.when(result.is(Core.FALSE).and(mode.isa(ERROR_ON_FAIL_MODE)));
      tstate.setResults(Condition.fromBoolean(result).choose(Core.EMPTY_ARRAY, Core.NONE), pos);
    }
  }

  /// ```
  /// method parse(OneOf oneOf, mode, pos=, source) {
  ///   next = takeChar(source, pos=)
  ///   if contains(oneOf_, next) {
  /// 		 return []
  ///   }
  /// 	assert not isErrorOnFail(mode)
  /// 	return None
  /// }
  /// ```
  static class ParseOneOf extends BuiltinMethod {
    static final Caller takeChar = new Caller("takeChar:2", "afterTakeChar");

    @Core.Method("parse(OneOf, ParseMode, _, _)")
    static void begin(
        TState tstate,
        @RC.In Value oneOf,
        @RC.In Value mode,
        @RC.In Value pos,
        @RC.In Value source) {
      tstate.startCall(takeChar, source, pos).saving(oneOf, mode);
    }

    @Continuation
    static void afterTakeChar(
        TState tstate, Value nextChar, @RC.In Value pos, @Saved Value oneOf, Value mode)
        throws Err.BuiltinException {
      nextChar
          .is(Core.NONE)
          .not()
          .and(
              () ->
                  StringValue.containsCodePoint.asCondition(tstate, oneOf.peekElement(0), nextChar))
          .testExcept(
              () -> {
                tstate.setResults(Core.EMPTY_ARRAY, pos);
              },
              () -> {
                Err.PARSE_FAILED.when(mode.isa(ERROR_ON_FAIL_MODE));
                tstate.setResults(Core.NONE, pos);
              });
    }
  }

  /// ```
  /// method parse(OneOfRange oneOfRange, mode, pos=, source) {
  ///   next = takeChar(source, pos=)
  ///   {first, last} = oneOfRange_
  ///   if next is not None and next >= first and next <= last {
  /// 		 return []
  ///   }
  /// 	assert not isErrorOnFail(mode)
  /// 	return None
  /// }
  /// ```
  static class ParseOneOfRange extends BuiltinMethod {
    static final Caller takeChar = new Caller("takeChar:2", "afterTakeChar");

    @Core.Method("parse(OneOfRange, ParseMode, _, _)")
    static void begin(
        TState tstate,
        @RC.In Value oneOfRange,
        @RC.In Value mode,
        @RC.In Value pos,
        @RC.In Value source) {
      tstate.startCall(takeChar, source, pos).saving(oneOfRange, mode);
    }

    private static Condition contains(Value oneOfRange, Value c) {
      return Condition.numericLessOrEq(oneOfRange.element(0), c)
          .and(Condition.numericLessOrEq(c, oneOfRange.element(1)));
    }

    @Continuation
    static void afterTakeChar(
        TState tstate, Value nextChar, @RC.In Value pos, @Saved Value oneOfRange, Value mode)
        throws Err.BuiltinException {
      nextChar
          .is(Core.NONE)
          .not()
          .and(() -> contains(oneOfRange, nextChar))
          .testExcept(
              () -> {
                tstate.setResults(Core.EMPTY_ARRAY, pos);
              },
              () -> {
                Err.PARSE_FAILED.when(mode.isa(ERROR_ON_FAIL_MODE));
                tstate.setResults(Core.NONE, pos);
              });
    }
  }

  /// ```
  /// method parse(String s, mode, pos=, source) {
  ///   if matchString(source, s, pos=) { return [] }
  ///   assert not isErrorOnFail(mode)
  ///   return None
  /// }
  /// ```
  static class ParseString extends BuiltinMethod {
    static final Caller matchString = new Caller("matchString:3", "afterMatchString");

    @Core.Method("parse(String, ParseMode, _, _)")
    static void begin(
        TState tstate, @RC.In Value s, @RC.In Value mode, @RC.In Value pos, @RC.In Value source) {
      if (s instanceof StringValue sv && sv.value.isEmpty()) {
        tstate.dropValue(mode);
        tstate.dropValue(source);
        tstate.setResults(Core.EMPTY_ARRAY, pos);
      } else {
        tstate.startCall(matchString, source, pos, s).saving(mode);
      }
    }

    @Continuation
    static void afterMatchString(TState tstate, Value result, @RC.In Value pos, @Saved Value mode)
        throws Err.BuiltinException {
      Condition.fromBoolean(result)
          .testExcept(
              () -> tstate.setResults(Core.EMPTY_ARRAY, pos),
              () -> {
                Err.PARSE_FAILED.when(mode.isa(ERROR_ON_FAIL_MODE));
                tstate.setResults(Core.NONE, pos);
              });
    }
  }

  /// ```
  /// method parse(TrivalParser, mode, pos=, source) = []
  /// ```
  @Core.Method("parse(TrivialParser, ParseMode, _, _)")
  static void parseTrivial(
      TState tstate, Value unused, Value mode, @RC.In Value pos, Value source) {
    tstate.setResults(Core.EMPTY_ARRAY, pos);
  }

  /// ```
  /// method parse(OutputMatch outputMatch, mode, pos=, source) {
  ///   savedPos = pos
  ///   result = parse(outputMatch_, mode, pos=, source)
  ///   if result is not None and isKeepOutputs(mode) {
  ///     result &= [getString(source, savedPos, pos)]
  ///   }
  ///   return result
  /// }
  /// ```
  static class ParseOutputMatch extends BuiltinMethod {
    static final Caller parseInner = new Caller("parse:4", "afterParseInner");
    static final Caller getString = new Caller("getString:3", "afterGetString");

    @Core.Method("parse(OutputMatch, ParseMode, _, _)")
    static void begin(
        TState tstate,
        Value outputMatch,
        @RC.In Value mode,
        @RC.In Value pos,
        @RC.In Value source) {
      Value inner = outputMatch.element(0);
      tstate
          .startCall(parseInner, inner, addRef(mode), addRef(pos), addRef(source))
          .saving(mode, pos, source);
    }

    @Continuation
    static void afterParseInner(
        TState tstate,
        @RC.In Value result,
        @RC.In Value pos,
        @Saved Value mode,
        @RC.In Value savedPos,
        @RC.In Value source) {
      result
          .is(Core.NONE)
          .or(mode.isa(KEEP_OUTPUTS_MODE).not())
          .test(
              () -> {
                tstate.dropValue(savedPos);
                tstate.dropValue(source);
                tstate.setResults(result, pos);
              },
              () -> tstate.startCall(getString, source, savedPos, addRef(pos)).saving(result, pos));
    }

    @Continuation(order = 2)
    static void afterGetString(
        TState tstate,
        ResultsInfo results,
        @RC.In Value s,
        @Saved @RC.In Value result,
        @RC.In Value pos)
        throws Err.BuiltinException {
      result = ValueUtil.appendElement(tstate, result, s, results.result(TProperty.ARRAY_LAYOUT));
      tstate.setResults(result, pos);
    }
  }

  /// ```
  /// method parse(ParseInt, mode, pos=, source) {
  ///   result = parseInt(source, pos=)
  ///   if result is None {
  ///     assert not isErrorOnFail(mode)
  ///   } else {
  ///     result = isKeepOutputs(mode) ? [result] : []
  ///   }
  ///   return result
  /// }
  /// ```
  static class ParseInt extends BuiltinMethod {
    static final Caller sourceParseInt = new Caller("parseInt:2", "afterParseInt");

    @Core.Method("parse(ParseInt, ParseMode, _, _)")
    static void begin(
        TState tstate, Value parseInt, @RC.In Value mode, @RC.In Value pos, @RC.In Value source) {
      tstate.startCall(sourceParseInt, source, pos).saving(mode);
    }

    @Continuation
    static void afterParseInt(
        TState tstate, @RC.In Value result, @RC.In Value pos, @Saved Value mode)
        throws Err.BuiltinException {
      result
          .is(Core.NONE)
          .testExcept(
              () -> {
                Err.PARSE_FAILED.when(mode.isa(ERROR_ON_FAIL_MODE));
                tstate.setResults(Core.NONE, pos);
              },
              () -> {
                Value parseResult =
                    mode.isa(KEEP_OUTPUTS_MODE)
                        .choose(
                            () -> tstate.arrayValue(result),
                            () -> {
                              tstate.dropValue(result);
                              return Core.EMPTY_ARRAY;
                            });
                tstate.setResults(parseResult, pos);
              });
    }
  }

  static final NumValue CR_CODE_POINT = NumValue.of('\r', Allocator.UNCOUNTED);
  static final NumValue LF_CODE_POINT = NumValue.of('\n', Allocator.UNCOUNTED);

  /// ```
  /// method tryParse(NewLine, errorOnFail, outputs=, pos=, source) {
  ///   tempPos = pos
  ///   next = takeChar(source, tempPos=)
  ///   if not contains(lineEnds, next) {
  /// 	   assert not isErrorOnFail(mode)
  /// 	   return None
  /// 	 }
  /// 	 pos = tempPos
  /// 	 if next == "\r" {
  ///     next = takeChar(source, tempPos=)
  ///     if next2 == "\n" {
  ///       pos = tempPos
  ///     }
  ///   }
  /// 	 return []
  /// }
  /// ```
  static class ParseNewLine extends BuiltinMethod {
    static final Caller takeChar = new Caller("takeChar:2", "afterTakeChar");
    static final Caller takeChar2 = new Caller("takeChar:2", "afterTakeChar2");

    @Core.Method("parse(NewLine, ParseMode, _, _)")
    static void begin(
        TState tstate, Value unused, @RC.In Value mode, @RC.In Value pos, @RC.In Value source) {
      tstate.startCall(takeChar, addRef(source), pos).saving(mode, source);
    }

    @Continuation
    static void afterTakeChar(
        TState tstate, Value nextChar, @RC.In Value pos, @Saved Value mode, Value source)
        throws Err.BuiltinException {
      nextChar
          .is(Core.NONE)
          .not()
          .and(() -> StringValue.containsCodePoint.asCondition(tstate, LINE_ENDS, nextChar))
          .testExcept(
              () -> {
                Condition.numericEq(nextChar, CR_CODE_POINT)
                    .test(
                        () -> tstate.startCall(takeChar2, addRef(source), addRef(pos)).saving(pos),
                        () -> tstate.setResults(Core.EMPTY_ARRAY, pos));
              },
              () -> {
                Err.PARSE_FAILED.when(mode.isa(ERROR_ON_FAIL_MODE));
                tstate.setResults(Core.NONE, pos);
              });
    }

    @Continuation
    static void afterTakeChar2(
        TState tstate, Value nextChar, @RC.In Value pos2, @Saved @RC.In Value pos) {
      Value newPos =
          nextChar
              .is(Core.NONE)
              .not()
              .and(() -> Condition.numericEq(nextChar, LF_CODE_POINT))
              .choose(
                  () -> {
                    tstate.dropValue(pos);
                    return pos2;
                  },
                  () -> {
                    tstate.dropValue(pos2);
                    return pos;
                  });
      tstate.setResults(Core.EMPTY_ARRAY, newPos);
    }
  }

  /// ```
  /// method add(Parser p1, Parser p2) = ConcatParser_({p1, p2})
  /// ```
  @Core.Method("add(Parser, Parser)")
  static Value addParsers(TState tstate, @RC.In Value p1, @RC.In Value p2) {
    return tstate.compound(CONCAT_PARSER, p1, p2);
  }

  /// ```
  /// method parse(ConcatParser concat, mode, pos=, source) {
  ///   tempPos = pos
  ///   result = parse(concat_.p1, mode, tempPos=, source)
  ///   if result is None { return None }
  ///   result2 = tryParse(concat_.p2, mode, tempPos=, source)
  ///   if result2 is None { return None }
  /// 	 pos = tempPos
  /// 	 result &= result2
  /// 	 return result
  /// }
  /// ```
  static class ParseConcatParser extends BuiltinMethod {
    static final Caller parse1 = new Caller("parse:4", "afterParse1");
    static final Caller parse2 = new Caller("parse:4", "afterParse2");

    @Core.Method("parse(ConcatParser, ParseMode, _, _)")
    static void begin(
        TState tstate, Value concat, @RC.In Value mode, @RC.In Value pos, @RC.In Value source) {
      Value p1 = concat.element(0);
      Value p2 = concat.element(1);
      tstate.startCall(parse1, p1, addRef(mode), pos, addRef(source)).saving(p2, mode, source);
    }

    @Continuation
    static void afterParse1(
        TState tstate,
        @RC.In Value result,
        @RC.In Value pos,
        @Saved @RC.In Value p2,
        @RC.In Value mode,
        @RC.In Value source) {
      result
          .is(Core.NONE)
          .test(
              () -> {
                tstate.dropValue(p2);
                tstate.dropValue(mode);
                tstate.dropValue(source);
                tstate.setResults(Core.NONE, pos);
              },
              () -> tstate.startCall(parse2, p2, mode, pos, source).saving(result));
    }

    @Continuation(order = 2)
    static void afterParse2(
        TState tstate,
        ResultsInfo results,
        @RC.In Value result2,
        @RC.In Value pos,
        @Saved @RC.In Value result1)
        throws Err.BuiltinException {
      result2
          .is(Core.NONE)
          .testExcept(
              () -> {
                tstate.dropValue(result1);
                tstate.setResults(Core.NONE, pos);
              },
              () -> {
                Value combined =
                    ValueUtil.appendArrays(
                        tstate, result1, result2, results.result(TProperty.ARRAY_LAYOUT));
                tstate.setResults(combined, pos);
              });
    }
  }

  /// ```
  /// method multiply(Number n, Parser inner) {
  ///   assert n is Integer and n >= 0
  ///   return RepeatParser_({inner, min: n, max: n})
  /// }
  /// ```
  @Core.Method("multiply(Number, Parser)")
  static Value repeatParserInt(TState tstate, Value n, @RC.In Value inner)
      throws Err.BuiltinException {
    n = n.verifyInt(Err.INVALID_ARGUMENT);
    Err.INVALID_ARGUMENT.unless(Condition.numericLessOrEq(NumValue.ZERO, n));
    Value min = n.makeStorable(tstate);
    Value guarded = tstate.compound(GUARDED, inner, TRIVIAL_PARSER);
    return tstate.compound(REPEAT_PARSER, guarded, min, addRef(min));
  }

  /// ```
  /// method multiply(Range range, Parser inner) {
  ///   min = min(range)
  ///   if min is None {
  ///     min = 0
  ///   }
  ///   assert min >= 0
  ///   // range must not be empty
  ///   assert max is None or max >= min
  ///   return RepeatParser_({inner, min, max: max(range)})
  /// }
  /// ```
  @Core.Method("multiply(Range, Parser|Guarded)")
  static Value repeatParserRange(TState tstate, Value range, @RC.In Value inner)
      throws Err.BuiltinException {
    Value m = range.element(0);
    m = m.is(Core.NONE).choose(NumValue.ZERO, m);
    Value min;
    if (m instanceof ConditionalValue cv) {
      // Unlikely, but possible.  A ConditionalValue will cause problems ahead, so we need to
      // materialize it.  Fortunately that's easy when we know the type.
      min = cv.materialize(tstate.codeGen(), Template.NumVar.INT32);
    } else {
      min = m;
      tstate.dropOnThrow(min);
    }
    Err.INVALID_ARGUMENT.unless(Condition.numericLessOrEq(NumValue.ZERO, min));
    Value max = range.element(1);
    Err.INVALID_ARGUMENT.unless(max.is(Core.NONE).or(() -> Condition.numericLessOrEq(min, max)));
    return inner
        .isa(GUARDED)
        .choose(
            () -> tstate.compound(REPEAT_PARSER, inner, min, max),
            () -> {
              Value guarded = tstate.compound(GUARDED, inner, TRIVIAL_PARSER);
              return tstate.compound(REPEAT_PARSER, guarded, min, max);
            });
  }

  /// Returns OUTPUTS\_OR\_NONE if `mode` is OUTPUTS\_OR\_NONE or OUTPUTS\_OR\_ERROR; otherwise
  /// returns EMPTY\_OR\_NONE.
  private static Value noError(Value mode) {
    return mode.isa(KEEP_OUTPUTS_MODE).choose(OUTPUTS_OR_NONE, EMPTY_OR_NONE);
  }

  /// ```
  /// method parse(RepeatParser repeat, mode, pos=, source) {
  ///   { inner, min, max } = repeat_
  ///   savedPos = pos
  ///   result = []
  ///   for count in 1..max sequential result, pos {
  ///     guardPos = pos
  ///     guardMode = (count <= min) ? mode : noError(mode)
  ///     guardResult = parse(inner_.guard, guardMode, guardPos=, source)
  ///     if guardResult is None {
  ///       if count <= min { results = None }
  ///       break
  ///     }
  ///     result &= guardResult
  ///     pos = guardPos
  ///     restResult = parse(inner_.rest, mode, pos=, source)
  ///     if restResult is None {
  ///       result = None
  ///       break
  ///     }
  ///     result &= restResult
  ///   }
  ///   return result
  /// }
  /// ```
  static class ParseRepeat extends BuiltinMethod {
    static final Caller parseGuard = new Caller("parse:4", "afterParseGuard");
    static final Caller parseRest = new Caller("parse:4", "afterParseRest");

    @Core.Method("parse(RepeatParser, ParseMode, _, _)")
    static void begin(
        TState tstate,
        @RC.In Value repeat,
        @RC.In Value mode,
        @RC.In Value pos,
        @RC.In Value source) {
      tstate.jump("tryOne", repeat, NumValue.ZERO, mode, Core.EMPTY_ARRAY, pos, source);
    }

    static int loopBound(Object[] continuationArgs) {
      Value repeat = RValue.exploreSafely((Value) continuationArgs[0]);
      int result = NumValue.asIntOrMinusOne(repeat.peekElement(2));
      if (result > 0) {
        int count = NumValue.asIntOrMinusOne((Value) continuationArgs[1]);
        if (count > 0) {
          assert count <= result;
          result -= count;
        }
      }
      return result;
    }

    @LoopContinuation
    static void tryOne(
        TState tstate,
        @RC.In Value repeat,
        @RC.In Value count,
        @RC.In Value mode,
        @RC.In Value result,
        @RC.In Value pos,
        @RC.In Value source) {
      Value max = repeat.peekElement(2);
      max.is(Core.NONE)
          .not()
          .and(() -> Condition.numericEq(count, max))
          .test(
              () -> {
                tstate.dropValue(repeat);
                tstate.dropValue(count);
                tstate.dropValue(mode);
                tstate.dropValue(source);
                tstate.setResults(result, pos);
              },
              () -> {
                Value inner = repeat.peekElement(0);
                assert inner.isa(GUARDED).asBoolean();
                Value guard = inner.element(0);
                Value min = repeat.peekElement(1);
                // Once we have at least min successful matches it's OK to fail
                Value guardMode = Condition.numericLessThan(count, min).choose(mode, noError(mode));
                tstate
                    .startCall(parseGuard, guard, guardMode, addRef(pos), addRef(source))
                    .saving(repeat, count, mode, result, pos, source);
              });
    }

    @Continuation(order = 2)
    static void afterParseGuard(
        TState tstate,
        ResultsInfo results,
        @RC.In Value guardResult,
        @RC.In Value guardPos,
        @Saved @RC.In Value repeat,
        Value count,
        @RC.In Value mode,
        @RC.In Value result,
        @RC.In Value prevPos,
        @RC.In Value source)
        throws Err.BuiltinException {
      guardResult
          .is(Core.NONE)
          .testExcept(
              () -> {
                tstate.dropValue(guardPos);
                Value min = repeat.peekElement(1);
                tstate.dropValue(repeat);
                tstate.dropValue(mode);
                tstate.dropValue(source);
                Value finalResult =
                    Condition.numericLessThan(count, min)
                        .choose(
                            () -> {
                              tstate.dropValue(result);
                              return Core.NONE;
                            },
                            () -> result);
                tstate.setResults(finalResult, prevPos);
              },
              () -> {
                Value combinedResult =
                    ValueUtil.appendArrays(
                        tstate, result, guardResult, results.result(TProperty.ARRAY_LAYOUT));
                Value newCount = ValueUtil.addInts(tstate, count, NumValue.ONE);
                Value inner = repeat.peekElement(0);
                assert inner.isa(GUARDED).asBoolean();
                Value rest = inner.element(1);
                tstate
                    .startCall(parseRest, rest, addRef(mode), guardPos, addRef(source))
                    .saving(repeat, newCount, mode, combinedResult, prevPos, source);
              });
    }

    @Continuation(order = 3)
    static void afterParseRest(
        TState tstate,
        ResultsInfo results,
        @RC.In Value restResult,
        @RC.In Value pos,
        @Saved @RC.In Value repeat,
        @RC.In Value count,
        @RC.In Value mode,
        @RC.In Value result,
        Value prevPos,
        @RC.In Value source)
        throws Err.BuiltinException {
      restResult
          .is(Core.NONE)
          .testExcept(
              () -> {
                tstate.dropValue(repeat);
                tstate.dropValue(count);
                tstate.dropValue(mode);
                tstate.dropValue(result);
                tstate.dropValue(source);
                tstate.setResults(Core.NONE, pos);
              },
              () -> {
                Err.REPEATED_EMPTY_PARSE.when(Condition.numericEq(pos, prevPos));
                Value combinedResult =
                    ValueUtil.appendArrays(
                        tstate, result, restResult, results.result(TProperty.ARRAY_LAYOUT));
                tstate.jump("tryOne", repeat, count, mode, combinedResult, pos, source);
              });
    }
  }

  /// ```
  /// method parse(Choose choose, mode, pos=, source) {
  ///   { first second } = choose_
  /// 	 guardPos = pos
  /// 	 result = parse(first_.guard, noError(mode), guardPos=, source)
  /// 	 if result is None {
  /// 	   return parse(second, mode, pos=, source)
  /// 	 }
  /// 	 pos = guardPos
  /// 	 restResult = parse(first_.rest, mode, pos=, source)
  /// 	 if restResult is None {
  /// 	 	 return None
  /// 	 }
  /// 	 result &= restResult
  /// 	 return result
  /// }
  /// ```
  static class ParseChoose extends BuiltinMethod {
    static final Caller parseGuard = new Caller("parse:4", "afterParseGuard");
    static final Caller parseRest = new Caller("parse:4", "afterParseRest");

    @Core.Method("parse(ChooseParser, ParseMode, _, _)")
    static void begin(
        TState tstate,
        @RC.In Value choose,
        @RC.In Value mode,
        @RC.In Value pos,
        @RC.In Value source) {
      Value first = choose.peekElement(0);
      assert first.isa(GUARDED).asBoolean();
      Value guard = first.element(0);
      tstate
          .startCall(parseGuard, guard, noError(mode), addRef(pos), addRef(source))
          .saving(choose, mode, pos, source);
    }

    @Continuation
    static void afterParseGuard(
        TState tstate,
        @RC.In Value guardResult,
        @RC.In Value pos,
        @Saved Value choose,
        @RC.In Value mode,
        @RC.In Value prevPos,
        @RC.In Value source,
        @Fn("parse:4") Caller parseSecond)
        throws Err.BuiltinException {
      guardResult
          .is(Core.NONE)
          .testExcept(
              () -> {
                tstate.dropValue(pos);
                Value second = choose.element(1);
                tstate.startCall(parseSecond, second, mode, prevPos, source);
              },
              () -> {
                tstate.dropValue(prevPos);
                Value first = choose.peekElement(0);
                Value rest = first.element(1);
                tstate.startCall(parseRest, rest, mode, pos, source).saving(guardResult);
              });
    }

    @Continuation(order = 2)
    static void afterParseRest(
        TState tstate,
        ResultsInfo results,
        @RC.In Value restResult,
        @RC.In Value pos,
        @Saved @RC.In Value guardResult)
        throws Err.BuiltinException {
      restResult
          .is(Core.NONE)
          .testExcept(
              () -> {
                tstate.dropValue(guardResult);
                tstate.setResults(Core.NONE, pos);
              },
              () -> {
                Value combinedResult =
                    ValueUtil.appendArrays(
                        tstate, guardResult, restResult, results.result(TProperty.ARRAY_LAYOUT));
                tstate.setResults(combinedResult, pos);
              });
    }
  }

  /// ```
  /// method atEnd(String s, Number pos) = (pos == size(s))
  /// ```
  @Core.Method("atEnd(String, Number)")
  static Value atEndString(TState tstate, Value s, Value pos) {
    return StringValue.lengthIs(tstate, s, pos).asValue();
  }

  /// ```
  /// method takeChar(String s, Number pos=) {
  ///   if atEnd(s, pos) {
  ///     return None
  ///   }
  ///   result = s @ pos
  ///   pos += 1
  ///   return result
  /// }
  /// ```
  @Core.Method("takeChar(String, Number)")
  static void takeCharString(TState tstate, Value s, Value pos) throws Err.BuiltinException {
    Value p = pos.verifyInt(Err.INVALID_ARGUMENT);
    Value result = StringValue.codePoint.call(tstate, s, p);
    Condition.numericLessThan(result, NumValue.ZERO)
        .test(
            () -> tstate.setResults(Core.NONE, addRef(p)),
            () -> {
              Value newPos = ValueUtil.addInts(tstate, p, NumValue.ONE);
              tstate.setResults(result.makeStorable(tstate), newPos);
            });
  }

  /// ```
  /// method getString(String s, Number start, Number end) = s[start + 1 .. end]
  /// ```
  @Core.Method("getString(String, Number, Number)")
  static Value getStringString(TState tstate, Value s, Value start, Value end)
      throws Err.BuiltinException {
    start = start.verifyInt(Err.INVALID_ARGUMENT);
    end = end.verifyInt(Err.INVALID_ARGUMENT);
    Value result = StringValue.substring.call(tstate, s, start, end);
    Err.INVALID_ARGUMENT.when(Condition.isNull(result));
    return result;
  }

  /// ```
  /// method matchString(String s, Number pos=, String sub) = ...
  /// ```
  @Core.Method("matchString(String, Number, String)")
  static void matchStringString(TState tstate, Value s, Value pos, Value sub)
      throws Err.BuiltinException {
    Value iPos = pos.verifyInt(Err.INVALID_ARGUMENT);
    StringValue.substringStartsWith
        .asCondition(tstate, s, iPos, sub)
        .test(
            () -> {
              Value subSize = StringValue.size(tstate, sub);
              Value endPos = ValueUtil.addInts(tstate, iPos, subSize);
              tstate.dropValue(subSize);
              tstate.setResults(Core.TRUE, endPos);
            },
            () -> tstate.setResults(Core.FALSE, iPos.makeStorable(tstate)));
  }

  /// ```
  /// method parseInt(String s, Number pos=) {
  ///   ...
  /// }
  /// ```
  @Core.Method("parseInt(String, Number)")
  static void parseIntString(TState tstate, ResultsInfo results, Value s, Value start)
      throws Err.BuiltinException {
    Value end = StringValue.skipInt.call(tstate, s, start);
    Condition.numericLessThan(end, NumValue.ZERO)
        .testExcept(
            () -> tstate.setResults(Core.NONE, start),
            () -> {
              Value result = StringValue.parseInt(tstate, results, s, start, end);
              tstate.setResults(result, end.makeStorable(tstate));
            });
  }
}
