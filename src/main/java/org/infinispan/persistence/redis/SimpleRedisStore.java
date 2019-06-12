package org.infinispan.persistence.redis;


import java.util.*;
import java.util.concurrent.TimeUnit;

import com.codahale.metrics.Gauge;
import net.jcip.annotations.ThreadSafe;
import org.infinispan.commons.io.ByteBuffer;
import org.infinispan.marshall.core.MarshalledEntry;
import org.infinispan.persistence.redis.client.RedisConnection;
import org.infinispan.persistence.redis.client.RedisConnectionPool;
import org.infinispan.persistence.redis.client.RedisConnectionPoolFactory;
import org.infinispan.persistence.spi.ExternalStore;
import org.infinispan.persistence.spi.InitializationContext;
import org.infinispan.persistence.spi.PersistenceException;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import com.nimbusds.common.monitor.MonitorRegistries;



@ThreadSafe
public class SimpleRedisStore implements ExternalStore
{
    /**
     * The instances of this class, keyed by Infinispan cache name.
     */
    protected static Map<String, SimpleRedisStore> instances = new Hashtable<>();
    
    
    /**
     * Returns the {@link #init initialised} instances of this class.
     *
     * @return The instances of this class as an unmodifiable map, keyed by
     *         Infinispan cache name.
     */
    public static Map<String, SimpleRedisStore> getInstances() {
        return Collections.unmodifiableMap(instances);
    }
    
    protected static final Log log = LogFactory.getLog(SimpleRedisStore.class, Log.class);

    protected InitializationContext ctx = null;
    protected RedisConnectionPool connectionPool = null;

    /**
     * Used to initialize a cache loader.  Typically invoked by the {@link org.infinispan.persistence.manager.PersistenceManager}
     * when setting up cache loaders.
     *
     * @throws PersistenceException in case of an error, e.g. communicating with the external storage
     */
    @Override
    public void init(InitializationContext ctx)
    {
        SimpleRedisStore.log.infof("Initialising Redis store for cache '%s'", ctx.getCache().getName());
        this.ctx = ctx;
    
        // Register store
        if (ctx.getCache().getName() != null) {
            SimpleRedisStore.instances.put(ctx.getCache().getName(), this);
        }
    }

    /**
     * Invoked on component start
     */
    @Override
    public void start()
    {
        SimpleRedisStore.log.infof("Starting Redis store for cache '%s'", ctx.getCache().getName());

        try {
            this.connectionPool = RedisConnectionPoolFactory.factory(this.ctx.getConfiguration(), this.ctx.getMarshaller());
        }
        catch(Exception ex) {
            SimpleRedisStore.log.errorf(ex, "Failed to initialise the Redis store for cache '%s'", ctx.getCache().getName());
            throw new PersistenceException(ex);
        }
        
        // Register Dropwizard metrics
        MonitorRegistries.register(
                ctx.getCache().getName() + ".redisStore.numActiveConnections",
                (Gauge<Integer>) () -> connectionPool.getNumActiveConnections());
        MonitorRegistries.register(
                ctx.getCache().getName() + ".redisStore.numIdleConnections",
                (Gauge<Integer>) () -> connectionPool.getNumIdleConnections());
        MonitorRegistries.register(
                ctx.getCache().getName() + ".redisStore.numWaitingForConnection",
                (Gauge<Integer>) () -> connectionPool.getNumWaitersForConnection());
        MonitorRegistries.register(
                ctx.getCache().getName() + ".redisStore.meanWaitingTimeForConnectionMs",
                (Gauge<Long>) () -> connectionPool.getMeanConnectionBorrowWaitTimeMillis());
        MonitorRegistries.register(
                ctx.getCache().getName() + ".redisStore.maxWaitingTimeForConnectionMs",
                (Gauge<Long>) () -> connectionPool.getMaxConnectionBorrowWaitTimeMillis());
    }

    /**
     * Invoked on component stop
     */
    @Override
    public void stop()
    {
        SimpleRedisStore.log.infof("Stopping Redis store for cache '%s'", ctx.getCache().getName());

        if (null != this.connectionPool) {
            this.connectionPool.shutdown();
        }
    
        // Unregister store
        if (ctx.getCache().getName() != null) {
            SimpleRedisStore.instances.remove(ctx.getCache().getName());
        }
    }

