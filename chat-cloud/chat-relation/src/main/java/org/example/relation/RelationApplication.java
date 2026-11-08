package org.example.relation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 关系服务入口（好友 / 黑名单 / 分组）。
 *
 * <p><b>为什么好友关系要独立成一个服务，而不是塞进 chat-user？</b>
 * <ul>
 *   <li>职责不同：chat-user 管"你是谁"（身份），本服务管"你和别人是什么关系"。
 *       把关系表塞进用户服务会让它同时承担两件事，以后加黑名单/分组会越滚越大。</li>
 *   <li>读写模式不同：用户表写得少读得多但量小；关系表是<b>读极多、写不少、量级是用户数的平方</b>，
 *       将来要单独加缓存或分库分表时，独立服务才有发挥空间。</li>
 * </ul>
 *
 * <p><b>它和 chat-user 的关系</b>：本服务只存 userId，不存昵称头像。
 * 要展示用户资料时（好友列表要显示昵称/头像），用 Feign <b>批量</b>问 chat-user 要 ——
 * 绝不跨服务 JOIN，保住将来拆库的可能。
 *
 * <p>{@code @EnableFeignClients} 就是为了上面这件事打开的（见 client 包下的 UserClient）。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
public class RelationApplication {

    public static void main(String[] args) {
        SpringApplication.run(RelationApplication.class, args);
    }
}
