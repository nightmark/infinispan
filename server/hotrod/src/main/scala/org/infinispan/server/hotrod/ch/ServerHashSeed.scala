/*
 * Copyright 2011 Red Hat, Inc. and/or its affiliates.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA
 */

package org.infinispan.server.hotrod.ch

import org.infinispan.distribution.ch.HashSeed
import org.infinispan.remoting.transport.Address
import org.infinispan.Cache
import org.infinispan.server.hotrod.ServerAddress

/**
 * Hash seed implementation for Hot Rod servers
 *
 * @author Galder Zamarreño
 * @since 5.1
 */
class ServerHashSeed(addressCache: Cache[Address, ServerAddress]) extends HashSeed {

   def getHashSeed(clusterMember: Address) = {
      val serverAddress = addressCache.get(clusterMember)
      if (serverAddress == null)
         throw new IllegalStateException(
            "Server address for %s not present".format(clusterMember))

      serverAddress
   }

}