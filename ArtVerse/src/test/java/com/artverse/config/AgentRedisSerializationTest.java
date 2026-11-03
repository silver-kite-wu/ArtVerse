package com.artverse.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializer;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRedisSerializationTest {
    record Fixture(UUID requestId,OffsetDateTime createdAt,String state) {}
    @Test void preservesTimeAndTypeMetadataForAgentToolState() {
        var template=new SaTokenConfig().redisTemplate(mock(RedisConnectionFactory.class));
        @SuppressWarnings("unchecked") var serializer=(RedisSerializer<Object>)template.getValueSerializer();
        var value=new Fixture(UUID.randomUUID(),OffsetDateTime.parse("2026-10-25T12:00:00Z"),"WAITING_USER");
        assertThat(serializer.deserialize(serializer.serialize(value))).isEqualTo(value);
    }
}
