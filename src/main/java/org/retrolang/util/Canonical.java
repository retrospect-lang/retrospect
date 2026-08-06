package org.retrolang.util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;

/// Inheriting from Canonical enables a subclass to ensure that each instance can be efficiently
/// mapped to a canonical instance, ensuring constant-time successful equality checks.
public abstract class Canonical {
  /// Precomputing the hashCode means that failing equality checks can usually be constant-time as
  /// well.
  final int hash;

  /// The canonical instance of an equivalence class will be the one with the lowest order.
  final long order;

  /// Newly-created instances are in an equivalence class by themselves, and hence are their own
  /// canonical instance.
  private Canonical canonical = this;

  static final VarHandle CANONICAL;

  static {
    MethodHandles.Lookup lookup = MethodHandles.lookup();
    try {
      CANONICAL = lookup.findVarHandle(Canonical.class, "canonical", Canonical.class);
    } catch (ReflectiveOperationException e) {
      throw new LinkageError("Shouldn't happen", e);
    }
  }

  private static final AtomicLong nextOrder = new AtomicLong();

  protected Canonical(int hash) {
    this.hash = hash;
    this.order = nextOrder.getAndIncrement();
  }

  // Returns this instance's canonical instance.
  public Canonical getCanonical() {
    if (canonical == this) {
      // No synchronization needed
      return this;
    }
    // We're going to have to be more careful.
    Canonical c = this;
    for (; ; ) {
      Canonical result = (Canonical) CANONICAL.getAcquire(c);
      if (result.canonical == result) {
        if (c != this) {
          assert result.hash == hash;
          CANONICAL.setRelease(this, result);
        }
        return result;
      }
      c = result;
    }
  }

  // Ensures that `c1` and `c2` are in the same equivalence group (and hence have the same canonical
  // instance).  Returns their shared canonical instance.
  protected static Canonical setEquivalent(Canonical c1, Canonical c2) {
    c1 = c1.getCanonical();
    c2 = c2.getCanonical();
    while (c1 != c2) {
      assert c1.order != c2.order;
      if (c1.order > c2.order) {
        Canonical t = c2;
        c2 = c1;
        c1 = t;
      }
      Canonical prev = (Canonical) CANONICAL.compareAndExchange(c2, c2, c1);
      if (prev == c2) {
        break;
      }
      c2 = prev;
    }
    return c1;
  }

  /// Subclasses should implement this they way they normally would `equals()`; it will only be
  /// called if the faster checks fail.
  protected abstract boolean slowEquals(Object other);

  @Override
  public final boolean equals(Object obj) {
    if (canonical == obj) {
      return true;
    } else if (!(obj instanceof Canonical other)) {
      return false;
    } else {
      Canonical c1 = getCanonical();
      Canonical c2 = other.getCanonical();
      if (c1 == c2) {
        assert hash == other.hash;
        return true;
      } else if (hash != other.hash || !slowEquals(obj)) {
        return false;
      }
      setEquivalent(c1, c2);
      return true;
    }
  }

  @Override
  public final int hashCode() {
    return hash;
  }
}
