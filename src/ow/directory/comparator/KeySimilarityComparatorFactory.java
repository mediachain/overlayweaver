package ow.directory.comparator;

import ow.id.ID;

import java.util.logging.Logger;

/**
 * Created by yusef on 12/17/15.
 */
public class KeySimilarityComparatorFactory {
  private final static Logger logger = Logger.getLogger("directory");

  public static final String METRIC_HAMMING = "Hamming";

  @SuppressWarnings("unchecked")
  public static <K> KeySimilarityComparator<K> getComparator(Class keyClass, String metric) {
    if (!metric.equals(METRIC_HAMMING)) {
      logger.warning("Similarity metric " + metric + " is not supported. " +
       "Currently the only supported metric is " + METRIC_HAMMING);
      return null;
    }

    if (ID.class.isAssignableFrom(keyClass)) {
      return (KeySimilarityComparator<K>) new HammingIDComparator();
    }

    if (String.class.isAssignableFrom(String.class)) {
      return (KeySimilarityComparator<K>) new HammingStringComparator();
    }

    logger.warning("Key class " + keyClass.getName() + " is not supported for similarity comparison");
    return null;
  }
}
