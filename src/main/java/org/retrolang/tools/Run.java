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

package org.retrolang.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.antlr.v4.runtime.CharStreams;
import org.retrolang.Vm;
import org.retrolang.compiler.Compiler;
import org.retrolang.impl.VirtualMachine;

/// A simple command-line tool for running a single Retrospect program.
public class Run {
  private Run() {}

  private static void checkUsage(boolean condition) {
    if (!condition) {
      System.err.println("Use: run <fileName> [ <var>=<val> ...]");
      System.exit(1);
    }
  }

  /// If any async threads are still executing, let them complete before we check anything else.
  private static void waitForThreadCompletion() {
    if (!ForkJoinPool.commonPool().awaitQuiescence(2, TimeUnit.SECONDS)) {
      System.err.println("Threads still running!");
    }
  }

  public static void main(String[] args) throws IOException {
    String memoryLimitProp = System.getProperty("memoryLimit");
    long memoryLimit = (memoryLimitProp == null) ? 200_000 : Long.parseLong(memoryLimitProp);
    int maxTraces = Integer.parseInt(System.getProperty("maxTraces", "12"));
    boolean rcDebug = Boolean.parseBoolean(System.getProperty("rcDebug", "false"));
    checkUsage(args.length != 0);
    Path file = Path.of(args[0]);
    String fileName = file.getFileName().toString();
    String[] argNames = new String[args.length - 1];
    String[] argValueStrings = new String[args.length - 1];
    for (int i = 1; i < args.length; i++) {
      int eq = args[i].indexOf('=');
      checkUsage(eq > 0);
      argNames[i - 1] = args[i].substring(0, eq).trim();
      argValueStrings[i - 1] = args[i].substring(eq + 1).trim();
    }
    String argsAsString = Arrays.stream(args, 1, args.length).collect(Collectors.joining(", "));
    // Compile the program into a new module
    VirtualMachine vm = new VirtualMachine();
    vm.enableDebugging();
    Vm.ModuleBuilder module = vm.newModule("(input)");
    Vm.InstructionBlock insts =
        Compiler.compile(CharStreams.fromPath(file), fileName, vm, module, argNames);
    module.build();
    // Now set up to execute the compiled code, passing any arg values that were given.
    Vm.ResourceTracker tracker = vm.newResourceTracker(memoryLimit, maxTraces, rcDebug);
    // Currently only supports integer-valued arguments
    Vm.Value[] argValues =
        Arrays.stream(argValueStrings)
            .map(a -> tracker.asValue(Integer.parseInt(a)))
            .toArray(Vm.Value[]::new);
    try (Vm.Value result = insts.applyToArgs(tracker, argValues)) {
      waitForThreadCompletion();
      String limit = memoryLimitProp == null ? "" : " LIMIT " + memoryLimitProp;
      System.out.printf("/* RUN (%s)%s RETURNS\n  %s\n", argsAsString, limit, result);
    } catch (Vm.RuntimeError e) {
      waitForThreadCompletion();
      System.out.printf("/* RUN (%s) ERRORS\n", argsAsString);
      e.stack().forEach(line -> System.out.println("  " + line));
      e.close();
    }
    String traces = tracker.takeTraces();
    if (!traces.isEmpty()) {
      System.out.println("---\n" + traces);
    }
    System.out.println("---");
    // Now everything should have been released
    if (tracker.allReleased()) {
      System.out.printf("%s\n%s\n", tracker, vm.getDebuggingSummary());
    } else {
      System.out.printf("Reference counting error: %s\n", tracker);
    }
    System.out.println("*/");
  }
}
