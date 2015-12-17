package ow.directory.comparator;

import java.util.Comparator;

/**
 * Created by yusef on 12/17/15.
 */
public interface KeySimilarityComparator<K> {

  double similarity(K key1, K key2);
}
