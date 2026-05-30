package com.zdsj.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer

@Configuration
class RedisConfig {

    @Bean
    fun redisTemplate(
        factory: RedisConnectionFactory,
        objectMapper: ObjectMapper,
    ): RedisTemplate<String, Any> {
        val template = RedisTemplate<String, Any>()
        template.connectionFactory = factory
        val stringSerializer = StringRedisSerializer()
        val jsonSerializer = GenericJackson2JsonRedisSerializer(objectMapper)
        template.keySerializer = stringSerializer
        template.hashKeySerializer = stringSerializer
        template.valueSerializer = jsonSerializer
        template.hashValueSerializer = jsonSerializer
        template.afterPropertiesSet()
        return template
    }
}
