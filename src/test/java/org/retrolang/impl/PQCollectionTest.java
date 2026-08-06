package org.retrolang.impl;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import dev.hardwood.InputFile;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.row.PqList;
import dev.hardwood.row.StructAccessor;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.antlr.v4.runtime.CharStreams;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.retrolang.Vm;
import org.retrolang.compiler.Compiler;
import org.retrolang.testing.TestMonitor;
import org.retrolang.util.Pair;
import org.retrolang.util.StringUtil;

/// Reads a bunch of test files using hardwood's RowReader, and then again using ParquetCore, and
/// then compares the results to confirm that they are the same.
///
/// These test files were copied from
/// https://github.com/hardwood-hq/hardwood/tree/main/core/src/test/resources; I believe that they
/// provide a reasonably thorough exercise of the parquet file schema possibilities.
@RunWith(TestParameterInjector.class)
public class PQCollectionTest {
  static final Path INPUTS = Path.of("src/test/java/org/retrolang/impl/testdata/pq_inputs");

  /// A simple Retrospect program that reads the specified parquet file and copies it into an array
  /// of simple structs. It uses a recursive copier to ensure that all the PQLists are converted to
  /// simple arrays.
  static final String TEST_PROGRAM =
      """
      return load(f)

      // Call copyLists() on each row of the table, and then return an array of rows.
      function load(f) = copyLists(^pqCollection(f)) | saveSequential

      function copyLists(x) = (x is Collection) ? copyLists(^x) | save : x
      """;

  static final long BYTE_LIMIT = 16_000_000;

  @Before
  public void setup() {
    TState.reset();
  }

  @Test
  public void runOne(@TestParameter(valuesProvider = TestFilesProvider.class) String fileName)
      throws IOException, Vm.RuntimeError {
    // The simplest way to compare the output of RowReader to the output of ParquetCore is to
    // convert them both to strings (one line per row).
    StringBuilder sb = new StringBuilder();
    try (ParquetFileReader fileReader =
        ParquetFileReader.open(InputFile.of(INPUTS.resolve(fileName)))) {
      System.out.println(fileReader.getFileSchema());
      RowReader rowReader = fileReader.rowReader();
      while (rowReader.hasNext()) {
        rowReader.next();
        sb.append(structToString(rowReader)).append("\n");
      }
    }
    String expected = sb.toString();

    // Create a VM and configure it to exline the `load` function.
    VirtualMachine vm = new VirtualMachine();
    vm.scope.setRootDir(INPUTS);
    vm.scope.codeGenManager.setMonitor(new TestMonitor(false));
    ModuleBuilder module = (ModuleBuilder) vm.newModule("(input)");
    vm.scope.setForceExlined(
        method -> method.function.name.equals("load") && method.impl.module() == module.module);
    Vm.InstructionBlock instBlock =
        Compiler.compile(CharStreams.fromString(TEST_PROGRAM), "read_pq", vm, module, "f");
    InstructionBlock ib = ((VmInstructionBlock) instBlock).ib;
    module.build();
    MethodMemo mMemo = ib.memoForApply();

    String result = run(vm, ib, mMemo, fileName);
    assertThat(result).isEqualTo(expected);

    vm.scope.generateCodeForForcedMethods();
    String result2 = run(vm, ib, mMemo, fileName);
    assertThat(result2).isEqualTo(expected);
  }

  private static String run(
      VirtualMachine vm, InstructionBlock ib, MethodMemo mMemo, String fileName)
      throws Vm.RuntimeError {
    ResourceTracker tracker = vm.newResourceTracker(BYTE_LIMIT, /* maxTraces= */ 6, false);
    TState.resetAndGet().bindTo(tracker);
    StringBuilder sb = new StringBuilder();
    try {
      Value v = ib.applyToArgs(tracker, mMemo, tracker.asValue(fileName));
      int size = v.numElements();
      for (int i = 0; i < size; i++) {
        sb.append(v.peekElement(i)).append("\n");
      }
      TState.dropReferenceWithTracker(tracker, (RefCounted) v);
    } finally {
      // If any async threads are still executing, let them complete before we check anything else.
      assertThat(ForkJoinPool.commonPool().awaitQuiescence(2, TimeUnit.SECONDS)).isTrue();
    }
    System.out.println(tracker.takeTraces(1, false));
    assertWithMessage("Reference counting error: %s", tracker).that(tracker.allReleased()).isTrue();
    System.out.println(tracker);
    return sb.toString();
  }

  /// Converts the output of a RowReader to what `toString()` should return on the resulting
  /// Retrospect object.
  private static String structToString(StructAccessor struct) {
    // Sort the fields by key, and convert each value with our custom toString() method.
    return IntStream.range(0, struct.getFieldCount())
        .mapToObj(i -> new Pair<>(struct.getFieldName(i), struct.getValue(i)))
        .sorted(Comparator.comparing(p -> p.x))
        .map(pair -> pair.x + ": " + toString(pair.y))
        .collect(Collectors.joining(", ", "{", "}"));
  }

  /// Converts one element of a RowReader output to what `toString()` should return on the
  /// corresponding Retrospect object.
  private static String toString(Object object) {
    return switch (object) {
      case null -> "Absent";
      case Boolean b -> b ? "True" : "False";
      case StructAccessor struct -> structToString(struct);
      case PqList list ->
          list.values().stream()
              .map(PQCollectionTest::toString)
              .collect(Collectors.joining(", ", "[", "]"));
      case String s -> StringUtil.escape(s);
      case byte[] bytes -> toStringBytes(bytes);
      case UUID uuid -> toStringHack(uuid);
      case Instant instant -> toStringHack(instant);
      default ->
          // All that's left at this point are numbers.
          Objects.toString(object);
    };
  }

  private static String toStringBytes(byte[] bytes) {
    if (bytes.length == 0) {
      return "[]";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < bytes.length; i++) {
      sb.append(i == 0 ? "[" : ", ");
      sb.append(bytes[i] & 255);
    }
    return sb.append("]").toString();
  }

  private static String toStringHack(UUID uuid) {
    // TODO: remove this hack once ParquetCore supports UUID-typed leaves
    long scratch = uuid.getMostSignificantBits();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 16; i++) {
      sb.append(i == 0 ? "[" : ", ");
      sb.append((scratch >>> 56) & 255);
      scratch = (i == 7) ? uuid.getLeastSignificantBits() : (scratch << 8);
    }
    return sb.append("]").toString();
  }

  private static String toStringHack(Instant instant) {
    // TODO: remove this hack once ParquetCore supports TIMESTAMP-typed leaves
    return Double.toString(instant.getEpochSecond() * 1000d);
  }

  static class TestFilesProvider extends TestParameterValuesProvider {
    /// Returns a list of all `.parquet` file names in the [#INPUTS] directory.
    @Override
    public final ImmutableList<String> provideValues(Context context) {
      ImmutableList.Builder<String> results = new ImmutableList.Builder<>();
      try (DirectoryStream<Path> files = Files.newDirectoryStream(INPUTS, "*.parquet")) {
        files.forEach(path -> results.add(path.getFileName().toString()));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return results.build();
    }
  }
}
