package ow.directory.comparator;

import ow.id.ID;

/**
 * Created by yusef on 12/17/15.
 */
public class HammingIDComparator implements KeySimilarityComparator<ID> {

  @Override
  public float similarity(ID key1, ID key2) {
    if (key1.getSize() != key2.getSize()) {
      throw new IllegalArgumentException("Keys must have equal sizes for similarity comparison");
    }

    final float keySizeInBits = key1.getSize() * 8;
    if (keySizeInBits == 0) {
      // yay, paranoia!
      throw new IllegalArgumentException("Key size of zero is invalid.");
    }

    final float hammingDistance =
        key1.toBigInteger().xor(key2.toBigInteger()).bitCount();
    return hammingDistance / keySizeInBits;
  }
}
