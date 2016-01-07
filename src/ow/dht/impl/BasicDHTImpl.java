/*
 * Copyright 2006-2011 National Institute of Advanced Industrial Science
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

package ow.dht.impl;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.UnknownHostException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import ow.dht.*;
import ow.dht.impl.message.DHTReplyMessage;
import ow.dht.impl.message.GetMessage;
import ow.dht.impl.message.PutMessage;
import ow.dht.impl.message.RemoveMessage;
import ow.dht.impl.message.ReqTransferMessage;
import ow.dht.memcached.Memcached;
import ow.dht.memcached.impl.message.PutOnConditionMessage;
import ow.directory.DirectoryConfiguration;
import ow.directory.DirectoryFactory;
import ow.directory.DirectoryProvider;
import ow.directory.MultiValueAdapterForSingleValueDirectory;
import ow.directory.MultiValueDirectory;
import ow.directory.SingleValueDirectory;
import ow.id.ID;
import ow.id.IDAddressPair;
import ow.id.comparator.AlgoBasedTowardTargetIDComparator;
import ow.messaging.Message;
import ow.messaging.MessageHandler;
import ow.messaging.MessageSender;
import ow.messaging.MessagingAddress;
import ow.messaging.MessagingConfiguration;
import ow.messaging.MessagingFactory;
import ow.messaging.MessagingProvider;
import ow.messaging.Signature;
import ow.routing.*;

/**
 * A basic implementation of DHT service over a routing service.
 */
public class BasicDHTImpl<V extends Serializable> implements DHT<V> {
	final static Logger logger = Logger.getLogger("dht");

	private final static String GLOBAL_DB_NAME = "global";

	private final static String CALLBACK_NAME_GET = "GET";
	private final static String CALLBACK_NAME_GET_SIMILAR = "GET_SIMILAR";

	// members common to higher level services (DHT and Mcast)

	protected DHTConfiguration config;
	private RoutingAlgorithmConfiguration algoConfig;

	protected RoutingService routingSvc;
	protected MessageSender sender;

	protected boolean stopped = false;
	protected boolean suspended = false;

	// members specific to DHT

	protected MultiValueDirectory<ID,ValueInfo<V>> globalDir;

	// members for put operations
	protected ByteArray hashedSecretForPut;
	protected long ttlForPut;

	public BasicDHTImpl(short applicationID, short applicationVersion,
			DHTConfiguration config, ID selfID /* possibly null */)
				throws Exception {
		// obtain messaging service
		byte[] messageSignature = Signature.getSignature(
				RoutingServiceFactory.getRoutingStyleID(config.getRoutingStyle()),
				RoutingAlgorithmFactory.getAlgorithmID(config.getRoutingAlgorithm()),
				applicationID, applicationVersion);

		MessagingProvider msgProvider = MessagingFactory.getProvider(config.getMessagingTransport(), messageSignature);
		if (config.getSelfAddress() != null) {
			msgProvider.setSelfAddress(config.getSelfAddress());
		}

		MessagingConfiguration msgConfig = msgProvider.getDefaultConfiguration();
		msgConfig.setDoUPnPNATTraversal(config.getDoUPnPNATTraversal());

		// obtain routing service
		RoutingAlgorithmProvider algoProvider = RoutingAlgorithmFactory.getProvider(config.getRoutingAlgorithm());
		RoutingAlgorithmConfiguration algoConfig = algoProvider.getDefaultConfiguration();

		RoutingServiceProvider svcProvider = RoutingServiceFactory.getProvider(config.getRoutingStyle());
		RoutingService routingSvc = svcProvider.getService(
				svcProvider.getDefaultConfiguration(),
				msgProvider, msgConfig, config.getSelfPort(), config.getSelfPortRange(),
				algoProvider, algoConfig, selfID);

		config.setSelfPort(routingSvc.getMessageReceiver().getPort());	// correct config

		// instantiate a RoutingAlgorithm in the routing service
		algoProvider.initializeAlgorithmInstance(algoConfig, routingSvc);

		this.init(config, routingSvc);
	}

	public BasicDHTImpl(DHTConfiguration config, RoutingService routingSvc)
			throws Exception {
		this.init(config, routingSvc);
	}

