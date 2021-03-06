/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010 Red Hat Inc. and/or its affiliates and other
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
package org.infinispan.container;

import net.jcip.annotations.ThreadSafe;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.container.entries.InternalEntryFactory;
import org.infinispan.eviction.EvictionManager;
import org.infinispan.eviction.EvictionStrategy;
import org.infinispan.eviction.EvictionThreadPolicy;
import org.infinispan.eviction.PassivationManager;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.util.Immutables;
import org.infinispan.util.concurrent.BoundedConcurrentHashMap;
import org.infinispan.util.concurrent.BoundedConcurrentHashMap.Eviction;
import org.infinispan.util.concurrent.BoundedConcurrentHashMap.EvictionListener;

import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * DefaultDataContainer is both eviction and non-eviction based data container.
 *
 *
 * @author Manik Surtani
 * @author Galder Zamarreño
 * @author Vladimir Blagojevic
 * @author <a href="http://gleamynode.net/">Trustin Lee</a>
 *
 * @since 4.0
 */
@ThreadSafe
public class DefaultDataContainer implements DataContainer {

   final ConcurrentMap<Object, InternalCacheEntry> entries;
   final InternalEntryFactory entryFactory;
   final DefaultEvictionListener evictionListener;
   private EvictionManager evictionManager;
   private PassivationManager passivator;

   protected DefaultDataContainer(int concurrencyLevel) {
      entries = new ConcurrentHashMap<Object, InternalCacheEntry>(128, 0.75f,concurrencyLevel);
      entryFactory = new InternalEntryFactory();
      evictionListener = null;
   }

   protected DefaultDataContainer(int concurrencyLevel, int maxEntries, EvictionStrategy strategy, EvictionThreadPolicy policy) {

      // translate eviction policy and strategy
      switch (policy) {
         case PIGGYBACK:
         case DEFAULT:
            evictionListener = new DefaultEvictionListener();
            break;
         default:
            throw new IllegalArgumentException("No such eviction thread policy " + strategy);
      }

      Eviction eviction;
      switch (strategy) {
         case FIFO:
         case UNORDERED:
         case LRU:
            eviction = Eviction.LRU;
            break;
         case LRU_OLD:
            eviction = Eviction.LRU_OLD;
            break;
         case LIRS:
            eviction = Eviction.LIRS;
            break;
         default:
            throw new IllegalArgumentException("No such eviction strategy " + strategy);
      }
      entries = new BoundedConcurrentHashMap<Object, InternalCacheEntry>(maxEntries, concurrencyLevel, eviction, evictionListener);
      entryFactory = new InternalEntryFactory();
   }

   @Inject
   public void initialize(EvictionManager evictionManager, PassivationManager passivator) {
      this.evictionManager = evictionManager;
      this.passivator = passivator;
   }

   public static DataContainer boundedDataContainer(int concurrencyLevel, int maxEntries,
            EvictionStrategy strategy, EvictionThreadPolicy policy) {
      return new DefaultDataContainer(concurrencyLevel, maxEntries, strategy, policy);
   }

   public static DataContainer unBoundedDataContainer(int concurrencyLevel) {
      return new DefaultDataContainer(concurrencyLevel);
   }

   public InternalCacheEntry peek(Object key) {
      InternalCacheEntry e = entries.get(key);
      return e;
   }

   public InternalCacheEntry get(Object k) {
      InternalCacheEntry e = peek(k);
      if (e != null) {
         if (e.isExpired(System.currentTimeMillis())) {
            entries.remove(k);
            e = null;
         } else {
            e.touch();
         }
      }
      return e;
   }

   public void put(Object k, Object v, long lifespan, long maxIdle) {
      InternalCacheEntry e = entries.get(k);
      if (e != null) {
         e.setValue(v);
         InternalCacheEntry original = e;
         e = entryFactory.update(e, lifespan, maxIdle);
         // we have the same instance. So we need to reincarnate.
         if(original == e) {
            e.reincarnate();
         }
      } else {
         // this is a brand-new entry
         e = entryFactory.createNewEntry(k, v, lifespan, maxIdle);
      }
      entries.put(k, e);
   }

