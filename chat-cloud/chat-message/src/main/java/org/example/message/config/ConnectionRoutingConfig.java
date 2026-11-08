package org.example.message.config;

import org.example.common.routing.ConnectionTargetResolver;
import org.example.common.routing.RedisConnectionRoutes;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class ConnectionRoutingConfig {
    @Bean
    RedisConnectionRoutes connectionRoutes(StringRedisTemplate redis) { return new RedisConnectionRoutes(redis); }

    @Bean
    ConnectionTargetResolver connectionTargets(DiscoveryClient discovery) { return new ConnectionTargetResolver(discovery); }
}