	private void init(DHTConfiguration config, RoutingService routingSvc)
			throws Exception {
		this.config = config;
		this.routingSvc = routingSvc;
		this.sender = routingSvc.getMessageSender();
		this.algoConfig = routingSvc.getRoutingAlgorithm().getConfiguration();

		this.hashedSecretForPut = null;
		this.ttlForPut = config.getDefaultTTL();

		// prepare working directory
		File workingDirFile = new File(config.getWorkingDirectory());
		workingDirFile.mkdirs();

		// initialize directories
		DirectoryProvider dirProvider = DirectoryFactory.getProvider(config.getDirectoryType());
		DirectoryConfiguration dirConfig = DirectoryConfiguration.getDefaultConfiguration();

		if (config.getDoExpire())
			dirConfig.setExpirationTime(config.getDefaultTTL());
		else
			dirConfig.setExpirationTime(-1L);

		if (config.getMultipleValuesForASingleKey()) {
			this.globalDir = dirProvider.openMultiValueDirectory(
					ID.class, config.getValueClass(), config.getWorkingDirectory(), GLOBAL_DB_NAME,
					dirConfig);
		}
		else {
			SingleValueDirectory<ID,ValueInfo<V>> singleValueDir =
					dirProvider.openSingleValueDirectory(
							ID.class, config.getValueClass(), config.getWorkingDirectory(), GLOBAL_DB_NAME,
							dirConfig);

			this.globalDir = new MultiValueAdapterForSingleValueDirectory<ID, ValueInfo<V>>(singleValueDir);
		}

		// initialize message handlers and callbacks
		prepareHandlers(this.routingSvc);
		prepareCallbacks(this.routingSvc);
	}

	public MessagingAddress joinOverlay(String hostAndPort, int defaultPort)
			throws UnknownHostException, RoutingException {
		MessagingAddress addr = this.routingSvc.getMessagingProvider().getMessagingAddress(hostAndPort, defaultPort);
		this.joinOverlay(addr);

		return addr;
	}

	public MessagingAddress joinOverlay(String hostAndPort)
			throws UnknownHostException, RoutingException {
		MessagingAddress addr = this.routingSvc.getMessagingProvider().getMessagingAddress(hostAndPort);
		this.joinOverlay(addr);

		return addr;
	}

	private void joinOverlay(MessagingAddress addr)
			throws RoutingException {
		logger.log(Level.INFO, "DHTImpl#joinOverlay: " + addr);

		IDAddressPair selfIDAddress = this.getSelfIDAddressPair();
		RoutingResult routingRes = this.routingSvc.join(addr);	// throws RoutingException

		this.lastKeys = new ID[1];
		this.lastKeys[0] = selfIDAddress.getID();
		this.lastRoutingResults = new RoutingResult[1];
		this.lastRoutingResults[0] = routingRes;

		// value transfer
		int nodeCount = config.getNumNodesAskedToTransfer();
		IDAddressPair[] respCands = routingRes.getResponsibleNodeCandidates();

		if (nodeCount > 0 && respCands != null) {
			int i = 0;

			while (nodeCount > 0 && i < respCands.length) {
				IDAddressPair transferringNode = respCands[i++];
				if (this.getSelfIDAddressPair().equals(transferringNode)) continue;

				Message request = new ReqTransferMessage();
				try {
					sender.send(transferringNode.getAddress(), request);
				}
				catch (IOException e) {
					logger.log(Level.WARNING, "failed to send: " + transferringNode.getAddress());
				}

				nodeCount--;
			}
		}
	}

	public void clearRoutingTable() {
		this.routingSvc.leave();

		this.lastKeys = null;
		this.lastRoutingResults = null;
	}

	public void clearDHTState() {
		synchronized (this.globalDir) {
			globalDir.clear();
		}
	}

	public Set<ValueInfo<V>> get(ID key)
			throws RoutingException {
		ID[] keys = { key };

		Set<ValueInfo<V>>[] results = new Set/*<ValueInfo<V>>*/[keys.length];

		RoutingResult[] routingRes = this.getRemotely(keys, results);

		if (routingRes[0] == null) throw new RoutingException();

		return results[0];
	}


