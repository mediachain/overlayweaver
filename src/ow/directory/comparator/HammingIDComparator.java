package ow.directory.comparator;

import ow.id.ID;
import ow.routing.chord.Hamming;

import java.util.Comparator;

/**
 * Created by yusef on 12/17/15.
 */
public class HammingIDComparator implements KeySimilarityComparator<ID> {

  @Override
  public Comparator<ID> comparatorForKey(ID reference) {
    return (k1, k2) -> {
      int sim1 = (int) (HammingIDComparator.this.similarity(reference, k1) * 100.0);
      int sim2 = (int) (HammingIDComparator.this.similarity(reference, k2) * 100.0);
      return sim1 - sim2;
    };
  }

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
