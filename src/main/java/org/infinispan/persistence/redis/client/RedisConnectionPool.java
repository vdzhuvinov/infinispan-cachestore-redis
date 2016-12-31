package org.infinispan.persistence.redis.client;


public interface RedisConnectionPool
{
    /**
     * Gets a Redis client connection from the pool
     */
    RedisConnection getConnection();
    
    
    /**
     * Returns the number of active Redis client connections.
     */
    int getNumActiveConnections();
    
    
    /**
     * Returns the number of idle Redis client connections.
     */
    int getNumIdleConnections();
    
    
    /**
     * Returns the number of waiters for a Redis client connection.
     */
    int getNumWaitersForConnection();
    
    
    /**
     * Returns the mean time waiting to borrow a Redis client connection from
     * the pool, in milliseconds.
     */
    long getMeanConnectionBorrowWaitTimeMillis();
    
    
    /**
     * Returns the maximum time waiting to borrow a Redis client connection
     * from the pool, in milliseconds.
     */
    long getMaxConnectionBorrowWaitTimeMillis();
    

    /**
     * Shuts down the Redis client connection pool, closing all connections
     */
    void shutdown();
}
