package ow.dht;

import ow.id.ID;

import java.io.Serializable;
import java.util.Set;

/**
 * Created by yusef on 12/14/15.
 */
public interface SimilarityDHT<V extends Serializable> extends DHT<V> {

  /**
   * Returns values for keys within `similarity` threshold of `key`
   * @param key
   * @param similarity - threshold within which similar keys w
   * @return
   */
  Set<ValueInfo<V>> getSimilar(ID key, Float similarity);
}