    /**
     * Fetches an entry from the storage. If a {@link MarshalledEntry} needs to be created here, {@link
     * InitializationContext#getMarshalledEntryFactory()} and {@link
     * InitializationContext#getByteBufferFactory()} should be used.
     *
     * @return the entry, or null if the entry does not exist
     * @throws PersistenceException in case of an error, e.g. communicating with the external storage
     */
    @Override
    public MarshalledEntry load(Object key)
    {
        SimpleRedisStore.log.debugf("Loading entry from the Redis store for cache '%s'", ctx.getCache().getName());
        RedisConnection connection = null;

        try {
            connection = this.connectionPool.getConnection();
            List<byte[]> data = connection.hmget(key, "value", "metadata");
            byte[] value = data.get(0);

            if (null == value) {
                return null;
            }

            ByteBuffer valueBuf = this.ctx.getByteBufferFactory().newByteBuffer(value, 0, value.length);
            ByteBuffer metadataBuf = null;
            byte[] metadata = data.get(1);

            if (null != metadata) {
                metadataBuf = this.ctx.getByteBufferFactory().newByteBuffer(metadata, 0, metadata.length);
            }

            return this.ctx.getMarshalledEntryFactory().newMarshalledEntry(key, valueBuf, metadataBuf);
        }
        catch(Exception ex) {
            SimpleRedisStore.log.errorf(ex, "Failed to load entry from the Redis store for cache '%s'", ctx.getCache().getName());
            throw new PersistenceException(ex);
        }
        finally {
            if (null != connection) {
                connection.release();
            }
        }
    }

    /**
     * Persists the entry to the storage.
     *
     * @throws PersistenceException in case of an error, e.g. communicating with the external storage
     * @see MarshalledEntry
     */
    @Override
    public void write(MarshalledEntry marshalledEntry)
    {
        SimpleRedisStore.log.debugf("Writing entry to the Redis store for cache '%s'", ctx.getCache().getName());
        RedisConnection connection = null;

        try {
            byte[] value;
            byte[] metadata;
            long lifespan = -1;
            Map<String,byte[]> fields = new HashMap<>();

            if (null != marshalledEntry.getValueBytes()) {
                value = marshalledEntry.getValueBytes().getBuf();
                fields.put("value", value);
            }

            if (null != marshalledEntry.getMetadataBytes()) {
                metadata = marshalledEntry.getMetadataBytes().getBuf();
                fields.put("metadata", metadata);
                lifespan = marshalledEntry.getMetadata().lifespan();
            }

            connection = this.connectionPool.getConnection();
            connection.hmset(marshalledEntry.getKey(), fields);

            if (-1 < lifespan) {
                connection.expire(marshalledEntry.getKey(), this.toSeconds(lifespan, marshalledEntry.getKey(), "lifespan"));
            }
        }
        catch(Exception ex) {
            SimpleRedisStore.log.errorf(ex,"Failed to write entry to the Redis store for cache '%s'", ctx.getCache().getName());
            throw new PersistenceException(ex);
        }
        finally {
            if (null != connection) {
                connection.release();
            }
        }
    }

    /**
     * Delete the entry for the given key from the store
     *
     * @return true if the entry existed in the persistent store and it was deleted.
     * @throws PersistenceException in case of an error, e.g. communicating with the external storage
     */
    @Override
    public boolean delete(Object key)
    {
        SimpleRedisStore.log.debugf("Deleting entry from Redis store for cache '%s'", ctx.getCache().getName());
        RedisConnection connection = null;

        try {
            connection = this.connectionPool.getConnection();
            return connection.delete(key);
        }
        catch(Exception ex) {
            SimpleRedisStore.log.errorf(ex,"Failed to delete entry from the Redis store for cache '%s'", ctx.getCache().getName());
            throw new PersistenceException(ex);
        }
        finally {
            if (null != connection) {
                connection.release();
            }
        }
    }

    /**
     * Returns true if the storage contains an entry associated with the given key.
     *
     * @return True if the cache contains the key specified
     * @throws PersistenceException in case of an error, e.g. communicating with the external storage
     */
    @Override
    public boolean contains(Object key)
    {
        SimpleRedisStore.log.debugf("Checking key in Redis store for cache '%s'", ctx.getCache().getName());
        RedisConnection connection = null;

        try {
            connection = this.connectionPool.getConnection();
            return connection.exists(key);
        }
        catch(Exception ex) {
            SimpleRedisStore.log.errorf(ex,"Failed to check key in Redis store for cache '%s'", ctx.getCache().getName());
            throw new PersistenceException(ex);
        }
        finally {
            if (null != connection) {
                connection.release();
            }
        }
    }

    private int toSeconds(long millis, Object key, String desc)
    {
        if (millis > 0 && millis < 1000) {
            if (log.isTraceEnabled()) {
                log.tracef("Adjusting %s time for (k,v): (%s, %s) from %d millis to 1 sec, as milliseconds are not supported by Redis",
                    desc ,key, millis);
            }

            return 1;
        }

        return (int) TimeUnit.MILLISECONDS.toSeconds(millis);
    }
}
