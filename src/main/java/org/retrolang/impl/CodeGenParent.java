package org.retrolang.impl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * An indirect reference to a CodeGenLink that can be broken (but not repaired).
 *
 * <p>A CodeGenParent is used to cache the current value of {@link MethodMemo#codeGenParent} in a
 * way that can be invalidated if a new CodeGenLink is created.
 */
class CodeGenParent {
  private CodeGenLink link;

  CodeGenParent(CodeGenLink link) {
    LINK.setRelease(this, link);
  }

  private static final VarHandle LINK =
      Handle.forVar(MethodHandles.lookup(), CodeGenParent.class, "link", CodeGenLink.class);

  /** An already-invalidated CodeGenParent. */
  static final CodeGenParent INVALID = new CodeGenParent(null);

  CodeGenLink link() {
    return (CodeGenLink) LINK.getAcquire(this);
  }

  void clear() {
    LINK.setOpaque(this, null);
  }

  @Override
  public String toString() {
    CodeGenLink link = link();
    return (link == null) ? "--" : link.mm.stack();
  }
}