	public Map<ID, Set<ValueInfo<V>>> getSimilar(ID key, float threshold)
			throws RoutingException {
		return getSimilar(key, threshold, config.getSimilarSearchExtraHopCount());
	}

	public Map<ID, Set<ValueInfo<V>>> getSimilar(ID key, float threshold, int extraHops)
		throws RoutingException {
		ID[] keys = { key };
		Float[] thresholds = { threshold };
		Map<ID, Set<ValueInfo<V>>>[] results = new Map[keys.length];
		RoutingResult[] routingRes = this.getSimilarRemotely(keys, thresholds, results, extraHops);

		if (routingRes[0] == null) {
			throw new RoutingException();
		}

		return results[0];
	}

	public Set<ValueInfo<V>>[] get(ID[] keys) {
		Set<ValueInfo<V>>[] results = new Set/*<ValueInfo<V>>*/[keys.length];

		return results;
	}

	protected RoutingResult[] getRemotely(ID[] keys, Set<ValueInfo<V>>[] results) {
		Serializable[][] args = new Serializable[keys.length][2];
		for (int i = 0; i < keys.length; i++) {
			args[i][0] = CALLBACK_NAME_GET;
			args[i][1] = keys[i];
		}

		Serializable[][] callbackResultContainer = new Serializable[keys.length][1];

		// get from the responsible node by RoutingService#invokeCallbacksOnRoute()
		RoutingResult[] routingRes = this.routingSvc.invokeCallbacksOnRoute(
				keys, config.getNumTimesGets() + config.getNumSpareResponsibleNodeCandidates(),
				callbackResultContainer, -1, args);

		this.preserveRoute(keys, routingRes);

		for (int i = 0; i < keys.length; i++) {
			if (routingRes[i] == null) continue;

			if (callbackResultContainer[i] != null)
				results[i] = (Set<ValueInfo<V>>)callbackResultContainer[i][0];

			if (results[i] == null) results[i] = new HashSet<ValueInfo<V>>();
			// routing succeeded and results[i] should not be null.
		}

		return routingRes;
	}

