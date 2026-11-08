package org.example.connection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 长连接接入服务入口：维护 WS 会话，转交消息收据，定时清理空闲连接。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@EnableScheduling
public class ConnectionApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConnectionApplication.class, args);
    }
}
