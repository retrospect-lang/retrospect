package org.retrolang.testing;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.retrolang.code.DebugInfo;
import org.retrolang.impl.CodeGenManager;
import org.retrolang.impl.Template;
import org.retrolang.util.StringUtil;

/**
 * An implementation of {@link CodeGenManager.Monitor} that enables call counting and saves the
 * values passed to {@link #loaded} so that they can be checked later.
 */
public class TestMonitor implements CodeGenManager.Monitor {
  private static final boolean LOG_BYTECODES = false;

  final boolean verbose;
  public final List<Supplier<String>> counters = new ArrayList<>();
  public final StringBuilder blocks = new StringBuilder();

  public TestMonitor(boolean verbose) {
    this.verbose = verbose;
  }

  /** Summarizes the call counters for each target loaded with this Monitor. */
  public String counters() {
    return counters.stream().map(Supplier::get).collect(Collectors.joining(", "));
  }

  @Override
  public synchronized void loaded(
      String name, ImmutableList<Template> args, Supplier<String> counter, DebugInfo debugInfo) {
    System.out.println(debugInfo.blocks);
    String argsToString = StringUtil.joinElements("(", ")", args.size(), args::get);
    blocks.append("\n").append(name).append(argsToString).append(":\n").append(debugInfo.blocks);
    if (LOG_BYTECODES) {
      System.out.println(name + ":\n" + CodeGenManager.Monitor.dumpBytecode(debugInfo));
    }
    // If loading failed we'll be called with counter == null
    if (counter != null) {
      counters.add(counter);
    }
  }

  @Override
  public boolean countCalls() {
    return true;
  }

  @Override
  public boolean verbose() {
    return verbose;
  }
}
