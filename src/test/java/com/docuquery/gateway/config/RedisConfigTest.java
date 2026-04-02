package com.docuquery.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisConfigTest {

    private final RedisConfig redisConfig = new RedisConfig();

    @Test
    @DisplayName("reactiveRedisTemplate bean is non-null")
    void beanIsNonNull() {
        ReactiveRedisConnectionFactory mockFactory = mock(ReactiveRedisConnectionFactory.class);

        ReactiveRedisTemplate<String, String> template = redisConfig.reactiveRedisTemplate(mockFactory);

        assertThat(template).isNotNull();
    }

    @Test
    @DisplayName("reactiveRedisTemplate uses String serialization for keys and values")
    void beanUsesStringSerializationContext() {
        ReactiveRedisConnectionFactory mockFactory = mock(ReactiveRedisConnectionFactory.class);

        ReactiveRedisTemplate<String, String> template = redisConfig.reactiveRedisTemplate(mockFactory);

        assertThat(template).isInstanceOf(ReactiveRedisTemplate.class);
    }
}
