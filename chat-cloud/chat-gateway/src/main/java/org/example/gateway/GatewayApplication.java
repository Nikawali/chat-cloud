package org.example.gateway;

import org.example.gateway.config.AuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 网关入口。所有外部请求先到这里，再按路由转发到内部服务。
 *
 * <p>入口处还会做一次<b>鉴权</b>（见 {@code AuthGlobalFilter}）：
 * 校验 token 是否有效，并把解析出的 userId 以 {@code X-User-Id} 请求头
 * 转给下游服务。下游服务因此不需要各自连 Redis 验 token。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableConfigurationProperties(AuthProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