	protected RoutingResult[] getSimilarRemotely(ID[] keys,
																							 Float[] thresholds,
																							 Map<ID, Set<ValueInfo<V>>>[] results,
																							 int extraHops) {
		if (!config.getSearchKeysForSimilarity()) {
			// fallback to getRemotely()
			Set<ValueInfo<V>>[] exactResults = new Set[keys.length];
			RoutingResult[] routingRes = getRemotely(keys, exactResults);
			for (int i = 0; i < keys.length; i++) {
				if (exactResults[i] == null) {
					results[i] = new TreeMap<>();
				} else {
					TreeMap<ID, Set<ValueInfo<V>>> sorted = new TreeMap<>();
					sorted.put(keys[i], exactResults[i]);
					results[i] = sorted;
				}
			}
			return routingRes;
		}

		Serializable[][] args = new Serializable[keys.length][3];
		for (int i = 0; i < keys.length; i++) {
			args[i][0] = CALLBACK_NAME_GET_SIMILAR;
			args[i][1] = keys[i];
			args[i][2] = thresholds[i];
		}

		Serializable[][] callbackResultContainer = new Serializable[keys.length][1];

		// get from the responsible node by RoutingService#invokeCallbacksOnRoute()
		RoutingResult[] routingRes = this.routingSvc.invokeCallbacksOnRoute(
				keys, config.getNumTimesGets() + config.getNumSpareResponsibleNodeCandidates(),
				callbackResultContainer, -1, args);

		this.preserveRoute(keys, routingRes);

		for (int i = 0; i < keys.length; i++) {
			if (routingRes[i] == null) continue;

			if (callbackResultContainer[i] != null)
				results[i] = (Map<ID, Set<ValueInfo<V>>>)callbackResultContainer[i][0];
		}

		// If there are no spare candidates, we can't query the neighbors of the responsible nodes
		if (config.getNumSpareResponsibleNodeCandidates() < 1) {
			return routingRes;
		}

		// To retrieve more similar results, query the neighbors of the nodes that returned
		// values, up to the max number of hops specified in `extraHops`

		ID[] targets = new ID[keys.length];
		RoutingResult[] lastHopResults = routingRes;

		RoutingAlgorithm algo = this.getRoutingService().getRoutingAlgorithm();

		TreeSet<ID>[] closestNodes = new TreeSet[keys.length];
		Set<ID>[] alreadyContacted = new Set[keys.length];
		for (int i = 0; i < keys.length; i++) {
			closestNodes[i] = new TreeSet<>();
			alreadyContacted[i] = new HashSet<>();
		}

		for (int hops = 0; hops < extraHops; hops++) {
			for (int i = 0; i < keys.length; i++) {
				if (lastHopResults[i] == null) {
					targets[i] = keys[i];
					continue;
				}

				IDAddressPair[] candidates = lastHopResults[i].getResponsibleNodeCandidates();
				RoutingHop[] route = lastHopResults[i].getRoute();
				// the first candidate is the node that returned the result, followed by its neighbors,
				// sorted by proximity to the target key.
				alreadyContacted[i].add(route[route.length - 1].getIDAddressPair().getID());
				for (IDAddressPair node : candidates) {
					closestNodes[i].add(node.getID());
				}

				closestNodes[i].removeAll(alreadyContacted[i]);
				closestNodes[i].remove(getSelfIDAddressPair().getID());
				if (closestNodes[i].isEmpty()) {
					targets[i] = keys[i];
					continue;
				}

				targets[i] = closestNodes[i].first();
			}

			// targets now contains the IDs of the nodes nearest to the responsible nodes from last round
			// so we query those nodes using the same args
			logger.warning("Hop #" + hops + " contacting " + targets);
			lastHopResults = this.routingSvc.invokeCallbacksOnRoute(
					targets, config.getNumTimesGets() + config.getNumSpareResponsibleNodeCandidates(),
					callbackResultContainer, -1, args);

			// merge results from this round with those from previous hops
			for (int i = 0; i < keys.length; i++) {
				if (lastHopResults[i] == null || callbackResultContainer[i] == null) {
					continue;
				}

				Map<ID, Set<ValueInfo<V>>> accumulatedResults = results[i];
				Map<ID, Set<ValueInfo<V>>> hopResult =
						(Map<ID, Set<ValueInfo<V>>>)callbackResultContainer[i][0];

				// For each entry in the new map, merge into the accumulatedResults.
				// If the same ID key exists in both, merge both sets of values
				for (Map.Entry<ID, Set<ValueInfo<V>>> entry : hopResult.entrySet()) {
					accumulatedResults.merge(entry.getKey(), entry.getValue(),
							(Set<ValueInfo<V>> prevVals, Set<ValueInfo<V>> newVals) -> {
								prevVals.addAll(newVals);
								return prevVals;
							});
				}
				results[i] = accumulatedResults;
			}
		}

		return routingRes;
	}

	public Set<ValueInfo<V>> put(ID key, V value) throws IOException {
		V[] values = (V[])new Serializable[1];
		values[0] = value;

		return this.put(key, values);
	}

	public Set<ValueInfo<V>> put(ID key, V[] values) throws IOException {
		DHT.PutRequest<V>[] req = new DHT.PutRequest/*<V>*/[1];
		req[0] = new DHT.PutRequest<V>(key, values);

		Set<ValueInfo<V>>[] ret =
			this.putOrRemoveRemotely(req, false, this.ttlForPut, this.hashedSecretForPut, true);

		if (req[0] == null) throw new RoutingException();

		return ret[0];
	}

	public Set<ValueInfo<V>>[] put(DHT.PutRequest<V>[] requests) throws IOException {
		return putOrRemoveRemotely(requests, false, this.ttlForPut, this.hashedSecretForPut, true);
	}

