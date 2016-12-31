package org.infinispan.persistence.redis.client;


import java.util.HashSet;
import java.util.Set;

import org.infinispan.persistence.redis.configuration.ConnectionPoolConfiguration;
import org.infinispan.persistence.redis.configuration.RedisServerConfiguration;
import org.infinispan.persistence.redis.configuration.RedisStoreConfiguration;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisPoolConfig;

final public class RedisClusterConnectionPool implements RedisConnectionPool
{
    private RedisMarshaller<String> marshaller;
    private JedisCluster cluster;

    public RedisClusterConnectionPool(RedisStoreConfiguration configuration, RedisMarshaller<String> marshaller)
    {
        Set<HostAndPort> clusterNodes = new HashSet<HostAndPort>();
        for (RedisServerConfiguration server : configuration.servers()) {
            clusterNodes.add(new HostAndPort(server.host(), server.port()));
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

        this.cluster = new JedisCluster(
            clusterNodes,
            configuration.connectionTimeout(),
            configuration.socketTimeout(),
            configuration.maxRedirections(),
            poolConfig
        );

        this.marshaller = marshaller;
    }

    @Override
    public RedisConnection getConnection()
    {
        return new RedisClusterConnection(this.cluster, this.marshaller);
    }
    
    @Override
    public int getNumActiveConnections() {
        return -1; // TODO
    }
    
    @Override
    public int getNumIdleConnections() {
        return -1; // TODO
    }
    
    @Override
    public int getNumWaitersForConnection() {
        return -1; // TODO
    }
    
    @Override
    public long getMeanConnectionBorrowWaitTimeMillis() {
        return -1; // TODO
    }
    
    @Override
    public long getMaxConnectionBorrowWaitTimeMillis() {
        return -1; // TODO
    }
    
    
    @Override
    public void shutdown()
    {
        this.cluster.close();
    }
}
