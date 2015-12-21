/*
 * Copyright 2006-2010 National Institute of Advanced Industrial Science
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

package ow.routing.kademlia;

import ow.id.ID;
import ow.id.IDAddressPair;
import ow.id.comparator.AlgoBasedTowardTargetIDAddrComparator;
import ow.routing.RoutingAlgorithmConfiguration;
import ow.routing.RoutingContext;
import ow.routing.RoutingService;
import ow.routing.impl.AbstractRoutingAlgorithm;
import ow.util.HTMLUtil;

import java.io.IOException;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.logging.Level;

/**
 * A RoutingAlgorithm implementing Kademlia, but which uses
 * Hamming distance as its distance metric instead of XOR.
 */
public final class HammingKademlia extends Kademlia {

	protected HammingKademlia(RoutingAlgorithmConfiguration config, RoutingService routingSvc)
			throws InvalidAlgorithmParameterException {
		super(config, routingSvc);
	}

	@Override
	public BigInteger distance(ID to, ID from) {
		BigInteger toInt = to.toBigInteger();
		BigInteger fromInt = from.toBigInteger();

		// Hamming distance
		return BigInteger.valueOf(fromInt.xor(toInt).bitCount());
	}

	public IDAddressPair[] nextHopCandidates(ID targetID, ID lastHop /*ignored*/, boolean joining,
			int maxNum, RoutingContext cxt) {
		final IDAddressPair[] results = new IDAddressPair[maxNum];

		int distance = distance(targetID, selfIDAddress.getID()).intValue();

		Comparator<IDAddressPair> comparator =
			new AlgoBasedTowardTargetIDAddrComparator(this, targetID);

		// pick nodes from k-buckets
		// and fulfill the resulting array with them
		int index = 0;
		KBucket kb;

		if (distance > 0) {	// this node is not the target
			kb = this.kBuckets[distance];
			if (kb != null) {
				index = pickNodes(index, results, kb, comparator);
				if (index >= results.length) return results;		// fulfilled
			}

			for (int i = distance - 1; i >= 0; i--) {
				kb = this.kBuckets[i];
				if (kb != null) {
					index = pickNodes(index, results, kb, comparator);
					if (index >= results.length) return results;	// fulfilled
				}
			}
		}

		if (!joining) {
			results[index++] = selfIDAddress;	// this node itself
			if (index >= results.length) return results;			// fulfilled
		}

		if (distance > 0) {	// this node is not the target
			for (int i = distance; i < this.numKBuckets; i++) {
				kb = this.kBuckets[i];
				if (kb != null) {
					index = pickNodes(index, results, kb, comparator);
					if (index >= results.length) return results;	// fulfilled
				}
			}
		}

		for (int i = distance + 1; i < this.numKBuckets; i++) {
			kb = this.kBuckets[i];
			if (kb != null) {
				index = pickNodes(index, results, kb, comparator);
			}
		}

		// shorten the array
		IDAddressPair[] ret = new IDAddressPair[index];
		System.arraycopy(results, 0, ret, 0, index);

//System.out.println("target: " + targetID);
//for (IDAddressPair r: ret) {
//	if (r == null) break;
//	System.out.println("  " + r + ": " + distance(r.getID(), targetID));
//}

		return ret;
	}

	private int pickNodes(int index, IDAddressPair[] dest, KBucket kb, Comparator<IDAddressPair> comparator) {
		IDAddressPair[] result;
		if (true) {	// performs better
			result = kb.toSortedArray(comparator);
		}
		else {
			result = kb.toArray();
			Arrays.<IDAddressPair>sort(result, comparator);
		}

		int resultLen = result.length;
		int destLen = dest.length;
		int copyLen = Math.min(destLen - index, resultLen);

		System.arraycopy(result, 0, dest, index, copyLen);

		return index + copyLen;
	}


	public void touch(IDAddressPair from) {
		if (from.getID().compareTo(selfIDAddress.getID()) == 0) {
			// from is myself, and ignore
			return;
		}

		int distance = distance(from.getID(), selfIDAddress.getID()).intValue();

		KBucket kb;
		synchronized (this.kBuckets) {
			kb = this.kBuckets[distance];
			if (kb == null) {
				kBuckets[distance] = kb = new KBucket(this);
			}
		}
		kb.appendToTail(from);
	}

	/**
	 * Remove the specified node from k-buckets.
	 */
	public void forget(IDAddressPair failedNode) {

		if (failedNode.getID().compareTo(selfIDAddress.getID()) == 0) {
			// from is myself, and ignore
			return;
		}
		int distance = distance(failedNode.getID(), selfIDAddress.getID()).intValue();

		synchronized (this.kBuckets) {
			KBucket kb = this.kBuckets[distance];
			if (kb != null) {
				kb.remove(failedNode);
				if (kb.size() <= 0) {
					this.kBuckets[distance] = null;
				}
			}
		}
	}
}
