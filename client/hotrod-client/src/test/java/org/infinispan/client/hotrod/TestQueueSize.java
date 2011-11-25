package org.infinispan.client.hotrod;

import static org.testng.AssertJUnit.assertTrue;

import java.lang.reflect.Method;

import org.infinispan.client.hotrod.test.MultiHotRodServersTest;
import org.infinispan.config.Configuration;
import org.infinispan.config.Configuration.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.remoting.ReplicationQueueImpl;
import org.testng.annotations.Test;
/**
 * @author Jozef Vilkolak
 * @since 5.1
 */
@Test(groups = "functional", testName = "client.hotrod.TestQueueSize")
public class TestQueueSize extends MultiHotRodServersTest {   

   @Override
   protected void createCacheManagers() throws Throwable {
       Configuration config = new Configuration().fluent()
               .clustering()
                  .mode(CacheMode.REPL_ASYNC)
                  .async()
                     .replQueueInterval(1000L)
                     .useReplQueue(true)
               .eviction()
                  .maxEntries(3)
               .build();      
      createHotRodServers(2, config);
   }

   public void testQueueSize(Method m) throws Exception {
      RemoteCacheManager rcm1 = client(0);
      RemoteCacheManager rcm2 = client(1);
      RemoteCache<String, String> asyncCache1 = rcm1.getCache();
      RemoteCache<String, String> asyncCache2 = rcm2.getCache();
            
      asyncCache1.clear();
      asyncCache1.put("k1", "v1");
      
      assertTrue(null != asyncCache1.get("k1"));
      assertTrue(null == asyncCache2.get("k1"));
      
      asyncCache1.put("k2", "v2");
      //k3 fills up the queue -> flush
      asyncCache1.put("k3", "v3");
      Thread.sleep(1000); //wait for the queue to be flushed
      
      assertTrue(null != asyncCache1.get("k1"));
      assertTrue(null != asyncCache2.get("k1"));
   }

}
