package ow.directory.comparator;

import ow.id.ID;

import java.util.Comparator;

/**
 * Created by yusef on 12/17/15.
 */
public class HammingStringComparator implements KeySimilarityComparator<String> {

  @Override
  public Comparator<String> comparatorForKey(String reference) {
    return (k1, k2) -> {
      int sim1 = (int) (HammingStringComparator.this.similarity(reference, k1) * 100.0);
      int sim2 = (int) (HammingStringComparator.this.similarity(reference, k2) * 100.0);
      return sim1 - sim2;
    };
  }

  public int distance(String key1, String key2) {
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
    if (key1.length() == 0) {
      throw new IllegalArgumentException("Key length must be > 0");
    }

    final float len = key1.length();
    final float dist = distance(key1, key2);

    return dist / len;
  }
}
