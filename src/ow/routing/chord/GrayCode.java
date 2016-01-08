package ow.routing.chord;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utility functions for converting to/from binary and Gray Code (reflected binary).
 * Uses BigIntegers of any size.
 *
 * FIXME: These conversions break if given a negative number input.
 *  That may not be an issue for our domain; if so we should at least
 *  throw if we get a negative number.
 */

public class GrayCode {

  private static class LRUCache extends LinkedHashMap<BigInteger, BigInteger> {
    int cacheSize;

    LRUCache(int cacheSize) {
      super(16, 0.75f, true);
      this.cacheSize = cacheSize;
    }

    protected boolean removeEldestEntry(Map.Entry<BigInteger, BigInteger> eldest) {
      return size() >= cacheSize;
    }
  }

  // BigInteger conversion from gray code to binary is crazy slow.
  private static final LRUCache fromGrayMemo = new LRUCache(65536);

  public static BigInteger toGray(BigInteger n) {
    return n.xor(n.shiftRight(1));
  }

  public static BigInteger fromGray(BigInteger n) {

    synchronized (fromGrayMemo) {
      if (fromGrayMemo.containsKey(n)) {
        return fromGrayMemo.get(n);
      }
    }

    BigInteger orig = n;
    for (BigInteger i = n.shiftRight(1);
         i.compareTo(BigInteger.ZERO) != 0;
         n = n.xor(i), i = i.shiftRight(1)) {
      // ;
    }

    synchronized (fromGrayMemo) {
      fromGrayMemo.put(orig, n);
    }
    return n;
  }

  public static BigInteger subtract(BigInteger g1, BigInteger g2) {
    final BigInteger b1 = fromGray(g1);
    final BigInteger b2 = fromGray(g2);
    return toGray(b1.subtract(b2));
  }

  public static BigInteger add(BigInteger g1, BigInteger g2) {
    final BigInteger b1 = fromGray(g1);
    final BigInteger b2 = fromGray(g2);
    return toGray(b1.add(b2));
  }

  public static BigInteger distance(BigInteger g1, BigInteger g2) {
    final BigInteger b1 = fromGray(g1);
    final BigInteger b2 = fromGray(g2);
    return b1.subtract(b2);
  }
}
