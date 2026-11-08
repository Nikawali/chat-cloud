package org.example.message.config;

import org.apache.hc.core5.util.TimeValue;
import org.springframework.cloud.openfeign.clientconfig.HttpClient5FeignConfiguration.HttpClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FeignHttpClientConfig {
    @Bean
    HttpClientBuilderCustomizer feignHttpClientCustomizer() {
        // Retry remains controlled by the existing message/receipt protocol.
        return builder -> builder.disableAutomaticRetries().evictIdleConnections(TimeValue.ofSeconds(30));
    }
}