	public Set<ValueInfo<V>> remove(ID key, V[] values, ByteArray hashedSecret)
			throws RoutingException {
		return remove(key, values, null, hashedSecret);
	}
	public Set<ValueInfo<V>> remove(ID key, ID[] valueHash, ByteArray hashedSecret)
			throws RoutingException {
		return remove(key, null, valueHash, hashedSecret);
	}
	private Set<ValueInfo<V>> remove(
			ID key, V[] values, ID[] valueHash, ByteArray hashedSecret)
				throws RoutingException {
		DHT.RemoveRequest<V>[] req = new DHT.RemoveRequest/*<V>*/[1];
		if (values != null)
			req[0] = new DHT.RemoveRequest<V>(key, values);
		else 
			req[0] = new DHT.RemoveRequest<V>(key, valueHash);

		Set<ValueInfo<V>>[] ret = this.remove(req, hashedSecret);

		if (req[0] == null) throw new RoutingException();

		return ret[0];
	}

	public Set<ValueInfo<V>> remove(ID key, ByteArray hashedSecret)
			throws RoutingException {
		DHT.RemoveRequest<V>[] req = new DHT.RemoveRequest/*<V>*/[1];
		req[0] = new DHT.RemoveRequest<V>(key);

		Set<ValueInfo<V>>[] ret = this.remove(req, hashedSecret);

		if (req[0] == null) throw new RoutingException();

		return ret[0];
	}

	public Set<ValueInfo<V>>[] remove(DHT.RemoveRequest<V>[] requests, ByteArray hashedSecret) {
		return putOrRemoveRemotely(requests, true, 0, hashedSecret, true);
	}

	// wrapper to supplement arguments
	private Set<ValueInfo<V>>[] putOrRemoveRemotely(DHT.PutRequest<V>[] requests,
			boolean doesRemove, long ttl, ByteArray hashedSecret, boolean toPreserveRoute) {
		return this.putOrRemoveRemotely(requests, doesRemove, ttl, hashedSecret, toPreserveRoute,
				1, 1, false);	// for replication (ChurnTolerantDHTImpl)
	}

	// Note:
	// This putOrRemoveRemotely method supports ChurnTolerantDHTImpl and MemcachedImpl.
	// Such supports should be separated from this BasicDHTImpl class.
	protected Set<ValueInfo<V>>[] putOrRemoveRemotely(DHT.PutRequest<V>[] requests,
			boolean doesRemove, long ttl, ByteArray hashedSecret, boolean toPreserveRoute,
			/* for replication: */ int numReplica, int repeat, boolean excludeSelf) {
		// for memcached
		boolean withCondition = (requests[0] instanceof Memcached.PutRequest);

		Set<ValueInfo<V>>[] results = new Set/*<ValueInfo<V>>*/[requests.length];

		int numRespCands = repeat + config.getNumSpareResponsibleNodeCandidates();

		ID[] keys = new ID[requests.length];
		for (int i = 0; i < requests.length; i++) {
			keys[i] = requests[i].getKey();
		}

		RoutingResult[] routingRes =
			this.routingSvc.route(keys, numRespCands);

		Queue<IDAddressPair>[] respCands = new Queue/*<IDAddressPair>*/[requests.length];
		for (int i = 0; i < requests.length; i++) {
			if (routingRes[i] != null) {
				respCands[i] = new LinkedList<IDAddressPair>();
				for (IDAddressPair p: routingRes[i].getResponsibleNodeCandidates()) {
					respCands[i].offer(p);
				}
			}
		}

		int[] succeed = new int[requests.length];

		retryPut:
		while (true) {
			Set<MessagingAddress> targetSet = new HashSet<MessagingAddress>();
			for (int i = 0; i < respCands.length; i++) {
				if (respCands[i] == null) {
					continue;
				}

				IDAddressPair p = null;

				do {
					p = respCands[i].peek();
					if (excludeSelf && getSelfIDAddressPair().equals(p)) {
						respCands[i].poll();
						continue;
					}

					break;
				} while (true);

				if (p != null) {
					targetSet.add(p.getAddress());
				}
			}

			if (targetSet.isEmpty()) break;

			for (MessagingAddress target: targetSet) {
				List<Integer> indexList = new ArrayList<Integer>();
				for (int i = 0; i < respCands.length; i++) {
					if (respCands[i] != null) {
						IDAddressPair p = respCands[i].peek();
						if (p != null && target.equals(p.getAddress())) {
							respCands[i].poll();
							indexList.add(i);
						}
					}
				}

				int size= indexList.size();
				DHT.PutRequest<V>[] packedRequests =
					(doesRemove ? new DHT.RemoveRequest/*<V>*/[size] : new DHT.PutRequest/*<V>*/[size]);
				for (int i = 0; i < size; i++) {
					packedRequests[i] = requests[indexList.get(i)];
				}

				Message request = null;
				if (!doesRemove) {
					if (!withCondition) {
						request = new PutMessage<V>(
								packedRequests, ttl, hashedSecret, numReplica);
					}
					else {	// for memcached
						request = new PutOnConditionMessage(
								(Memcached.PutRequest[])packedRequests, ttl, hashedSecret, numReplica);
					}
				}
				else {
					request = new RemoveMessage<V>(
							(DHT.RemoveRequest<V>[])requests, hashedSecret, numReplica);
				}

				Message reply;
				try {
					reply = this.requestPutOrRemove(target, request);
				}
				catch (IOException e) {
					continue retryPut;
				}

				Set<ValueInfo<V>>[] existedValues = ((DHTReplyMessage<V>)reply).existedValues;
				if (existedValues != null) {
					for (int i = 0; i < indexList.size(); i++) {
						results[indexList.get(i)] = existedValues[i];
					}
				}

				for (int index: indexList) {
					if (++succeed[index] >= repeat) {
						respCands[index] = null;
					}
				}
			}	// for (MessagingAddress target: targetSet)
		}	// while (true)

		// null in requests indicates that routing failure
		for (int i = 0; i < requests.length; i++) {
			if (routingRes[i] == null) requests[i] = null;
		}

		// null in results indicates that not put request succeeded
		for (int i = 0; i < requests.length; i++) {
			if (succeed[i] == 0) {	// can be succeed[i] < repeat
				routingRes[i] = null;
				results[i] = null;
			}
		}

		if (toPreserveRoute) this.preserveRoute(keys, routingRes);

		return results;
	}

