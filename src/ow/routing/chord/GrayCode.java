package ow.routing.chord;

import java.math.BigInteger;

/**
 * Utility functions for converting to/from binary and Gray Code (reflected binary).
 * Uses BigIntegers of any size.
 *
 * FIXME: These conversions break if given a negative number input.
 *  That may not be an issue for our domain; if so we should at least
 *  throw if we get a negative number.
 */
public class GrayCode {

  public static BigInteger toGray(BigInteger n) {
    return n.xor(n.shiftRight(1));
  }

  public static BigInteger fromGray(BigInteger n) {
    for (BigInteger i = n.shiftRight(1);
         i.compareTo(BigInteger.ZERO) != 0;
         n = n.xor(i), i = i.shiftRight(1)) {
      // ;
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
    // Need to ensure that the result is not negative, since
    // conversion breaks for negative numbers
    BigInteger larger, smaller;
    if (b1.compareTo(b2) > 0) {
      larger = b1;
      smaller = b2;
    } else {
      larger = b2;
      smaller = b1;
    }

    return toGray(larger.subtract(smaller));
  }
}
