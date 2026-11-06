package com.artverse.config;

import io.agentscope.core.state.AgentStateStore;
import com.artverse.agent.gateway.StrictAgentStateStore;
import com.artverse.agent.gateway.AgentTurnStateStores;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.extensions.redis.state.RedisClientAdapter;
import io.agentscope.extensions.redis.state.lettuce.LettuceClientAdapter;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.List;

@Slf4j
@Configuration
public class RedisAgentStateStoreConfig {

    @Bean(destroyMethod = "close")
    public AgentScopeRedisBackend agentScopeRedisBackend(RedisProperties properties) {
        if (properties.getCluster() != null && properties.getCluster().getNodes() != null
                && !properties.getCluster().getNodes().isEmpty()) {
            List<RedisURI> nodes = properties.getCluster().getNodes().stream()
                    .map(node -> nodeUri(node, properties))
                    .toList();
            log.info("Creating AgentScope Redis Cluster state backend with {} nodes", nodes.size());
            return AgentScopeRedisBackend.cluster(RedisClusterClient.create(nodes));
        }
        RedisURI uri = properties.getSentinel() == null
                ? standaloneUri(properties)
                : sentinelUri(properties);
        log.info("Creating AgentScope Redis state backend: mode={} ssl={} database={}",
                properties.getSentinel() == null ? "standalone" : "sentinel",
                properties.getSsl().isEnabled(), properties.getDatabase());
        return AgentScopeRedisBackend.standalone(RedisClient.create(uri));
    }

    @Bean(destroyMethod = "")
    public RedisClientAdapter agentScopeStateClient(AgentScopeRedisBackend backend) {
        return backend.adapter;
    }

    @Bean(destroyMethod = "")
    public RedisAgentStateStore nativeRedisAgentStateStore(RedisClientAdapter agentScopeStateClient) {
        return RedisAgentStateStore.builder().keyPrefix(AgentTurnStateStores.KEY_PREFIX)
                .clientAdapter(agentScopeStateClient).build();
    }

    @Bean
    public AgentTurnStateStores agentTurnStateStores(AgentScopeRedisBackend backend) {
        return new AgentTurnStateStores(backend.adapter, backend.clusterClient != null);
    }

    @Bean
    @Primary
    public AgentStateStore redisAgentStateStore(RedisAgentStateStore nativeRedisAgentStateStore) {
        return StrictAgentStateStore.readOnly(nativeRedisAgentStateStore);
    }

    private RedisURI standaloneUri(RedisProperties properties) {
        if (properties.getUrl() != null && !properties.getUrl().isBlank()) {
            return RedisURI.create(properties.getUrl());
        }
        RedisURI.Builder builder = RedisURI.Builder.redis(properties.getHost(), properties.getPort());
        applyCommon(builder, properties);
        return builder.build();
    }

    private RedisURI sentinelUri(RedisProperties properties) {
        RedisProperties.Sentinel sentinel = properties.getSentinel();
        if (sentinel.getNodes() == null || sentinel.getNodes().isEmpty()) {
            throw new IllegalStateException("Redis Sentinel nodes are required");
        }
        HostPort first = hostPort(sentinel.getNodes().get(0));
        RedisURI.Builder builder = sentinel.getPassword() == null || sentinel.getPassword().isBlank()
                ? RedisURI.Builder.sentinel(first.host(), first.port(), sentinel.getMaster())
                : RedisURI.Builder.sentinel(first.host(), first.port(), sentinel.getMaster(), sentinel.getPassword());
        sentinel.getNodes().stream().skip(1).map(this::hostPort)
                .forEach(node -> builder.withSentinel(node.host(), node.port()));
        applyCommon(builder, properties);
        return builder.build();
    }

    private RedisURI nodeUri(String node, RedisProperties properties) {
        HostPort address = hostPort(node);
        RedisURI.Builder builder = RedisURI.Builder.redis(address.host(), address.port());
        applyCommon(builder, properties);
        return builder.build();
    }

    private void applyCommon(RedisURI.Builder builder, RedisProperties properties) {
        builder.withDatabase(properties.getDatabase())
                .withSsl(properties.getSsl().isEnabled())
                .withTimeout(properties.getTimeout() == null ? Duration.ofSeconds(5) : properties.getTimeout());
        if (properties.getPassword() != null && !properties.getPassword().isBlank()) {
            if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
                builder.withAuthentication(properties.getUsername(), properties.getPassword());
            } else {
                builder.withPassword(properties.getPassword());
            }
        }
        if (properties.getClientName() != null && !properties.getClientName().isBlank()) {
            builder.withClientName(properties.getClientName());
        }
    }

    private HostPort hostPort(String node) {
        int separator = node.lastIndexOf(':');
        if (separator <= 0 || separator == node.length() - 1) {
            throw new IllegalStateException("Redis node must use host:port format");
        }
        String host = node.substring(0, separator).replace("[", "").replace("]", "");
        return new HostPort(host, Integer.parseInt(node.substring(separator + 1)));
    }

    private record HostPort(String host, int port) {
    }

    public static final class AgentScopeRedisBackend implements AutoCloseable {
        private final RedisClient client;
        private final RedisClusterClient clusterClient;
        private final RedisClientAdapter adapter;

        private AgentScopeRedisBackend(RedisClient client, RedisClusterClient clusterClient) {
            this.client = client;
            this.clusterClient = clusterClient;
            this.adapter = clusterClient == null ? LettuceClientAdapter.of(client) : LettuceClientAdapter.of(clusterClient);
        }

        static AgentScopeRedisBackend standalone(RedisClient client) {
            return new AgentScopeRedisBackend(client, null);
        }

        static AgentScopeRedisBackend cluster(RedisClusterClient client) {
            return new AgentScopeRedisBackend(null, client);
        }

        @Override
        public void close() {
            adapter.close();
        }
    }
}