	private Message requestPutOrRemove(MessagingAddress target, Message request)
			throws IOException {
		Message reply = null;

		try {
			reply = sender.sendAndReceive(target, request);
				// throws IOException

			if (reply instanceof DHTReplyMessage) {
				logger.log(Level.INFO, "put/remove succeeded on " + target);
			}
			else {
				reply = null;
			}
		}
		catch (IOException e) {
			logger.log(Level.WARNING, "Failed to send a put/remove message to " + target, e);
			throw e;
		}

		return reply;
	}

	/**
	 * Saves the last keys and routes.
	 */
	private void preserveRoute(ID[] keys, RoutingResult[] routingRes) {
		this.lastKeys = keys;
		this.lastRoutingResults = routingRes;
	}

	public ByteArray setHashedSecretForPut(ByteArray hashedSecret) {
		ByteArray old = this.hashedSecretForPut;
		this.hashedSecretForPut = hashedSecret;
		return old;
	}

	public long setTTLForPut(long ttl) {
		long old = this.ttlForPut;
		this.ttlForPut = ttl;
		return old;
	}

	public synchronized void stop() {
		logger.log(Level.INFO, "DHT#stop() called.");

		this.stopped = true;

		// stop routing service
		if (this.routingSvc != null) {
			this.routingSvc.stop();
			this.routingSvc = null;
		}

		// close directories
		if (this.globalDir != null) {
			this.globalDir.close();
			this.globalDir = null;
		}
	}

	public synchronized void suspend() {
		// suspend sub-services
		this.routingSvc.suspend();

		this.suspended = true;
	}

	public synchronized void resume() {
		this.suspended = false;

		// resume sub-services
		this.routingSvc.resume();
	}

	//
	// methods specific to DHT
	//

	public Set<ID> getLocalKeys() {
		return null;
	}
	public Set<ValueInfo<V>> getLocalValues(ID key) {
		return null;
	}
	public Set<ID> getGlobalKeys() {
		return this.globalDir.keySet();
	}
	public Set<ValueInfo<V>> getGlobalValues(ID key) {
		Set<ValueInfo<V>> ret = null;

		try {
			ret = globalDir.get(key);
		}
		catch (Exception e) {
			logger.log(Level.WARNING, "An Exception thrown when retrieve from the globalDir.", e);
		}

		return ret;
	}

