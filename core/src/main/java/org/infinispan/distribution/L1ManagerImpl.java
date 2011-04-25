/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.distribution;

import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.infinispan.commands.CommandsFactory;
import org.infinispan.commands.write.InvalidateCommand;
import org.infinispan.config.Configuration;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.util.concurrent.AggregatingNotifyingFutureImpl;
import org.infinispan.util.concurrent.ConcurrentHashSet;
import org.infinispan.util.concurrent.NotifyingNotifiableFuture;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

public class L1ManagerImpl implements L1Manager {
	
	private final Log log = LogFactory.getLog(L1ManagerImpl.class);
	private final boolean trace = log.isTraceEnabled();
	
	private RpcManager rpcManager;
	private CommandsFactory commandsFactory;
	private int threshold;

	private volatile ConcurrentMap<Object, Collection<Address>> requestors;
	
	public L1ManagerImpl() {
	   requestors = new ConcurrentHashMap<Object, Collection<Address>>();
   }
	
   @Inject
   public void init(Configuration configuration, RpcManager rpcManager, CommandsFactory commandsFactory) {
   	this.rpcManager = rpcManager;
   	this.commandsFactory = commandsFactory;
   	this.threshold = configuration.getL1InvalidationThreshold();
   }
   
   public void addRequestor(Object key, Address origin) {
      
      // Classic DCL - first check if we have HashSet in the map, if outside a lock we have it, we can use it
      Collection<Address> as = requestors.get(key);
   	
      if (as == null) {
        synchronized (requestors) {
           as = requestors.get(key);
           // Check again, if we now have it, skip to using it
           if (as == null) {
              // Otherwise add the hash set and key
              as = new ConcurrentHashSet<Address>();
              requestors.put(key, as);
           }
        }
      }
      // Finally, add the value to the hashset
      as.add(origin);
      
      // If the collection of addresses got removed underneath us, then the correct thing to do is add a new collection with the address in
      // This is an edge case, so synchronisation is fine
      if (requestors.get(key) != as) {
         synchronized (requestors) {
            if (requestors.containsKey(key)) {
               requestors.get(key).add(origin);
            } else {
               as = new ConcurrentHashSet<Address>();
               as.add(origin);
               requestors.put(key, as);
            }
         }
      }
   }
   
   public NotifyingNotifiableFuture<Object> flushCache(Collection<Object> keys, Object retval, Address origin) {
      if (trace) log.trace("Invalidating L1 caches for keys %s", keys);
      
      NotifyingNotifiableFuture<Object> future = new AggregatingNotifyingFutureImpl(retval, 2);
      
      Collection<Address> invalidationAddresses = buildInvalidationAddressList(keys, origin);
      
      int nodes = invalidationAddresses.size();
      
      if (nodes > 0) {
         // No need to invalidate at all if there is no one to invalidate!
         boolean multicast = isUseMulticast(nodes);
         
         if (trace) log.trace("There are %s nodes involved in invalidation. Threshold is: %s; using multicast: %s", nodes, threshold, multicast);
         
         if (multicast) {
         	if (trace) log.trace("Invalidating keys %s via multicast", keys);
         	InvalidateCommand ic = commandsFactory.buildInvalidateFromL1Command(false, keys);
         	try {
         		rpcManager.broadcastRpcCommandInFuture(ic, future);
         	} finally {
         		cleanupRequestors(keys);
         	}
         } else {
            try {
            	InvalidateCommand ic = commandsFactory.buildInvalidateFromL1Command(false, keys);
            	
               // Ask the caches who have requested from us to remove
               if (trace) log.trace("Keys %s needs invalidation on %s", keys, invalidationAddresses);
               rpcManager.invokeRemotelyInFuture(invalidationAddresses, ic, future);
               return future;
            } finally {
            	cleanupRequestors(keys);
            }
         }    
      } else
         if (trace) log.trace("No L1 caches to invalidate");
      return future;
   }
   
   private void cleanupRequestors(Collection<Object> keys) {
   	for (Object key : keys) {
   		requestors.remove(key);
   	}
   }
   
   private Collection<Address> buildInvalidationAddressList(Collection<Object> keys, Address origin) {
   	Collection<Address> addresses = new HashSet<Address>();
   	
   	for (Object key : keys) {
   	   Collection<Address> as = requestors.get(key);
   	   if (as != null)
   		   addresses.addAll(as);
   	}
   	if (origin != null)
   		addresses.remove(origin);
   	return addresses;
   }
   
   private boolean isUseMulticast(int nodes) {
   	// User has requested unicast or multicast only
   	if (threshold == -1) return false;
   	if (threshold == 0) return true;
   	// Underlying transport is not multicast capable
   	if (!rpcManager.getTransport().isMulticastCapable()) return false;
   	return nodes > threshold;
   }

}