package org.infinispan.persistence.redis;


import java.util.concurrent.Executor;

import net.jcip.annotations.ThreadSafe;
import org.infinispan.commons.configuration.ConfiguredBy;
import org.infinispan.commons.io.ByteBuffer;
import org.infinispan.commons.persistence.Store;
import org.infinispan.filter.KeyFilter;
import org.infinispan.marshall.core.MarshalledEntry;
import org.infinispan.persistence.TaskContextImpl;
import org.infinispan.persistence.redis.client.RedisConnection;
import org.infinispan.persistence.redis.client.RedisConnectionPool;
import org.infinispan.persistence.redis.configuration.RedisStoreConfiguration;
import org.infinispan.persistence.spi.AdvancedLoadWriteStore;
import org.infinispan.persistence.spi.InitializationContext;
import org.infinispan.persistence.spi.PersistenceException;

@ThreadSafe
@ConfiguredBy(RedisStoreConfiguration.class)
@Store(shared = true)
final public class RedisStore extends SimpleRedisStore implements AdvancedLoadWriteStore
{

    /**
     * Invoked on component stop
     */
    @Override
    public void stop()
    {
        RedisStore.log.infof("Stopping Redis store for cache '%s'", ctx.getCache().getName());

        if (null != this.connectionPool) {
            this.connectionPool.shutdown();
        }
    
        // Unregister store
        if (ctx.getCache().getName() != null) {
            RedisStore.instances.remove(ctx.getCache().getName());
        }
    }

    /**
     * Iterates in parallel over the entries in the storage using the threads from the <b>executor</b> pool. For each
     * entry the {@link CacheLoaderTask#processEntry(MarshalledEntry, TaskContext)} is
     * invoked. Before passing an entry to the callback task, the entry should be validated against the <b>filter</b>.
     * Implementors should build an {@link TaskContext} instance (implementation) that is fed to the {@link
     * CacheLoaderTask} on every invocation. The {@link CacheLoaderTask} might invoke {@link
     * org.infinispan.persistence.spi.AdvancedCacheLoader.TaskContext#stop()} at any time, so implementors of this method
     * should verify TaskContext's state for early termination of iteration. The method should only return once the
     * iteration is complete or as soon as possible in the case TaskContext.stop() is invoked.
     *
     * @param filter        to validate which entries should be feed into the task. Might be null.
     * @param task          callback to be invoked in parallel for each stored entry that passes the filter check
     * @param executor      an external thread pool to be used for parallel iteration
     * @param fetchValue    whether or not to fetch the value from the persistent store. E.g. if the iteration is
     *                      intended only over the key set, no point fetching the values from the persistent store as
     *                      well
     * @param fetchMetadata whether or not to fetch the metadata from the persistent store. E.g. if the iteration is
     *                      intended only ove the key set, then no pint fetching the metadata from the persistent store
     *                      as well
     * @throws PersistenceException in case of an error, e.g. communicating with the external storage
     */
    @Override
    public void process(
        final KeyFilter filter,
        final CacheLoaderTask task,
        Executor executor,
        boolean fetchValue,
        boolean fetchMetadata
    )
    {
        RedisStore.log.debugf("Iterating Redis store entries for cache '%s'", ctx.getCache().getName());

        final InitializationContext ctx = this.ctx;
        final TaskContext taskContext = new TaskContextImpl();
        final RedisConnectionPool connectionPool = this.connectionPool;
        final RedisStore cacheStore = this;
        RedisConnection connection = connectionPool.getConnection();

        try {
            for (Object key : connection.scan()) {
                if (taskContext.isStopped()) {
                    break;
                }

                if (null != filter && ! filter.accept(key)) {
                    continue;
                }

                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            MarshalledEntry marshalledEntry;
                            if (fetchValue) {
                                marshalledEntry = cacheStore.load(key);
                            }
                            else {
                                marshalledEntry = ctx.getMarshalledEntryFactory().newMarshalledEntry(
                                    key, null, (ByteBuffer) null);
                            }

                            if (null != marshalledEntry) {
                                task.processEntry(marshalledEntry, taskContext);
                            }
                        }
                        catch (Exception ex) {
                            RedisStore.log.errorf(ex, "Failed to process a Redis store key for cache '%s'", ctx.getCache().getName());
                            throw new PersistenceException(ex);
                        }
                    }
                });
            }
        }
        catch(Exception ex) {
            RedisStore.log.errorf(ex, "Failed to process the Redis store keys for cache '%s'", ctx.getCache().getName());
            throw new PersistenceException(ex);
        }
        finally {
            if (null != connection) {
                connection.release();
            }
        }
    }

    /**
     * Using the thread in the pool, remove all the expired data from the persistence storage. For each removed entry,
     * the supplied listener is invoked.
     *
     * @throws PersistenceException in case of an error, e.g. communicating with the external storage
     */
    @Override
    public void purge(Executor executor, final PurgeListener purgeListener)
    {
        // Nothing to do. All expired entries are purged by Redis itself
    }

    /**
     * Returns the number of elements in the store.
     *
     * @throws PersistenceException in case of an error, e.g. communicating with the external storage
     */
    @Override
    public int size()
    {
        RedisStore.log.debugf("Fetching the Redis store size for cache '%s'", ctx.getCache().getName());
        RedisConnection connection = null;

        try {
            connection = this.connectionPool.getConnection();
            long dbSize = connection.dbSize();

            // Can't return more than Integer.MAX_VALUE due to interface limitation
            // If the number of elements in redis is more than the int max size,
            // log the anomaly and return the int max size
            if (dbSize > Integer.MAX_VALUE) {
                RedisStore.log.warnf("The Redis store for cache '%s' is holding more entries than we can count! " +
                        "Total number of entries found %d. Limited to returning count as %d",
                        ctx.getCache().getName(), dbSize, Integer.MAX_VALUE
                );

                return Integer.MAX_VALUE;
            }
            else {
                return (int) dbSize;
            }
        }
        catch(Exception ex) {
            RedisStore.log.errorf(ex, "Failed to fetch the entry count for the Redis store for cache '%s'", ctx.getCache().getName());
            throw new PersistenceException(ex);
        }
        finally {
            if (null != connection) {
                connection.release();
            }
        }
    }

    /**
     * Removes all the data from the storage.
     *
     * @throws PersistenceException in case of an error, e.g. communicating with the external storage
     */
    @Override
    public void clear()
    {
        RedisStore.log.debugf("Clearing the Redis store for cache '%s'", ctx.getCache().getName());
        RedisConnection connection = null;

        try {
            connection = this.connectionPool.getConnection();
            connection.flushDb();
        }
        catch(Exception ex) {
            RedisStore.log.errorf(ex, "Failed to clear the Redis store for cache '%s'", ctx.getCache().getName());
            throw new PersistenceException(ex);
        }
        finally {
            if (null != connection) {
                connection.release();
            }
        }
    }
}