	//
	// methods common to DHT and Mcast
	//

	public RoutingService getRoutingService() {
		return this.routingSvc;
	}

	public DHTConfiguration getConfiguration() {
		return this.config;
	}

	public RoutingAlgorithmConfiguration getRoutingAlgorithmConfiguration() {
		return this.algoConfig;
	}

	public IDAddressPair getSelfIDAddressPair() {
		return this.routingSvc.getSelfIDAddressPair();
	}

	public void setStatCollectorAddress(String host, int port) throws UnknownHostException {
		MessagingAddress addr = this.routingSvc.getMessagingProvider().getMessagingAddress(host, port);

		this.routingSvc.setStatCollectorAddress(addr);
	}

	private ID[] lastKeys = null;
	public ID[] getLastKeys() { return this.lastKeys; }

	private RoutingResult[] lastRoutingResults = null;
	public RoutingResult[] getLastRoutingResults() { return this.lastRoutingResults; }

	public String getRoutingTableString(int verboseLevel) {
		return this.routingSvc.getRoutingAlgorithm().getRoutingTableString(verboseLevel);
	}

	protected void prepareHandlers(RoutingService routingSvc) {
		this.prepareHandlers0(routingSvc);

		MessageHandler handler;

		handler = new PutMessageHandler();
		routingSvc.addMessageHandler(PutMessage.class, handler);

		handler = new RemoveMessageHandler();
		routingSvc.addMessageHandler(RemoveMessage.class, handler);
	}

	protected void prepareHandlers0(RoutingService routingSvc) {
		MessageHandler handler;

		handler = new MessageHandler() {
			public Message process(Message msg) {
				// get locally
				ID[] keys = ((GetMessage)msg).keys;
				Set<ValueInfo<V>>[] valueSets = new Set/*<ValueInfo<V>>*/[keys.length];

				for (int i = 0; i < keys.length; i++) {
					valueSets[i] = getValueLocally(keys[i], globalDir);
				}

				return new DHTReplyMessage<V>(valueSets);
			}
		};
		routingSvc.addMessageHandler(GetMessage.class, handler);
	}

	protected class PutMessageHandler implements MessageHandler {
		public Message process(Message msg) {
//System.out.println(getSelfIDAddressPair().getAddress() + ": " + Tag.getNameByNumber(msg.getTag())+ " from " + msg.getSource());
			final DHT.PutRequest<V>[] requests = ((PutMessage<V>)msg).requests;
			long ttl = ((PutMessage<V>)msg).ttl;
			final ByteArray hashedSecret = ((PutMessage<V>)msg).hashedSecret;

			logger.log(Level.INFO, "A PUT message received"
					+ (requests[0] == null ? "(null)" : requests[0].getKey()));

			if (ttl > config.getMaximumTTL())
				ttl = config.getMaximumTTL(); 
			else if (ttl <= 0L)
				ttl = 0L;

			// put locally
			Set<ValueInfo<V>>[] ret = new Set/*<ValueInfo<V>>*/[requests.length];

			try {
				ValueInfo.Attributes attr = new ValueInfo.Attributes(ttl, hashedSecret);

				for (int i = 0; i < requests.length; i++) {
//System.out.println("  key[" + i + "]: " + requests[i].getKey());
					if (requests[i] == null) continue;

					ret[i] = new HashSet<ValueInfo<V>>();

					for (V v: requests[i].getValues()) {
//System.out.println("  value: " + v);
						if (v != null) {
							ValueInfo<V> old = null;
							synchronized (globalDir) {
								old = globalDir.put(requests[i].getKey(), new ValueInfo<V>(v, attr), ttl);
							}

							if (old != null) {
								ret[i].add(old);
							}
						}
					}
				}
			}
			catch (Exception e) {
				// NOTREACHED
				logger.log(Level.WARNING, "An Exception thrown by Directory#put().", e);
			}

			return new DHTReplyMessage<V>(ret);
		}
	}

