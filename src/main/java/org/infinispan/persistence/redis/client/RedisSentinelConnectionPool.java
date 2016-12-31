package org.infinispan.persistence.redis.client;


import java.util.HashSet;
import java.util.Set;

import org.infinispan.persistence.redis.configuration.ConnectionPoolConfiguration;
import org.infinispan.persistence.redis.configuration.RedisServerConfiguration;
import org.infinispan.persistence.redis.configuration.RedisStoreConfiguration;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisSentinelPool;

final public class RedisSentinelConnectionPool implements RedisConnectionPool
{
    private RedisMarshaller<String> marshaller;
    private JedisSentinelPool sentinelPool;
    private static final Log log = LogFactory.getLog(RedisSentinelConnectionPool.class, Log.class);

    public RedisSentinelConnectionPool(RedisStoreConfiguration configuration, RedisMarshaller<String> marshaller)
    {
        Set<String> sentinels = new HashSet<String>();
        for (RedisServerConfiguration server : configuration.sentinels()) {
            sentinels.add(String.format("%s:%s", server.host(), server.port()));
        }

        ConnectionPoolConfiguration connectionPoolConfiguration = configuration.connectionPool();

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(connectionPoolConfiguration.maxTotal());
        poolConfig.setMinIdle(connectionPoolConfiguration.minIdle());
        poolConfig.setMaxIdle(connectionPoolConfiguration.maxIdle());
        poolConfig.setMinEvictableIdleTimeMillis(connectionPoolConfiguration.minEvictableIdleTime());
        poolConfig.setTimeBetweenEvictionRunsMillis(connectionPoolConfiguration.timeBetweenEvictionRuns());
        poolConfig.setTestOnCreate(connectionPoolConfiguration.testOnCreate());
        poolConfig.setTestOnBorrow(connectionPoolConfiguration.testOnBorrow());
        poolConfig.setTestOnReturn(connectionPoolConfiguration.testOnReturn());
        poolConfig.setTestWhileIdle(connectionPoolConfiguration.testOnIdle());

        sentinelPool = new JedisSentinelPool(
            configuration.masterName(),
            sentinels,
            poolConfig,
            configuration.connectionTimeout(),
            configuration.socketTimeout(),
            configuration.password(),
            configuration.database(),
            null
        );

        this.marshaller = marshaller;
    }

    @Override
    public RedisConnection getConnection()
    {
        return new RedisServerConnection(this.sentinelPool.getResource(), this.marshaller);
    }
    
    
    @Override
    public int getNumActiveConnections() {
        return sentinelPool.getNumActive();
    }
    
    
    @Override
    public int getNumIdleConnections() {
        return sentinelPool.getNumIdle();
    }
    
    
    @Override
    public int getNumWaitersForConnection() {
        return sentinelPool.getNumWaiters();
    }
    
    
    @Override
    public long getMeanConnectionBorrowWaitTimeMillis() {
        return sentinelPool.getMeanBorrowWaitTimeMillis();
    }
    
    
    @Override
    public long getMaxConnectionBorrowWaitTimeMillis() {
        return sentinelPool.getMaxBorrowWaitTimeMillis();
    }
    
    
    @Override
    public void shutdown()
    {
        this.sentinelPool.destroy();
    }
}
