package org.retrolang.impl.core;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import dev.hardwood.HardwoodContext;
import dev.hardwood.InputFile;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ColumnReaders;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.Validity;
import dev.hardwood.schema.ColumnProjection;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.schema.SchemaNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.retrolang.code.CodeValue;
import org.retrolang.code.Op;
import org.retrolang.code.Register;
import org.retrolang.code.ValueInfo;
import org.retrolang.impl.Allocator;
import org.retrolang.impl.BaseType;
import org.retrolang.impl.BuiltinMethod;
import org.retrolang.impl.CodeGen;
import org.retrolang.impl.Condition;
import org.retrolang.impl.Core;
import org.retrolang.impl.Err;
import org.retrolang.impl.FutureValue;
import org.retrolang.impl.MemoryHelper;
import org.retrolang.impl.MethodMemo;
import org.retrolang.impl.NumValue;
import org.retrolang.impl.RC;
import org.retrolang.impl.RValue;
import org.retrolang.impl.RcOp;
import org.retrolang.impl.RefCounted;
import org.retrolang.impl.RefVisitor;
import org.retrolang.impl.ResourceTracker;
import org.retrolang.impl.Scope;
import org.retrolang.impl.SetMemoExtension;
import org.retrolang.impl.StringValue;
import org.retrolang.impl.StructType;
import org.retrolang.impl.TState;
import org.retrolang.impl.Value;
import org.retrolang.impl.ValueUtil;
import org.retrolang.impl.VmFunctionBuilder;
import org.retrolang.util.ArrayUtil;
import org.retrolang.util.Canonical;
import org.retrolang.util.Pair;
import org.retrolang.util.SizeOf;

/// Types and methods for reading [Parquet](https://parquet.apache.org) files as Retrospect
/// collections.
public class ParquetCore {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /// Returns a collection defined by the contents of one or more Parquet files. The argument is a
  /// file name or a glob pattern matching multiple files.
  ///
  /// Each element of the collection is a Struct corresponding to one row of a Parquet file; each
  /// field of the struct corresponds to a column.
  @Core.Public
  static final VmFunctionBuilder pqCollection = VmFunctionBuilder.create("pqCollection", 1);

  /// A collection defined by the contents of one or more Parquet files.
  @Core.Private
  static final BaseType.NonCompositional PQ_COLLECTION =
      Core.newNonCompositional("PQCollection", PQCollection.class, Core.COLLECTION);

  /// An Element identifies a column or group of columns in a Parquet file and their translation to
  /// a Retrospect value. Elements are not exposed directly to clients.
  @Core.Private
  static final BaseType.NonCompositional ELEMENT =
      Core.newNonCompositional("PQElement", Element.class);

  /// A Batch is a sequence of rows read from a Parquet file (via a ColumnReaders).
  @Core.Private
  static final BaseType.NonCompositional BATCH = Core.newNonCompositional("PQBatch", Batch.class);

  /// `private compound PQList is Lambda`
  ///
  /// Elements are `batch` and `element`. A PQList contains all the values of a list or repeated
  /// element in a Batch, concatenated together; each value of the list element is a subsequence of
  /// the PQList.
  @Core.Private static final BaseType.Named PQ_LIST = Core.newBaseType("PQList", 2, Core.LAMBDA);

  /// `private compound PQBytes is Lambda`
  ///
  /// Elements are `batch` and `dataPos`. A PQBytes contains all the values of a byte_array or
  /// fixed_len_byte_array in a Batch, concatenated together; each value of the element is a
  /// subsequence of the PQBytes.
  @Core.Private static final BaseType.Named PQ_BYTES = Core.newBaseType("PQBytes", 2, Core.LAMBDA);

  /// `private compound PQIterator is Iterator`
  ///
  /// Elements are `next`, `batch`, and `future`. The value of `future` will be either ABSENT or
  /// another `[batch, future]` pair.
  ///
  /// A PQIterator returns the rows in a Batch one by one, and then advances the ColumnReaders and
  /// creates a new Batch.
  @Core.Private
  static final BaseType.Named ITERATOR = Core.newBaseType("PQIterator", 3, LoopCore.ITERATOR);

  @Core.Method("pqCollection(String)")
  static Value pqCollection(TState tstate, MethodMemo.Extended mMemo, Value fileNames)
      throws Err.BuiltinException {
    Value result = newPQCollection.call(tstate, mMemo, fileNames);
    Err.INVALID_ARGUMENT.when(Condition.isNull(result));
    return result;
  }

  public static final BuiltinMethod.SimpleRuntimeHelper newPQCollection =
      new BuiltinMethod.SimpleRuntimeHelper();

  /// Implements [#pqCollection]; returns null if the call should error.
  ///
  /// If a new Schema is created it will be added to the extension of `mMemo`.
  @RC.Out
  public static PQCollection newPQCollection(
      TState tstate, MethodMemo.Extended mMemo, StringValue fileNames) {
    List<InputFile> files = expandFileNames(tstate.scope().rootDir(), fileNames.value);
    if (files == null || files.isEmpty()) {
      return null;
    }
    HardwoodContext context = hardwoodContext(tstate.tracker());
    ParquetFileReader reader;
    try {
      reader = ParquetFileReader.openAll(files, context);
    } catch (IOException e) {
      logger.atInfo().withCause(e).log("error opening %s", files);
      return null;
    }
    Element schema;
    try {
      /// Convert the hardwood schema (described by a SchemaNode) into an [Element].
      schema = new SchemaBuilder(tstate.scope(), reader.getFileSchema()).build();
    } catch (UnsupportedOperationException e) {
      logger.atInfo().withCause(e).log("unsupported schema in %s", files);
      safelyClose(reader);
      return null;
    }
    // If we execute this call multiple times (probably rare) and the opened files share the same
    // schema (probably common?), using a SetMemoExtension to canonicalize them will enable us to
    // reuse the code generated for previous executions.
    SetMemoExtension.add(tstate, schema, mMemo);
    schema = (Element) schema.getCanonical();
    fileNames.addRef();
    return new PQCollection(tstate, fileNames, files, reader, schema);
  }

  /// Close `reader`, logging but otherwise ignoring any IOException.
  private static void safelyClose(ParquetFileReader reader) {
    try {
      reader.close();
    } catch (IOException e) {
      logger.atInfo().withCause(e).log("close() errored");
    }
  }