	protected class RemoveMessageHandler implements MessageHandler {
		public Message process(Message msg) {
			DHT.RemoveRequest<V>[] requests = ((RemoveMessage<V>)msg).requests;
			ByteArray hashedSecret = ((RemoveMessage<V>)msg).hashedSecret;

			logger.log(Level.INFO, "A REMOVE message received"
					+ (requests[0] == null ? "(null)" : requests[0].getKey()));

			// remove locally from global directory
			Set<ValueInfo<V>>[] ret = new Set/*<ValueInfo<V>>*/[requests.length];

			if (hashedSecret == null) {
				logger.log(Level.WARNING, "A REMOVE request with no secret.");
				return null;
			}

			for (int i = 0; i < requests.length; i++) {
				if (requests[i] == null) continue;

				ID key = requests[i].getKey();
				V[] values = requests[i].getValues();
				ID[] valueHash = requests[i].getValueHash();

				try {
					if (values == null) {
						ret[i] = globalDir.get(requests[i].getKey());

						if (ret[i] != null) {
							ValueInfo<V>[] existedValueArray = new ValueInfo/*<V>*/[ret[i].size()];
							ret[i].toArray(existedValueArray);

							ret[i] = new HashSet<ValueInfo<V>>();

							for (ValueInfo<V> v: existedValueArray) {
								ID h = ID.getSHA1BasedID(
										v.getValue().toString().getBytes(config.getValueEncoding()));

								if (hashedSecret.equals(v.getHashedSecret())) {
									boolean remove = false;

									if (valueHash == null)
										remove = true;
									else {
										for (ID valH: valueHash) {
											if (h.equals(valH)) remove = true;
										}
									}

									if (remove) {
										synchronized (globalDir) {
											globalDir.remove(key, v);
										}

										ret[i].add(v);
									}
								}
							}
						}
					}
					else {
						ret[i] = new HashSet<ValueInfo<V>>();

						synchronized (globalDir) {
							if (values != null) {
								for (V val: values) {
									ValueInfo<V> v =
										globalDir.remove(key, new ValueInfo<V>(val, 0, hashedSecret));
									if (v != null) ret[i].add(v);
								}
							}
						}
					}
				}
				catch (Exception e) {
					// NOTREACHED
					logger.log(Level.WARNING, "An Exception thrown by Directory#remove().", e);
				}
			}	// for (int i = 0; i < requests.length; i++)

			return new DHTReplyMessage<V>(ret);
		}
	}

	private void prepareCallbacks(RoutingService routingSvc) {
		routingSvc.addCallbackOnRoute(new CallbackOnRoute() {
			public Serializable process(ID target, int tag, Serializable[] args, IDAddressPair lastHop, boolean onResponsibleNode) {
				String callbackName = (String)args[0];
				ID key = (ID)args[1];

				logger.log(Level.INFO, "A callback invoked: " + callbackName + ": " + key);


				if (callbackName.equals(CALLBACK_NAME_GET)) {
					return (Serializable) getValueLocally(key, globalDir);
				} else if (callbackName.equals(CALLBACK_NAME_GET_SIMILAR)) {
					float threshold = (Float)args[2];
					return (Serializable) getSimilarValuesLocally(key, threshold, globalDir);
				}

				logger.log(Level.WARNING, "Unknown callback name " + callbackName);
				return new HashSet();
			}
		});
	}

	protected Set<ValueInfo<V>> getValueLocally(ID key, MultiValueDirectory<ID,ValueInfo<V>> dir) {
		Set<ValueInfo<V>> returnedValues = null;

		try {
			returnedValues = dir.get(key);
		}
		catch (Exception e) {
			// NOTREACHED
			logger.log(Level.WARNING, "An Exception thrown by Directory#get().", e);
			return null;
		}

		return returnedValues;
	}

	protected Map<ID, Set<ValueInfo<V>>>
		getSimilarValuesLocally(ID key, float threshold, MultiValueDirectory<ID, ValueInfo<V>> dir) {

		try {
			Map<ID, Set<ValueInfo<V>>> result = dir.getSimilar(key, threshold);
			return result;
		} catch (Exception e) {
			logger.log(Level.WARNING, "An Exception thrown by Directory#getSimilar().", e);
			return null;
		}

	}
}
