/*
 * Copyright 2006-2009 National Institute of Advanced Industrial Science
 * and Technology (AIST), and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ow.directory;

import ow.directory.comparator.KeySimilarityComparator;

import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;

/**
 *	A directory that maps keys to values.
 * It allows multiple (different) values for a key, but does not hold same values for the key.
 */
public interface MultiValueDirectory<K,V> extends Iterable<Map.Entry<K,V>> {
	/**
	 * Returns a set of values associated to the specified key.
	 *
	 * @return Null if no value was found.
	 */
	Set<V> get(K key) throws Exception;

	/**
	 * Returns the comparator used to determine key similarity.
	 * @return null if similarity comparison is not supported
   */
	KeySimilarityComparator<K> getSimilarityComparator();

	/**
	 * Returns the set of keys similar to `key` within `threshold`,
	 * using the KeySimilarityComparator specified in the config.
	 * @return empty set if no similar keys are found
   */
	Set<K> getSimilarKeys(K key, float threshold) throws Exception;

	/**
	 * Returns a set of key/value pairs for keys that are similar to `key`, within `threshold`
	 * @return null if no values are found
   */
	Map<K,Set<V>> getSimilar(K key, float threshold) throws Exception;

	/**
	 * Puts a pair of the specified key and value.
	 * Multiple values associated to the same key can be stored, but same values (equals()) are unified.
	 */
	V put(K key, V value) throws Exception;

	/**
	 * Removes a pair of the specified key and value.
	 *
	 * @return The associated value if found. Null if not found.
	 */
	V remove(K key, V value) throws Exception;

	/**
	 * Removes all values associated to the specified key.
	 *
	 * @return All found values.
	 */
	Set<V> remove(K key) throws Exception;

	/**
	 * Returns true if this directory contains no key-value mappings.
	 *
	 * @return
	 */
	boolean isEmpty();

	Set<K> keySet();
	Set<Map.Entry<K,V>> entrySet();
	void clear();
	void close();

	/** For compatibility with MultiValueExpiringDirectory. */
	V put(K key, V value, long ttl) throws Exception;
}
