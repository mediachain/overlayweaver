package ow.directory.comparator;

import ow.id.ID;
import ow.routing.chord.Hamming;

import java.io.Serializable;
import java.util.Comparator;

/**
 * Created by yusef on 12/17/15.
 */
public class HammingIDComparator implements KeySimilarityComparator<ID>, Serializable {

  public class KeyComparator implements Comparator<ID>, Serializable {
    private final ID reference;
    public KeyComparator(ID reference) {
      this.reference = reference;
    }

    @Override
    public int compare(ID k1, ID k2) {
      int sim1 = (int) (HammingIDComparator.getSimilarity(reference, k1) * 100.0);
      int sim2 = (int) (HammingIDComparator.getSimilarity(reference, k2) * 100.0);
      return sim1 - sim2;
    }
  }


  @Override
  public Comparator<ID> comparatorForKey(ID reference) {
    return new KeyComparator(reference);
  }

  @Override
  public float similarity(ID key1, ID key2) {
    return HammingIDComparator.getSimilarity(key1, key2);
  }

  public static float getSimilarity(ID key1, ID key2) {
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

    return (keySizeInBits - hammingDistance) / keySizeInBits;
  }
}