  /// Returns the HardwoodContext that should be used for reading Parquet files.
  private static HardwoodContext hardwoodContext(ResourceTracker tracker) {
    return tracker.scope.getExtra(HardwoodContext.class, HardwoodContext::create);
  }

  /// Match non-glob chars (i.e. anything except '\', '?', '\*', '{', or '[') or any escaped chars.
  private static final Pattern NON_GLOB_PREFIX = Pattern.compile("([^\\\\?*{\\[]|\\\\.)*");

  private static final String GLOB_REPLACEMENT = "xxx";

  /// Returns a list of InputFiles matching the given pattern, or null if we encounter some error.
  static List<InputFile> expandFileNames(Path rootDir, String s) {
    // We want to separate the enclosing directory (before any glob starts) from the
    // possibly-globbed rest of the pattern.
    Matcher m = NON_GLOB_PREFIX.matcher(s);
    if (!m.lookingAt()) {
      throw new AssertionError();
    }
    int prefixLength = m.end();
    if (prefixLength == s.length()) {
      // No glob chars, so a single path
      return ImmutableList.of(InputFile.of(rootDir.resolve(s)));
    }
    // We want to behave consistently whether or not there are non-glob chars after the last "/";
    // the easiest way to do that is to ensure that there's always at least one char.
    String withoutGlob = s.substring(0, prefixLength) + GLOB_REPLACEMENT;
    Path dir = rootDir.resolve(withoutGlob);
    // That should give us a non-globbed directory, and a file within that directory whose name
    // ends in "xxx"; we need to replace the "xxx" with the glob part or the original string and
    // search within that directory.
    String name = dir.getFileName().toString();
    if (!name.endsWith(GLOB_REPLACEMENT)) {
      logger.atWarning().log(
          "resolving \"%s\" yielded \"%s\" which ends in \"%s\"", withoutGlob, dir, name);
      return null;
    }
    dir = dir.getParent();
    String glob =
        name.substring(0, name.length() - GLOB_REPLACEMENT.length()) + s.substring(prefixLength);
    List<Path> paths = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
      stream.forEach(paths::add);
    } catch (IOException | PatternSyntaxException e) {
      logger.atInfo().withCause(e).log("error enumerating \"%s\" in \"%s\"", glob, dir);
      return null;
    }
    // The order in which a DirectoryStream enumerates files is not specified, which seems like a
    // bad idea.
    paths.sort(null);
    return paths.stream().map(InputFile::of).collect(ImmutableList.toImmutableList());
  }

  @Core.Method("iterator(PQCollection, EnumerationKind, Loop, _)")
  static void iteratorPQCollection(
      TState tstate,
      Value collection,
      @RC.Singleton Value eKind,
      @RC.In Value loop,
      @RC.In Value initialState)
      throws Err.BuiltinException {
    Err.INVALID_ARGUMENT.unless(eKind.is(LoopCore.ENUMERATE_VALUES));
    Value future = startRead.call(tstate, collection, loop, initialState);
    // Creating an iterator that starts with an empty batch is the easiest way to reuse the rest
    // of the code.
    Value result = tstate.compound(ITERATOR, NumValue.ZERO, Batch.EMPTY, future);
    tstate.setResults(result, loop, initialState);
  }

  static final BuiltinMethod.SimpleRuntimeHelper startRead =
      new BuiltinMethod.SimpleRuntimeHelper();

  /// Creates a new ColumnReaders
  @RC.Out
  public static FutureValue startRead(
      TState tstate, PQCollection pqc, Value loop, Value initialState) {
    AtomicInteger next = new AtomicInteger(0);
    Element row = pqc.schema.assignDataSlots(next);
    ColumnReaders readers = pqc.reader.columnReaders(ColumnProjection.all());
    pqc.addRef();
    return newFuture(tstate.tracker(), pqc, readers, row, next.getPlain());
  }

  private static final Value EMPTY_FUTURE =
      FutureValue.newCompleted(Allocator.UNCOUNTED, Core.ABSENT);
  private static final Value EMPTY_ITERATOR =
      ITERATOR.uncountedOf(NumValue.ZERO, Batch.EMPTY, EMPTY_FUTURE);

  /// ```
  /// method next(PQIterator it=) {
  ///   {next, batch, future} = it_
  ///   if next >= numRows(batch) {
  ///     nextBatch = waitFor(future)
  ///     if nextBatch is Absent {
  ///       it = EmptyIterator
  ///       return Absent
  ///     }
  ///     [batch, future] = nextBatch
  ///     next = 0
  ///     it_ = {next, batch, future}
  ///   }
  ///   result = batch[next]
  ///   it_.next = next + 1
  ///   return result
  /// }
  /// ```
  static class NextPQIterator extends BuiltinMethod {
    static final Caller waitFor = new Caller("waitFor:1", "afterWaitFor");

    @Core.Method("next(PQIterator)")
    static void begin(TState tstate, @RC.In Value it) {
      // If there are still unread rows in the current batch, just return the next one; otherwise
      // we need to wait for the next batch.
      Condition hasNext;
      if (!(it instanceof RValue)) {
        int next = it.elementAsInt(0);
        Batch batch = (Batch) it.peekElement(1);
        hasNext = Condition.of(next < batch.numRows);
      } else {
        CodeGen codeGen = tstate.codeGen();
        CodeValue next = codeGen.asCodeValue(it.peekElement(0));
        CodeValue batch = codeGen.asCodeValue(it.peekElement(1));
        hasNext = Condition.intLessThan(next, Batch.NUM_ROWS.result(batch));
      }
      hasNext.test(
          () -> tstate.jump("getNext", it),
          () -> {
            Value future = it.element(2);
            tstate.dropValue(it);
            tstate.startCall(waitFor, future);
          });
    }

    @Continuation
    static void afterWaitFor(TState tstate, Value next) {
      // The future's value will either be Absent (meaning we're done) or a [batch, nextFuture] pair
      next.is(Core.ABSENT)
          .test(
              () -> tstate.setResults(Core.ABSENT, EMPTY_ITERATOR),
              () -> {
                Value batch = next.element(0);
                Value future = next.element(1);
                Value it = tstate.compound(ITERATOR, NumValue.ZERO, batch, future);
                tstate.jump("getNext", it);
              });
    }

    @Continuation(order = 2)
    static void getNext(TState tstate, MethodMemo.Extended mMemo, @RC.In Value it) {
      if (!tstate.hasCodeGen()) {
        int next = it.elementAsInt(0);
        Batch batch = (Batch) it.peekElement(1);
        SetMemoExtension.add(tstate, batch.row, mMemo);
        Value result = batch.get(tstate, next);
        Value updatedIt = it.replaceElement(tstate, 0, NumValue.of(next + 1, tstate));
        tstate.setResults(result, updatedIt);
        return;
      }
      CodeGen codeGen = tstate.codeGen();
      CodeValue next = codeGen.asCodeValue(it.peekElement(0));
      CodeValue batch = codeGen.asCodeValue(it.peekElement(1));
      // In most programs we expect there to be only a single Schema for this call site, but
      // in the general case we may have to handle more than one.
      SetMemoExtension.find(
          Element.class,
          codeGen,
          mMemo,
          Batch.ROW.result(batch),
          row -> {
            Value result = row.value(codeGen, batch, next);
            Value nextNext = ValueUtil.addInts(tstate, it.peekElement(0), NumValue.ONE);
            Value updatedIt = it.replaceElement(tstate, 0, nextNext);
            tstate.setResults(result, updatedIt);
          });
    }
  }

  /// A PQCollection holds the ParquetFileReader and an [Element] for its schema. The list of files
  /// is not currently used, but will be needed when we serialize these values.
  public static class PQCollection extends RefCounted implements Value {
    final StringValue fileNames;
    final List<InputFile> files;
    final ParquetFileReader reader;
    private final Element schema;
    final long byteSize;

    PQCollection(
        TState tstate,
        @RC.In StringValue fileNames,
        List<InputFile> files,
        ParquetFileReader reader,
        Element schema) {
      this.fileNames = fileNames;
      this.files = files;
      this.reader = reader;
      this.schema = schema;
      // TODO: read the implementation of ParquetFileReader to get a better estimate of memory use
      this.byteSize = 32000;
      tstate.recordAlloc(this, byteSize);
    }

    @Override
    protected long visitRefs(RefVisitor visitor) {
      visitor.visitRefCounted(fileNames);
      // Close the ParquetFileReader when this value is released.
      if (MemoryHelper.isReleaser(visitor)) {
        safelyClose(reader);
      }
      return byteSize;
    }

    @Override
    public BaseType baseType() {
      return PQ_COLLECTION;
    }

    @Override
    public String toString() {
      return String.format("PQCollection(%s: %s)", fileNames.value, schema);
    }
  }

  /// Returns a FutureValue that constructs a Batch from the given ColumnReaders.
  ///
  /// `pqc` is not used, but we need to maintain a reference to it until we're done, since dropping
  /// all references to it would close the underlying hardwood readers.
  @RC.Out
  static FutureValue newFuture(
      ResourceTracker tracker,
      @RC.In PQCollection pqc,
      ColumnReaders readers,
      Element row,
      int dataSize) {
    // Don't do anything until someone calls waitFor() on the FutureValue; when they do, start
    // a thread to populate the Batch on the context's executor (since it may block until the
    // ColumnReaders is ready).
    return FutureValue.newLazy(
        tracker,
        consumer -> {
          if (consumer == null) {
            // This FutureValue was dropped before anyone called waitFor() on it.
            readers.close();
            TState.dropReferenceWithTracker(tracker, pqc);
          } else {
            // TODO: I'm using a new Thread here because it's likely to block waiting for hardwood
            // reading and decoding.  Is there a better option?
            new Thread(() -> consumer.accept(getFuture(tracker, pqc, readers, row, dataSize)))
                .start();
          }
        });
  }

  /// Waits until `readers` has the next batch of data, then constructs a [Batch] from it and
  /// returns a `[batch, newFuture]` pair.
  @RC.Out
  private static Value getFuture(
      ResourceTracker tracker,
      @RC.In PQCollection pqc,
      ColumnReaders readers,
      Element row,
      int dataSize) {
    String error = null;
    try {
      // I think this call to nextBatch() is where we are most likely to block.
      if (!readers.nextBatch()) {
        // We reached the end of the file(s).
        readers.close();
        TState.dropReferenceWithTracker(tracker, pqc);
        return Core.ABSENT;
      }
    } catch (RuntimeException e) {
      // Some file format errors can cause `nextBatch()` to throw an IllegalStateException.
      logger.atWarning().withCause(e).log();
      error = e.toString();
    }
    // Java's rules around referencing variables in lambdas are pretty lame.
    String finalError = error;
    // We're on one of the HardwoodContext's threads, so we need to set up the TState to
    // do memory tracking properly.
    return TState.withTracker(
        tracker,
        tstate -> {
          String error2 = finalError;
          if (error2 == null) {
            try {
              Batch batch = new BatchBuilder(readers, dataSize).build(tstate, row);
              // Create a new Future for the next batch.
              FutureValue newFuture = newFuture(tracker, pqc, readers, row, dataSize);
              return tstate.arrayValue(batch, newFuture);
            } catch (RuntimeException | AssertionError e) {
              logger.atWarning().withCause(e).log();
              error2 = e.toString();
            }
          }
          readers.close();
          tstate.dropReference(pqc);
          StringValue details = new StringValue(tstate, error2);
          return tstate.compound(Err.INTERNAL_ERROR, details);
        });
  }

  /// A BatchBuilder manages the process of initializing a [Batch] from the current state of a
  /// ColumnReaders. The BatchBuilder is not reusable.
  private static class BatchBuilder {
    final ColumnReaders readers;
    final Object[] data;

    /// As entries are added to [#data], we accumulate their size here so that we know how much
    /// total memory the Batch object is holding.
    private long totalBytes;

    BatchBuilder(ColumnReaders readers, int dataSize) {
      this.readers = readers;
      this.data = new Object[dataSize];
    }

    Batch build(TState tstate, Element row) {
      /// The call to [Element#buildBatch] does all the work, filling in elements of [#data] and
      /// accumulating the total size of the saved arrays in [#totalBytes] (although we're not
      /// allocating that memory, we'll count it as in-use until the Batch object is released).
      this.totalBytes = SizeOf.array(data);
      var unused = row.buildBatch(this, 0);
      return new Batch(tstate, row, data, readers.getRecordCount(), totalBytes);
    }

    /// Called from the various [Element#buildBatch] methods.
    void setData(int index, Object datum, long byteSize) {
      assert data[index] == null;
      data[index] = datum;
      totalBytes += byteSize;
    }

    /// Saves a Validity object (for an optional element) in [#data].
    void setValidity(int index, Validity v) {
      // If no elements are null, leave the Validity slot null
      if (v.hasNulls()) {
        setData(index, v, SizeOf.array(v.words()));
      }
    }
  }

  /// A SchemaBuilder translates hardwood's SchemaNodes into the corresponding Elements. The
  /// SchemaBuilder is not reusable.
  private static class SchemaBuilder {
    /// StructElements need StructTypes, and StructTypes are local to a Scope.
    final Scope scope;

    final FileSchema fileSchema;

    private int nextIndex;

    /// Creates an SchemaBuilder that uses StructTypes from the given Scope.
    SchemaBuilder(Scope scope, FileSchema fileSchema) {
      this.scope = scope;
      this.fileSchema = fileSchema;
    }

    /// Should be called exactly once.
    Element build() {
      return build(fileSchema.getRootNode(), null, false);
    }

    /// Called with the root GroupNode, and then recursively on each of the nested SchemaNodes.
    private Element build(SchemaNode node, String namePrefix, boolean inList) {
      Element result;
      String path = node.name();
      if (namePrefix != null) {
        path = namePrefix + path;
      }
      // Schema nodes marked as optional will be translated to Elements with `hasValidity` true.
      boolean hasValidity = (node.repetitionType() == RepetitionType.OPTIONAL);
      if (node instanceof SchemaNode.PrimitiveNode n) {
        // Each PrimitiveNode type has a corresponding LeafType
        LeafType type = LeafType.forNode(n, fileSchema);
        result = new LeafElement(path, nextIndex++, hasValidity, type);
      } else {
        SchemaNode.GroupNode group = (SchemaNode.GroupNode) node;
        // Currently handles struct and list groups.
        if (group.isStruct()) {
          // A group without special annotations becomes a Retrospect struct
          // The StructElement's dataPos is only used to locate its Validity (if it is optional).
          String childPrefix = (namePrefix == null) ? "" : (path + ".");
          // Build each element of the group, building a list of (name, element) pairs that is
          // sorted by name (fields in a Retrospect struct are always sorted by name).
          List<Pair<String, Element>> pairs =
              group.children().stream()
                  .map(child -> new Pair<>(child.name(), build(child, childPrefix, false)))
                  .sorted(Comparator.comparing(p -> p.x))
                  .toList();
          // Split that into parallel lists of names and elements.
          ImmutableList<String> names =
              pairs.stream().map(pair -> pair.x).collect(ImmutableList.toImmutableList());
          ImmutableList<Element> elements =
              pairs.stream().map(pair -> pair.y).collect(ImmutableList.toImmutableList());
          StructType structType = scope.compoundWithKeys(names);
          result = new StructElement(path, nextIndex++, hasValidity, structType, elements);
        } else if (group.isList()) {
          SchemaNode child = group.getListElement();
          // Proper LIST groups have a 3-level structure, and getListElement() returns the only
          // child of the LIST group's only child.
          // But there are a variety of backward-compatibility rules that handle 2-level structures
          // (see https://parquet.apache.org/docs/file-format/types/logicaltypes/#lists); in those
          // cases getListElement() returns the LIST group's child.
          SchemaNode first = group.children().get(0);
          String childPrefix = (child == first) ? path + "." : path + "." + first.name() + ".";
          result = new ListElement(path, nextIndex++, hasValidity, build(child, childPrefix, true));
        } else {
          // TODO: handle map and variant.
          throw new UnsupportedOperationException();
        }
      }
      // The preferred way to handle sequences is a LIST-annotated group, but we can also handle
      // old-school repeated fields by treating them as if they were in a LIST.
      if (!inList && node.repetitionType() == RepetitionType.REPEATED) {
        // Handle a repeated element as if it had been wrapped in a list group.
        assert !hasValidity;
        result = new ListElement(result.path, nextIndex++, false, result);
      }
      return result;
    }
  }

  /// A schema is represented by a tree of Elements. Element has three subclasses: [LeafElement],
  /// [StructElement], and [ListElement].
  ///
  /// Most Elements correspond to a single hardwood SchemaNode, but due to Parquet's wacky rules for
  /// representing lists, a ListElement may correspond to zero, one, or two SchemaNodes.
  ///
  /// When an iterator is created from a PQCollection, the element tree is copied and the [#index]
  /// of each Element in the copy is replaced with a position in the [Batch#data] array.
  ///
  /// Elements are Values so that they can be part of a [#PQ_LIST] compound. They are never exposed
  /// outside this module.
  ///
  /// Element extends Canonical to ensure that the test in [#atList] is fast.
  ///
  /// TODO: add support for Parquet's Map and Variant types.
  abstract static class Element extends Canonical implements Value {
    // hardwood's name for this element.
    final String path;

    /// Each Element in a Schema's tree is assigned a distinct index, which can be used to locate
    /// associated information (e.g. in the bitmap of which elements are used, when analyzing a loop
    /// body).
    ///
    /// The Elements in a [Batch#row] use their index field differently, as an offset into the
    /// Batch's [Batch#data] array where the element's values are stored.
    final int index;

    /// If true this element is optional (i.e. may be null). Optional elements in a Batch have an
    /// additional data slot at `index - 1` for their Validity.
    final boolean hasValidity;

    private Element(String path, int index, boolean hasValidity, int hashCode) {
      super(hashCode);
      this.path = path;
      this.index = index;
      this.hasValidity = hasValidity;
    }

    // Creates a copy of this element, assigning [#index] values corresponding to data slots
    // beginning at `next`.
    abstract Element assignDataSlots(AtomicInteger next);

    /// Populates this element's slots in the builder's data array from its ColumnReaders. `depth`
    /// is 0 for the schema root, and is incremented as we descend into lists and optional structs
    /// (non-optional structs don't increment depth; see
    /// https://hardwood.dev/latest/concepts/nested-columns/#layers for details).
    ///
    /// Returns a ColumnReader from within the element, which may be useful for a containing element
    /// (sruct or list) to retrieve its validity or offsets.
    abstract ColumnReader buildBatch(BatchBuilder builder, int depth);

    /// Returns this (non-null) element's value for the specified row.
    @RC.Out
    abstract Value valueImpl(TState tstate, Batch batch, int row);

    /// Emits code to compute the result of [#valueImpl(TState, Batch, int)].
    abstract Value valueImpl(CodeGen codeGen, CodeValue batch, CodeValue row);

    /// Returns the value of this element at the given position. `row` is usually the row number,
    /// but for elements nested in a ListElement it will depend on the both the row and the position
    /// in the list.
    @RC.Out
    final Value value(TState tstate, Batch batch, int row) {
      // If the element might be null we can check that in this shared code...
      if (hasValidity && batch.isNull(index - 1, row)) {
        return Core.ABSENT;
      }
      // ... but the rest we leave to the type-specific code.
      return valueImpl(tstate, batch, row);
    }

    /// Emits code to compute the result of [#value(TState, Batch, int)].
    final Value value(CodeGen codeGen, CodeValue batch, CodeValue row) {
      if (!hasValidity) {
        return valueImpl(codeGen, batch, row);
      }
      Condition isNull =
          Condition.isNonZero(BATCH_IS_NULL.result(batch, CodeValue.of(index - 1), row));
      return isNull.choose(() -> Core.ABSENT, () -> valueImpl(codeGen, batch, row));
    }

    @Override
    public final BaseType baseType() {
      return ELEMENT;
    }

    /// Returns a standard suffix for this element's `toString()` method, containing [#hasValidity]
    /// and [#index].
    final String toStringHelper() {
      return String.format("%s(%s)", hasValidity ? "?" : "", index);
    }
  }

  /// A LeafElement corresponds to a primitive schema node; its value is a Number, String, or Matrix
  /// depending on its LeafType. (TODO: support more types -- unsigned ints, decimal, float16, date,
  /// time, timestamp, interval, uuid, json, bson, geometry, geography.)
  static class LeafElement extends Element {
    final LeafType type;

    LeafElement(String path, int index, boolean hasValidity, LeafType type) {
      super(path, index, hasValidity, Objects.hash(path, index, hasValidity, type));
      this.type = type;
    }

    @Override
    Element assignDataSlots(AtomicInteger next) {
      int dataPos = next.getPlain() + (hasValidity ? 1 : 0);
      next.setPlain(dataPos + type.numDataEntries());
      return new LeafElement(path, dataPos, hasValidity, type);
    }

    @Override
    ColumnReader buildBatch(BatchBuilder builder, int depth) {
      ColumnReader reader = builder.readers.getColumnReader(path);
      if (hasValidity) {
        builder.setValidity(index - 1, reader.getLeafValidity());
      }
      type.buildBatch(builder, index, reader);
      return reader;
    }

    @Override
    @RC.Out
    Value valueImpl(TState tstate, Batch batch, int row) {
      return type.valueImpl(tstate, batch, index, row);
    }

    @Override
    Value valueImpl(CodeGen codeGen, CodeValue batch, CodeValue row) {
      return type.valueImpl(codeGen, batch, index, row);
    }

    @Override
    public boolean slowEquals(Object obj) {
      return (obj instanceof LeafElement other)
          && other.index == index
          && other.hasValidity == hasValidity
          && other.type.equals(type)
          && other.path.equals(path);
    }

    @Override
    public String toString() {
      return type + toStringHelper() + " " + path;
    }
  }

  /// A StructElement corresponds to a group schema node; its value is a struct.
  static class StructElement extends Element {
    final StructType structType;

    /// These are ordered to match the (sorted) ordering of [#structType]'s keys.
    final ImmutableList<Element> elements;

    public StructElement(
        String path,
        int index,
        boolean hasValidity,
        StructType structType,
        ImmutableList<Element> elements) {
      super(path, index, hasValidity, Objects.hash(path, index, hasValidity, structType, elements));
      this.structType = structType;
      this.elements = elements;
    }

    @Override
    Element assignDataSlots(AtomicInteger next) {
      int dataPos = -1;
      if (hasValidity) {
        // Note that we set our dataPos to 1 more than the slot we actually use, since validity
        // slots are always one less than the dataPos.
        dataPos = next.getPlain() + 1;
        next.setPlain(dataPos);
      }
      ImmutableList<Element> newElements =
          elements.stream()
              .map(e -> e.assignDataSlots(next))
              .collect(ImmutableList.toImmutableList());
      return new StructElement(path, dataPos, hasValidity, structType, newElements);
    }

    @Override
    ColumnReader buildBatch(BatchBuilder builder, int depth) {
      // See https://hardwood.dev/latest/concepts/nested-columns/#how-schema-nodes-map-to-layers
      int childDepth = depth + (hasValidity ? 1 : 0);
      ColumnReader firstReader = null;
      for (Element e : elements) {
        ColumnReader reader = e.buildBatch(builder, childDepth);
        if (firstReader == null) {
          firstReader = reader;
          if (hasValidity) {
            builder.setValidity(index - 1, reader.getLayerValidity(depth));
          }
        }
      }
      assert firstReader != null;
      return firstReader;
    }

    @Override
    @RC.Out
    Value valueImpl(TState tstate, Batch batch, int row) {
      int n = structType.size();
      Object[] v = tstate.allocObjectArray(n);
      for (int i = 0; i < n; i++) {
        v[i] = elements.get(i).value(tstate, batch, row);
      }
      return tstate.asCompoundValue(structType, v);
    }

    @Override
    Value valueImpl(CodeGen codeGen, CodeValue batch, CodeValue index) {
      int n = structType.size();
      Object[] v = new Object[n];
      for (int i = 0; i < n; i++) {
        v[i] = elements.get(i).value(codeGen, batch, index);
      }
      return codeGen.tstate().asCompoundValue(structType, v);
    }

    @Override
    public boolean slowEquals(Object obj) {
      return (obj instanceof StructElement other)
          && other.structType == structType
          && other.index == index
          && other.hasValidity == hasValidity
          && other.path.equals(path)
          && other.elements.equals(elements);
    }

    @Override
    public String toString() {
      // If we don't have an associated validity, skip the suffix completely since our index
      // is unused.
      return structType.toString(elements::get) + (hasValidity ? "?" : "");
    }
  }

  /// A ListElement corresponds to a list schema node or a simple repeated node; its value is a
  /// one-dimensional matrix.
  static class ListElement extends Element {
    final Element element;

    ListElement(String path, int index, boolean hasValidity, Element element) {
      super(path, index, hasValidity, Objects.hash(path, index, hasValidity, element));
      this.element = element;
    }

    @Override
    Element assignDataSlots(AtomicInteger next) {
      int dataPos = next.getPlain() + (hasValidity ? 1 : 0);
      next.setPlain(dataPos + 1);
      Element newElement = element.assignDataSlots(next);
      return new ListElement(path, dataPos, hasValidity, newElement);
    }

    @Override
    ColumnReader buildBatch(BatchBuilder builder, int depth) {
      ColumnReader reader = element.buildBatch(builder, depth + 1);
      if (hasValidity) {
        builder.setValidity(index - 1, reader.getLayerValidity(depth));
      }
      int[] offsets = reader.getLayerOffsets(depth);
      builder.setData(index, offsets, SizeOf.array(offsets));
      return reader;
    }

    @Override
    Value valueImpl(TState tstate, Batch batch, int row) {
      // Use our offsets to get the appropriate range of the nested element's array.
      int[] offsets = (int[]) batch.data[index];
      int start = offsets[row];
      int end = offsets[row + 1] - 1;
      assert end >= start - 1;
      return batch.newVector(tstate, start, end, PQ_LIST, element);
    }

    @Override
    Value valueImpl(CodeGen codeGen, CodeValue batch, CodeValue row) {
      CodeValue dataPos = CodeValue.of(index);
      CodeValue start = codeGen.materialize(BATCH_GET_INT.result(batch, dataPos, row), int.class);
      CodeValue end = BATCH_GET_INT.result(batch, dataPos, Op.ADD_INTS.result(row, CodeValue.ONE));
      end = codeGen.materialize(Op.SUBTRACT_INTS.result(end, CodeValue.ONE), int.class);
      return Batch.newVector(
          codeGen.tstate(),
          codeGen.toValue(batch, BATCH),
          codeGen.toValue(start),
          codeGen.toValue(end),
          PQ_LIST,
          element);
    }

    @Override
    public boolean slowEquals(Object obj) {
      return (obj instanceof ListElement other)
          && other.index == index
          && other.hasValidity == hasValidity
          && other.path.equals(path)
          && other.element.equals(element);
    }

    @Override
    public String toString() {
      return "List(" + element + ")" + toStringHelper();
    }
  }

  interface LeafType {
    /// The number of data slots used by `required` leaves of this type. (`optional` leaves will use
    /// one additional data slot, for their Validity.)
    int numDataEntries();

    /// Populates this leave's data slots from its ColumnReader.
    void buildBatch(BatchBuilder builder, int dataPos, ColumnReader reader);

    /// Returns one of this leave's values.
    @RC.Out
    Value valueImpl(TState tstate, Batch batch, int dataPos, int row);

    /// Returns one of this leave's values.
    Value valueImpl(CodeGen codeGen, CodeValue batch, int dataPos, CodeValue row);

    static LeafType forNode(SchemaNode.PrimitiveNode node, FileSchema schema) {
      return switch (node.type()) {
        case BOOLEAN -> SimpleLeafType.BOOLEAN;
        case INT32 -> SimpleLeafType.INT32;
        case INT64 -> SimpleLeafType.INT64;
        case INT96 -> throw new UnsupportedOperationException();
        case FLOAT -> SimpleLeafType.FLOAT;
        case DOUBLE -> SimpleLeafType.DOUBLE;
        case BYTE_ARRAY -> {
          if (node.logicalType() instanceof LogicalType.StringType) {
            yield SimpleLeafType.STRING;
          } else {
            yield SimpleLeafType.BYTE_ARRAY;
          }
        }
        case FIXED_LEN_BYTE_ARRAY ->
            new FixedLenLeafType(schema.getColumn(node.columnIndex()).typeLength());
      };
    }
  }

  enum SimpleLeafType implements LeafType {
    BOOLEAN(1, BATCH_GET_BOOLEAN),
    INT32(1, BATCH_GET_INT),
    INT64(1, BATCH_GET_LONG),
    FLOAT(1, BATCH_GET_FLOAT),
    DOUBLE(1, BATCH_GET_DOUBLE),
    BYTE_ARRAY(2, null),
    STRING(2, null);

    final int numDataEntries;
    final String name;
    final Op batchGetOp;

    SimpleLeafType(int numDataEntries, Op batchGetOp) {
      this.numDataEntries = numDataEntries;
      this.batchGetOp = batchGetOp;
      this.name = name().toLowerCase();
    }

    @Override
    public int numDataEntries() {
      return numDataEntries;
    }

    @Override
    public void buildBatch(BatchBuilder builder, int dataPos, ColumnReader reader) {
      switch (this) {
        case SimpleLeafType.BOOLEAN -> {
          boolean[] bytes = reader.getBooleans();
          builder.setData(dataPos, bytes, SizeOf.array(bytes));
        }
        case INT32 -> {
          int[] ints = reader.getInts();
          builder.setData(dataPos, ints, SizeOf.array(ints));
        }
        case INT64 -> {
          long[] longs = reader.getLongs();
          builder.setData(dataPos, longs, SizeOf.array(longs));
        }
        case FLOAT -> {
          float[] floats = reader.getFloats();
          builder.setData(dataPos, floats, SizeOf.array(floats));
        }
        case DOUBLE -> {
          double[] doubles = reader.getDoubles();
          builder.setData(dataPos, doubles, SizeOf.array(doubles));
        }
        case BYTE_ARRAY, STRING -> {
          // byte_array and string are both encoded as an array of bytes and an array of offsets.
          byte[] bytes = reader.getBinaryValues();
          builder.setData(dataPos, bytes, SizeOf.array(bytes));
          int[] offsets = reader.getBinaryOffsets();
          builder.setData(dataPos + 1, offsets, SizeOf.array(offsets));
        }
      }
    }

    @Override
    public Value valueImpl(TState tstate, Batch batch, int dataPos, int row) {
      return switch (this) {
        case BOOLEAN -> Core.bool(batch.getBoolean(dataPos, row));
        case INT32 -> NumValue.of(batch.getInt(dataPos, row), tstate);
        case INT64 -> NumValue.of(batch.getLong(dataPos, row), tstate);
        case FLOAT -> NumValue.orNan(batch.getFloat(dataPos, row), tstate);
        case DOUBLE -> NumValue.orNan(batch.getDouble(dataPos, row), tstate);
        case STRING -> batch.getString(tstate, dataPos, row);
        case BYTE_ARRAY ->
            batch.newVectorFromOffsets(
                tstate, dataPos + 1, row, PQ_BYTES, NumValue.of(dataPos, tstate));
      };
    }

    @Override
    public Value valueImpl(CodeGen codeGen, CodeValue batch, int dataPos, CodeValue row) {
      if (batchGetOp != null) {
        CodeValue cv = batchGetOp.result(batch, CodeValue.of(dataPos), row);
        if (batchGetOp.resultType == double.class) {
          return codeGen.checkForNaN(cv, false);
        } else if (this != BOOLEAN) {
          return codeGen.toValue(codeGen.materialize(cv, batchGetOp.resultType));
        } else {
          return RValue.intToBoolean((Register) codeGen.materialize(cv, int.class));
        }
      }
      return switch (this) {
        case STRING ->
            codeGen.toValue(
                codeGen.materialize(
                    BATCH_GET_STRING.result(
                        batch, codeGen.tstateRegister(), CodeValue.of(dataPos), row),
                    StringValue.class),
                Core.STRING);
        case BYTE_ARRAY ->
            Batch.newVectorFromOffsets(
                codeGen,
                batch,
                CodeValue.of(dataPos + 1),
                row,
                PQ_BYTES,
                NumValue.of(dataPos, Allocator.UNCOUNTED));
        default -> throw new AssertionError();
      };
    }

    @Override
    public String toString() {
      return name;
    }
  }

  record FixedLenLeafType(int byteSize) implements LeafType {
    @Override
    public int numDataEntries() {
      return 1;
    }

    @Override
    public void buildBatch(BatchBuilder builder, int dataPos, ColumnReader reader) {
      byte[] bytes = reader.getBinaryValues();
      builder.setData(dataPos, bytes, SizeOf.array(bytes));
    }

    @Override
    public Value valueImpl(TState tstate, Batch batch, int dataPos, int row) {
      int start = row * byteSize;
      int end = start + byteSize - 1;
      return batch.newVector(tstate, start, end, PQ_BYTES, NumValue.of(dataPos, tstate));
    }

    @Override
    public Value valueImpl(CodeGen codeGen, CodeValue batch, int dataPos, CodeValue row) {
      CodeValue start = Op.MULTIPLY_INTS.result(row, CodeValue.of(byteSize));
      CodeValue end = Op.ADD_INTS.result(start, CodeValue.of(byteSize - 1));
      return Batch.newVector(
          codeGen, batch, start, end, PQ_BYTES, NumValue.of(dataPos, Allocator.UNCOUNTED));
    }

    @Override
    public String toString() {
      return "bytes_" + byteSize;
    }
  }

  /// A Batch is a (non-compositional) Value containing data arrays and Validity objects extracted
  /// from a ColumnReaders.
  ///
  /// While most programs sequentially read each of the rows in a Batch and then move on to the next
  /// one, Batches may be re-read (or read independently by multiple concurrent threads); this
  /// ensures that we match the immutable semantics of Retrospect iterators even though we're
  /// building on the once-through behavior of a ColumnReaders.
  public static class Batch extends RefCounted implements Value {
    private static final long OBJ_SIZE =
        SizeOf.object(RefCounted.BASE_SIZE + 2 * SizeOf.PTR + SizeOf.INT + SizeOf.LONG);

    static final Batch EMPTY = new Batch(Allocator.UNCOUNTED, null, null, 0, 0);

    /// An Element that describes each row.
    public final Element row;

    /// The data arrays and Validity objects.
    public final Object[] data;

    /// The number of rows in this Batch, which should match the size of the top-level arrays and
    /// any corresponding Validity objects. Note that data slots corresponding to elements nested in
    /// a ListElement will typically be larger, with subranges selected by the ListElement's offsets
    /// array.
    public final int numRows;

    /// An estimate of the total memory consumed by this object, including the [#data] array and its
    /// contents.
    final long byteSize;

    static final Op ROW = Op.getField(Batch.class, "row", Element.class);
    static final Op NUM_ROWS = Op.getField(Batch.class, "numRows", int.class);

    Batch(Allocator allocator, Element row, Object[] data, int numRows, long byteSize) {
      this.row = row;
      this.data = data;
      this.numRows = numRows;
      // `byteSize` accounts for `data` and its elements, but not for the Batch object itself.
      byteSize += OBJ_SIZE;
      this.byteSize = byteSize;
      allocator.recordAlloc(this, byteSize);
    }

    @Override
    protected long visitRefs(RefVisitor visitor) {
      return byteSize;
    }

    @Override
    public BaseType baseType() {
      return BATCH;
    }

    /// Returns one row from this Batch.
    @RC.Out
    Value get(TState tstate, int i) {
      return row.value(tstate, this, i);
    }

    /// Reads the Validity object at the specified position in `data`.
    public boolean isNull(int dataPos, int i) {
      Validity v = (Validity) data[dataPos];
      // NO_NULL Validity objects aren't stored, so `null` means all valid.
      return v != null && v.isNull(i);
    }

    /// Reads the `boolean[]` at the specified position in `data`.
    public boolean getBoolean(int dataPos, int i) {
      return ((boolean[]) data[dataPos])[i];
    }

    /// Reads the `int[]` at the specified position in `data`.
    public int getInt(int dataPos, int i) {
      return ((int[]) data[dataPos])[i];
    }

    /// Reads the `long[]` at the specified position in `data`.
    public double getLong(int dataPos, int i) {
      return ((long[]) data[dataPos])[i];
    }

    /// Reads the `float[]` at the specified position in `data`.
    public double getFloat(int dataPos, int i) {
      return ((float[]) data[dataPos])[i];
    }

    /// Reads the `double[]` at the specified position in `data`.
    public double getDouble(int dataPos, int i) {
      return ((double[]) data[dataPos])[i];
    }

    /// Reads a StringValue constructed from the bytes at the specified position in `data`.
    @RC.Out
    public StringValue getString(TState tstate, int dataPos, int i) {
      byte[] bytes = (byte[]) data[dataPos];
      int[] offsets = (int[]) data[dataPos + 1];
      int start = offsets[i];
      int len = offsets[i + 1] - start;
      if (len == 0) {
        return StringValue.EMPTY;
      } else {
        String s = new String(bytes, start, len, StandardCharsets.UTF_8);
        return new StringValue(tstate, s);
      }
    }

    /// Returns a subsequence of a column's values (for byte array or list elements). Equivalent to
    /// the Retrospect code `start..end | lambda(this, arg)`.
    ///
    /// If `lambda` is [#PQ_LIST], `arg` should be the list's element; otherwise `lambda` is
    /// [#PQ_BYTES] and `arg` is the byte array element's dataPos.
    @RC.Out
    Value newVector(TState tstate, int start, int end, BaseType lambda, @RC.In Value arg) {
      addRef();
      return newVector(
          tstate, this, NumValue.of(start, tstate), NumValue.of(end, tstate), lambda, arg);
    }

    /// Returns a subsequence of a column's values (for byte array or list elements). Equivalent to
    /// the Retrospect code `start..end | lambda(batch, arg)`.
    static Value newVector(
        CodeGen codeGen,
        CodeValue batch,
        CodeValue start,
        CodeValue end,
        BaseType lambda,
        Value arg) {
      return newVector(
          codeGen.tstate(),
          codeGen.toValue(batch, BATCH),
          codeGen.toValue(start),
          codeGen.toValue(end),
          lambda,
          arg);
    }

    @RC.Out
    private static Value newVector(
        TState tstate,
        @RC.In Value batch,
        @RC.In Value start,
        @RC.In Value end,
        BaseType lambda,
        @RC.In Value arg) {
      Value keys = tstate.compound(Core.RANGE, start, end);
      Value transform = tstate.compound(lambda, batch, arg);
      return tstate.compound(CollectionCore.TRANSFORMED_MATRIX, keys, transform);
    }

    /// Returns a subsequence of a column's values (for byte array or list elements); the start and
    /// end of the subsequence are loaded from an offsets array at `dataPos`.
    @RC.Out
    public Value newVectorFromOffsets(
        TState tstate, int dataPos, int i, BaseType lambda, @RC.In Value arg) {
      int[] offsets = (int[]) data[dataPos];
      int start = offsets[i];
      int end = offsets[i + 1] - 1;
      return newVector(tstate, start, end, lambda, arg);
    }

    /// Returns a subsequence of a column's values (for byte array or list elements); the start and
    /// end of the subsequence are loaded from an offsets array at `dataPos`.
    @RC.Out
    public static Value newVectorFromOffsets(
        CodeGen codeGen,
        CodeValue batch,
        CodeValue dataPos,
        CodeValue index,
        BaseType lambda,
        Value arg) {
      CodeValue start = codeGen.materialize(BATCH_GET_INT.result(batch, dataPos, index), int.class);
      CodeValue end =
          BATCH_GET_INT.result(batch, dataPos, Op.ADD_INTS.result(index, CodeValue.ONE));
      end = codeGen.materialize(Op.SUBTRACT_INTS.result(end, CodeValue.ONE), int.class);
      return newVector(codeGen, batch, start, end, lambda, arg);
    }

    @Override
    public String toString() {
      return (this == EMPTY) ? "EmptyBatch" : super.toString();
    }
  }

  public static final BuiltinMethod.SimpleRuntimeHelper uint8FromBatch =
      new BuiltinMethod.SimpleRuntimeHelper().canSimplifyConstants();

  public static int uint8FromBatch(Batch batch, int dataPos, int i) {
    return ArrayUtil.bytesGetB((byte[]) batch.data[dataPos], i);
  }

  /// `method at(PQList list, i) = ...`
  ///
  /// Returns one element of a ListElement's value; `i` is the offset for the start of the value
  /// plus the position within the list. See [Batch#newVector].
  @Core.Method("at(PQList, _)")
  static void atList(TState tstate, MethodMemo.Extended mMemo, Value list, Value i)
      throws Err.BuiltinException {
    i = i.verifyInt(Err.INVALID_ARGUMENT);
    if (!tstate.hasCodeGen()) {
      Batch batch = (Batch) list.peekElement(0);
      Element element = (Element) list.peekElement(1);
      SetMemoExtension.add(tstate, element, mMemo);
      tstate.setResult(element.value(tstate, batch, NumValue.asInt(i)));
      return;
    }
    CodeGen codeGen = tstate.codeGen();
    CodeValue batch = codeGen.asCodeValue(list.peekElement(0));
    CodeValue elementCv = codeGen.asCodeValue(list.peekElement(1));
    CodeValue iCv = codeGen.asCodeValue(i);
    // In most programs we expect there to be only a single Element for this call site, but
    // in the general case we may have to handle more than one.
    SetMemoExtension.find(
        Element.class,
        codeGen,
        mMemo,
        elementCv,
        element -> tstate.setResult(element.value(codeGen, batch, iCv)));
  }

  /// `method at(PQBytes bytes, i) = ...`
  ///
  /// Returns one element of a byte array; `i` is the offset for the start of the value plus the
  /// position within the array. See [Batch#newVector].
  @Core.Method("at(PQBytes, _)")
  static Value atBytes(TState tstate, Value bytes, Value i) throws Err.BuiltinException {
    i = i.verifyInt(Err.INVALID_ARGUMENT);
    Value batch = bytes.peekElement(0);
    Value dataPos = bytes.peekElement(1);
    return uint8FromBatch.call(tstate, batch, dataPos, i);
  }

  /// A ValueInfo for Ops that return Java booleans.
  private static final ValueInfo BOOLEAN_INT = ValueInfo.IntRange.of(0, 1);

  private static final Op BATCH_IS_NULL =
      RcOp.forRcMethod(Batch.class, "isNull", int.class, int.class).build();
  private static final Op BATCH_GET_BOOLEAN =
      RcOp.forRcMethod(Batch.class, "getBoolean", int.class, int.class)
          .withInfo((args, registerInfo) -> BOOLEAN_INT)
          .build();
  private static final Op BATCH_GET_INT =
      RcOp.forRcMethod(Batch.class, "getInt", int.class, int.class).build();
  private static final Op BATCH_GET_LONG =
      RcOp.forRcMethod(Batch.class, "getLong", int.class, int.class).build();
  private static final Op BATCH_GET_FLOAT =
      RcOp.forRcMethod(Batch.class, "getFloat", int.class, int.class).build();
  private static final Op BATCH_GET_DOUBLE =
      RcOp.forRcMethod(Batch.class, "getDouble", int.class, int.class).build();
  private static final Op BATCH_GET_STRING =
      RcOp.forRcMethod(Batch.class, "getString", TState.class, int.class, int.class).build();
}
