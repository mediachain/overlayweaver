package ow.directory.comparator;

/**
 * Created by yusef on 12/17/15.
 */
public class HammingStringComparator implements KeySimilarityComparator<String> {

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
  public double similarity(String key1, String key2) {
    if (key1.length() == 0) {
      throw new IllegalArgumentException("Key length must be > 0");
    }

    final double len = key1.length();
    final double dist = distance(key1, key2);

    return dist / len;
  }
}
