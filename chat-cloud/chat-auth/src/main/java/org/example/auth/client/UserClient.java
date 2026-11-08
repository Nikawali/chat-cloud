package org.example.auth.client;

import org.example.common.constant.ServiceNames;
import org.example.common.dto.UserCreateRequest;
import org.example.common.dto.UserDTO;
import org.example.common.dto.UserVerifyRequest;
import org.example.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 调用 chat-user。写的是服务名，不是 ip:port ——
 * Feign + LoadBalancer 会去 Nacos 查实例列表并做负载均衡。
 *
 * <p>注意路径都是 {@code /internal/user/**}：这些是 chat-user 的<b>内部接口</b>，
 * 网关没有为 {@code /internal/**} 配置路由，所以外部无法直接调用，
 * 避免有人绕过短信验证直接注册，或者拿 verify 接口暴力试密码。
 */
@FeignClient(name = ServiceNames.CHAT_USER)
public interface UserClient {

    /** 创建用户（注册用） */
    @PostMapping("/internal/user/create")
    Result<UserDTO> create(@RequestBody UserCreateRequest request);

    /**
     * 校验登录密码（登录用）。
     *
     * <p>传明文过去，chat-user 用 BCrypt.matches 比对后只返回 userId ——
     * <b>密码哈希不会离开 chat-user</b>。
     *
     * <p><b>为什么只回一个 userId？</b> 本服务拿到它只为签 token。
     * 用户资料的 Redis 缓存由 chat-user 自己在校验通过时顺手预热，
     * 也就是说缓存实现（字段名 / TTL / CAS）完全不出它的服务边界，
     * auth 这边一行缓存代码都不需要。
     */
    @PostMapping("/internal/user/verify")
    Result<Long> verify(@RequestBody UserVerifyRequest request);
}
