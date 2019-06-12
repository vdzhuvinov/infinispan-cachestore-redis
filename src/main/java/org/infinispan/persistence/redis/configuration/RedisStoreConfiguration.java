package org.infinispan.persistence.redis.configuration;


import org.infinispan.commons.configuration.BuiltBy;
import org.infinispan.commons.configuration.ConfigurationFor;
import org.infinispan.commons.configuration.attributes.AttributeSet;
import org.infinispan.configuration.cache.AsyncStoreConfiguration;
import org.infinispan.configuration.cache.SingletonStoreConfiguration;
import org.infinispan.persistence.redis.RedisStore;


@BuiltBy(RedisStoreConfigurationBuilder.class)
@ConfigurationFor(RedisStore.class)
final public class RedisStoreConfiguration extends SimpleRedisStoreConfiguration
{
    public RedisStoreConfiguration(
        AttributeSet attributes,
        AsyncStoreConfiguration async,
        SingletonStoreConfiguration singletonStore,
        ConnectionPoolConfiguration connectionPool
    )
    {
        super(attributes, async, singletonStore, connectionPool);
    }
}
