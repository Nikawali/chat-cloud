package org.example.message;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 消息服务入口。
 *
 * <p>{@code @EnableFeignClients}：调用 chat-user（取昵称头像）和 chat-relation（校验好友关系）。
 *
 * <p>{@code @EnableScheduling}：<b>送达重试任务</b>要用 —— 它每秒扫一次
 * Redis 里的"待送达队列"，把没收到 ack 的消息按指数退避重推。
 * （不打开这个注解的话，{@code @Scheduled} 方法会安安静静地永远不执行，
 * 那种"代码看着都在、就是没跑"的问题最难查。）
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@EnableScheduling
public class MessageApplication {

    public static void main(String[] args) {
        SpringApplication.run(MessageApplication.class, args);
    }
}
