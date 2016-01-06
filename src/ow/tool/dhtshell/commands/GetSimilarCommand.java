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

package ow.tool.dhtshell.commands;

import ow.dht.ByteArray;
import ow.dht.DHT;
import ow.dht.ValueInfo;
import ow.directory.comparator.HammingIDComparator;
import ow.id.ID;
import ow.routing.RoutingException;
import ow.tool.util.shellframework.Command;
import ow.tool.util.shellframework.CommandUtil;
import ow.tool.util.shellframework.Shell;
import ow.tool.util.shellframework.ShellContext;

import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class GetSimilarCommand implements Command<DHT<String>> {
	private final static String[] NAMES = {"get-similar"};

	public String[] getNames() { return NAMES; }

	public String getHelp() {
		return "get-similar [-status] [-hops <num-hops>] <key> <threshold> [<key> <threshold> ...]";
	}

	public boolean execute(ShellContext<DHT<String>> context) {
		DHT<String> dht = context.getOpaqueData();
		PrintStream out = context.getOutputStream();
		String[] args = context.getArguments();
		boolean showStatus = false;
		int extraHops = -1;
		int argIndex = 0;

		if (argIndex < args.length && args[argIndex].equals("-status")) {
			showStatus = true;
			argIndex++;
		}

		if (argIndex < args.length - 1 && args[argIndex].equals("-hops")) {
			extraHops = Integer.parseInt(args[++argIndex]);
			argIndex++;
		}

		int remainingArgs = args.length - argIndex;

		if (remainingArgs < 2 || (remainingArgs % 2) != 0) {
			out.print("usage: " + getHelp() + Shell.CRLF);
			out.flush();

			return false;
		}

		Queue<Map.Entry<ID,Float>> requestQueue = new ConcurrentLinkedQueue<>();

		// parse the command line and queue get requests
		List<String> keyList = new ArrayList<>();
		List<String> thresholdList = new ArrayList<>();

		for (; argIndex < args.length; argIndex += 2) {
			String keyStr = args[argIndex];
			String thresholdStr = args[argIndex + 1];

			ID key = ID.parseID(keyStr, dht.getRoutingAlgorithmConfiguration().getIDSizeInByte());
			keyList.add(keyStr);


			Float threshold = Float.parseFloat(thresholdStr);
			thresholdList.add(thresholdStr);

			Map.Entry<ID, Float> pair = new AbstractMap.SimpleImmutableEntry<>(key, threshold);
			requestQueue.offer(pair);
		}

		// process get requests
		ID[] keys = new ID[requestQueue.size()];
		Float[] thresholds = new Float[requestQueue.size()];
		Map<ID, Set<ValueInfo<String>>>[] results = new Map[requestQueue.size()];

		for (int i = 0; i < keys.length; i++) {
			Map.Entry<ID, Float> pair = requestQueue.poll();
			keys[i] = pair.getKey();
			thresholds[i] = pair.getValue();

			try {
				if (extraHops >= 0) {
					results[i] = dht.getSimilar(keys[i], thresholds[i], extraHops);
				} else {
					results[i] = dht.getSimilar(keys[i], thresholds[i]);
				}
			} catch (RoutingException e) {
				results[i] = null;
			}

		}

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < keys.length; i++) {
			ID searchKey = keys[i];
			String threshold = thresholdList.get(i);

			sb.append("search key: ").append(searchKey).append(Shell.CRLF);
			sb.append("threshold:  ").append(threshold).append(Shell.CRLF);
			sb.append("results: ").append(Shell.CRLF);

			if (results[i] == null) {
				sb.append("routing failed: ").append(keyList.get(i)).append(Shell.CRLF);
				continue;
			}

			if (results[i].isEmpty()) {
				sb.append("no values returned").append(Shell.CRLF);
				continue;
			}


			for (Map.Entry<ID, Set<ValueInfo<String>>> pair : results[i].entrySet()) {
				ID key = pair.getKey();
				Set<ValueInfo<String>> values = pair.getValue();

				sb.append("content key: ").append(key.toString()).append(Shell.CRLF);

				for (ValueInfo<String> v : values) {
					sb.append("value:       ").append(v.getValue()).append(" ").append(v.getTTL() / 1000);

					ByteArray secret = v.getHashedSecret();
					if (secret != null) {
						sb.append(" ").append(secret);
					}

					sb.append(Shell.CRLF);
				}
			}

		}

		if (showStatus) {
			sb.append(CommandUtil.buildStatusMessage(context.getOpaqueData(), -1));
		}

		out.print(sb);
		out.flush();

		return false;
	}
}