   public boolean containsKey(Object k) {
      InternalCacheEntry ice = peek(k);
      if (ice != null && ice.isExpired(System.currentTimeMillis())) {
         entries.remove(k);
         ice = null;
      }
      return ice != null;
   }

   public InternalCacheEntry remove(Object k) {
      InternalCacheEntry e = entries.remove(k);
      return e == null || e.isExpired(System.currentTimeMillis()) ? null : e;
   }

   public int size() {
      return entries.size();
   }

   public void clear() {
      entries.clear();
   }

   public Set<Object> keySet() {
      return Collections.unmodifiableSet(entries.keySet());
   }

   public Collection<Object> values() {
      return new Values();
   }

   public Set<InternalCacheEntry> entrySet() {
      return new EntrySet();
   }

   public void purgeExpired() {
      long currentTimeMillis = System.currentTimeMillis();
      for (Iterator<InternalCacheEntry> purgeCandidates = entries.values().iterator(); purgeCandidates.hasNext();) {
         InternalCacheEntry e = purgeCandidates.next();
         if (e.isExpired(currentTimeMillis)) {
            purgeCandidates.remove();
         }
      }
   }

   public Iterator<InternalCacheEntry> iterator() {
      return new EntryIterator(entries.values().iterator());
   }

   private final class DefaultEvictionListener implements EvictionListener<Object, InternalCacheEntry> {
      @Override
      public void onEntryEviction(Map<Object, InternalCacheEntry> evicted) {
         evictionManager.onEntryEviction(evicted);
      }

      @Override
      public void onEntryChosenForEviction(InternalCacheEntry internalCacheEntry) {
         passivator.passivate(internalCacheEntry);
      }
   }

   private static class ImmutableEntryIterator extends EntryIterator {
      ImmutableEntryIterator(Iterator<InternalCacheEntry> it){
         super(it);
      }

      @Override
      public InternalCacheEntry next() {
         return Immutables.immutableInternalCacheEntry(super.next());
      }
   }

   public static class EntryIterator implements Iterator<InternalCacheEntry> {


      private final Iterator<InternalCacheEntry> it;

      EntryIterator(Iterator<InternalCacheEntry> it){this.it=it;}

      public InternalCacheEntry next() {
         return it.next();
      }

      @Override
      public boolean hasNext() {
         return it.hasNext();
      }

      @Override
      public void remove() {
         throw new UnsupportedOperationException();
      }
   }

   /**
    * Minimal implementation needed for unmodifiable Set
    *
    */
   private class EntrySet extends AbstractSet<InternalCacheEntry> {

      @Override
      public boolean contains(Object o) {
         if (!(o instanceof Map.Entry)) {
            return false;
         }

         @SuppressWarnings("rawtypes")
         Map.Entry e = (Map.Entry) o;
         InternalCacheEntry ice = entries.get(e.getKey());
         if (ice == null) {
            return false;
         }
         return ice.getValue().equals(e.getValue());
      }

      @Override
      public Iterator<InternalCacheEntry> iterator() {
         return new ImmutableEntryIterator(entries.values().iterator());
      }

      @Override
      public int size() {
         return entries.size();
      }
   }

   /**
    * Minimal implementation needed for unmodifiable Collection
    *
    */
   private class Values extends AbstractCollection<Object> {
      @Override
      public Iterator<Object> iterator() {
         return new ValueIterator(entries.values().iterator());
      }

      @Override
      public int size() {
         return entries.size();
      }
   }

   private static class ValueIterator implements Iterator<Object> {
      Iterator<InternalCacheEntry> currentIterator;

      private ValueIterator(Iterator<InternalCacheEntry> it) {
         currentIterator = it;
      }

      public boolean hasNext() {
         return currentIterator.hasNext();
      }

      public void remove() {
         throw new UnsupportedOperationException();
      }

      public Object next() {
         return currentIterator.next().getValue();
      }
   }
}
