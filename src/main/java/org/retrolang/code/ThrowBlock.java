package org.retrolang.code;

import org.objectweb.asm.Opcodes;

/** A Block that emits an ATHROW opcode. */
public class ThrowBlock extends Block.Terminal {
  final CodeValue throwable;

  /**
   * {@code throwable} should be a call to the Throwable's constructor. For simplicity this class
   * currently assumes that it does not reference any registers.
   *
   * <p>(Supporting a throwable that depends on execution state would be straightforward, but I
   * don't anticipate needing it so I didn't bother with the extra code that would be required.)
   */
  public ThrowBlock(CodeValue throwable) {
    // A simple way to throw a NullPointerException if throwable references any registers.
    throwable.getLive(true, null);
    this.throwable = throwable;
  }

  @Override
  void runForwardProp(boolean incremental) {}

  @Override
  public Block emit(Emitter emitter) {
    throwable.push(emitter, Throwable.class);
    emitter.mv.visitInsn(Opcodes.ATHROW);
    return null;
  }

  @Override
  public String toString(CodeBuilder.PrintOptions options) {
    return "throw " + throwable.toString(options);
  }
}
