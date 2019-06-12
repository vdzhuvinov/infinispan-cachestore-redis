package org.infinispan.persistence.redis;

import com.nimbusds.common.monitor.MonitorRegistries;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.PersistenceConfigurationBuilder;
import org.infinispan.persistence.BaseStoreFunctionalTest;
import org.infinispan.persistence.redis.configuration.SimpleRedisStoreConfiguration.Topology;
import org.infinispan.persistence.redis.configuration.RedisStoreConfigurationBuilder;
import org.infinispan.persistence.redis.support.RedisCluster;
import org.infinispan.persistence.redis.support.RedisServer;
import org.junit.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test(testName = "persistence.redis.RedisServerStoreFunctionalTest", groups = "functional")
public class RedisServerStoreFunctionalTest extends BaseStoreFunctionalTest
{
    private RedisServer redisServer;

    @BeforeClass(alwaysRun = true)
    public void beforeClass()
        throws Exception
    {
        redisServer = new RedisServer();
        redisServer.start();
    }

    @AfterClass(alwaysRun = true)
    public void afterClass()
    {
        redisServer.kill();
    }

    @Override
    protected PersistenceConfigurationBuilder createCacheStoreConfig(
        PersistenceConfigurationBuilder persistence,
        boolean b
    )
    {
        return createCacheStoreConfig(persistence, b, 0);
    }

    protected PersistenceConfigurationBuilder createCacheStoreConfig(
        PersistenceConfigurationBuilder persistence,
        boolean b,
        int database
    )
    {
        persistence
            .addStore(RedisStoreConfigurationBuilder.class)
            .topology(Topology.SERVER)
            .addServer()
            .host("localhost")
            .port(6379)
            .database(database)
        ;

        return persistence;
    }

    @Override
    public void testPreloadAndExpiry()
    {
        // No support for pre-load
    }

    @Override
    public void testPreloadStoredAsBinary()
    {
        // No support for pre-load
    }

    @Override
    public void testTwoCachesSameCacheStore()
    {
        ConfigurationBuilder cb1 = new ConfigurationBuilder();
        cb1.read(this.cacheManager.getDefaultCacheConfiguration());
        this.createCacheStoreConfig(cb1.persistence(), false, 0);
        Configuration c1 = cb1.build();

        ConfigurationBuilder cb2 = new ConfigurationBuilder();
        cb2.read(this.cacheManager.getDefaultCacheConfiguration());
        this.createCacheStoreConfig(cb2.persistence(), false, 1);
        Configuration c2 = cb2.build();

        this.cacheManager.defineConfiguration("testTwoCachesSameCacheStore-1", c1);
        this.cacheManager.defineConfiguration("testTwoCachesSameCacheStore-2", c2);
        Cache first = this.cacheManager.getCache("testTwoCachesSameCacheStore-1");
        Cache second = this.cacheManager.getCache("testTwoCachesSameCacheStore-2");

        first.start();
        second.start();
        first.put("key", this.wrap("key", "val"));
        Assert.assertEquals("val", this.unwrap(first.get("key")));
        Assert.assertNull(second.get("key"));
        second.put("key2", this.wrap("key2", "val2"));
        Assert.assertEquals("val2", this.unwrap(second.get("key2")));
        Assert.assertNull(first.get("key2"));
    
        // Metrics present
        // cache-1
        Assert.assertNotNull(MonitorRegistries.getMetricRegistry().getGauges().get("testTwoCachesSameCacheStore-1.redisStore.numActiveConnections"));
        Assert.assertNotNull(MonitorRegistries.getMetricRegistry().getGauges().get("testTwoCachesSameCacheStore-1.redisStore.numIdleConnections"));
        Assert.assertNotNull(MonitorRegistries.getMetricRegistry().getGauges().get("testTwoCachesSameCacheStore-1.redisStore.numWaitingForConnection"));
        Assert.assertNotNull(MonitorRegistries.getMetricRegistry().getGauges().get("testTwoCachesSameCacheStore-1.redisStore.meanWaitingTimeForConnectionMs"));
        Assert.assertNotNull(MonitorRegistries.getMetricRegistry().getGauges().get("testTwoCachesSameCacheStore-1.redisStore.maxWaitingTimeForConnectionMs"));
        
        // cache-2
        Assert.assertNotNull(MonitorRegistries.getMetricRegistry().getGauges().get("testTwoCachesSameCacheStore-2.redisStore.numActiveConnections"));
        Assert.assertNotNull(MonitorRegistries.getMetricRegistry().getGauges().get("testTwoCachesSameCacheStore-2.redisStore.numIdleConnections"));
        Assert.assertNotNull(MonitorRegistries.getMetricRegistry().getGauges().get("testTwoCachesSameCacheStore-2.redisStore.numWaitingForConnection"));
        Assert.assertNotNull(MonitorRegistries.getMetricRegistry().getGauges().get("testTwoCachesSameCacheStore-2.redisStore.meanWaitingTimeForConnectionMs"));
        Assert.assertNotNull(MonitorRegistries.getMetricRegistry().getGauges().get("testTwoCachesSameCacheStore-2.redisStore.maxWaitingTimeForConnectionMs"));
    }
    
    public void testGetRedisStoreInstance()
    {
        ConfigurationBuilder cb1 = new ConfigurationBuilder();
        cb1.read(this.cacheManager.getDefaultCacheConfiguration());
        this.createCacheStoreConfig(cb1.persistence(), false, 0);
        Configuration c1 = cb1.build();
    
        ConfigurationBuilder cb2 = new ConfigurationBuilder();
        cb2.read(this.cacheManager.getDefaultCacheConfiguration());
        this.createCacheStoreConfig(cb2.persistence(), false, 1);
        Configuration c2 = cb2.build();
    
        int initialNumRedisStoreInstances = RedisStore.getInstances().size();
        
        this.cacheManager.defineConfiguration("testTwoCachesSameCacheStore-1", c1);
        this.cacheManager.defineConfiguration("testTwoCachesSameCacheStore-2", c2);
        Cache first = this.cacheManager.getCache("testTwoCachesSameCacheStore-1");
        Cache second = this.cacheManager.getCache("testTwoCachesSameCacheStore-2");
    
        first.start();
        second.start();
        
        RedisStore firstRedisStore = (RedisStore)RedisStore.getInstances().get("testTwoCachesSameCacheStore-1");
        RedisStore secondRedisStore = (RedisStore)RedisStore.getInstances().get("testTwoCachesSameCacheStore-2");
        Assert.assertEquals(initialNumRedisStoreInstances + 2, RedisStore.getInstances().size());
        
        first.put("key1", "val2");
        second.put("key2", "val2");
    
        Assert.assertTrue(firstRedisStore.contains("key1"));
        Assert.assertTrue(secondRedisStore.contains("key2"));
        
        first.stop();
        second.stop();
        
        Assert.assertNull(RedisStore.getInstances().get("testTwoCachesSameCacheStore-1"));
        Assert.assertNull(RedisStore.getInstances().get("testTwoCachesSameCacheStore-2"));
        Assert.assertEquals(initialNumRedisStoreInstances, RedisStore.getInstances().size());
    }
}
