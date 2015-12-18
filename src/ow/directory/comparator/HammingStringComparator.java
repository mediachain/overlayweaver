package ow.directory.comparator;

import ow.id.ID;

import java.io.Serializable;
import java.util.Comparator;

/**
 * Created by yusef on 12/17/15.
 */
public class HammingStringComparator implements KeySimilarityComparator<String>, Serializable {
  public class KeyComparator implements Comparator<String>, Serializable {
    private final String reference;
    public KeyComparator(String reference) {
      this.reference = reference;
    }

    @Override
    public int compare(String k1, String k2) {
      int sim1 = (int) (HammingStringComparator.getSimilarity(reference, k1) * 100.0);
      int sim2 = (int) (HammingStringComparator.getSimilarity(reference, k2) * 100.0);
      return sim1 - sim2;
    }
  }

  @Override
  public Comparator<String> comparatorForKey(String reference) {
    return new KeyComparator(reference);
  }

  public static int distance(String key1, String key2) {
    if (key1 == null || key2 == null || key1.length() != key2.length()) {
      throw new IllegalArgumentException();
    }

    int distance = 0;
    for (int i = 0; i < key1.length(); i++) {
      if (key1.charAt(i) != key2.charAt(i)) {
        distance += 1;
      }
    }
    return distance;
  }


  @Override
  public float similarity(String key1, String key2) {
    return HammingStringComparator.getSimilarity(key1, key2);
  }

  public static float getSimilarity(String key1, String key2) {
    if (key1.length() == 0) {
      throw new IllegalArgumentException("Key length must be > 0");
    }

    final float len = key1.length();
    final float dist = distance(key1, key2);

    return (len - dist) / len;
  }
}
