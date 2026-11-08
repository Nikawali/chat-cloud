package org.example.message.client;

import java.util.List;

import org.example.common.constant.ServiceNames;
import org.example.common.dto.UserBatchRequest;
import org.example.common.dto.UserProfileDTO;
import org.example.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 调用用户服务。写的是服务名 chat-user，不是 ip:port ——
 * Feign + LoadBalancer 会去 Nacos 查实例列表并做负载均衡。
 *
 * <p><b>路径用 {@code /internal/user/**}</b>：
 * 这是 chat-user 的内部接口，网关没有为 {@code /internal/**} 配路由，外部打不到。
 *
 * <p><b>为什么不直接调对外的 {@code /user/{id}}？</b>
 * 以前这里调的是对外接口，有两个隐患：
 * <ol>
 *   <li><b>契约不一致</b>：对外接口返回的是 {@code UserProfileDTO}，
 *       而这里当时声明的是 {@code UserDTO}，能跑只是因为 Spring Boot 默认
 *       关掉了"未知字段报错"。一旦有人改了 Jackson 配置，服务间调用就会突然炸。</li>
 *   <li><b>耦合</b>：对外接口是给前端设计的，以后给它加限流/权限校验，
 *       会连带把内部调用一起卡死。</li>
 * </ol>
 *
 * <p>返回完整资料（而不是只有 id + 用户名）是因为消息列表要显示<b>昵称和头像</b>。
 * 走的是 chat-user 的 {@code getUserById}，所以<b>也享受 Redis 缓存</b>。
 *
 * <p><b>注意：内部接口和对外的接口返回的形状故意不一样。</b>
 * 这里返回 {@code UserProfileDTO}（带 {@code updatedAt} 缓存版本号，chat-common 里的共享契约）；
 * 对外的 {@code /user/{id}} 返回 chat-user 的 {@code UserDetailVO}（不带版本号，也不带 phone）。
 * 服务之间不需要"给前端看的展示形状"，硬要统一就得把 VO 挪进 chat-common，
 * 那会让所有服务都被迫依赖 chat-user 的展示细节 —— 不值得。
 */
@FeignClient(name = ServiceNames.CHAT_USER)
public interface UserClient {

    @GetMapping("/internal/user/{id}")
    Result<UserProfileDTO> getById(@PathVariable("id") Long id);

    /**
     * <b>批量</b>查用户资料 —— 拼消息列表时用它。
     *
     * <p>一条消息要显示发送者的昵称/头像。翻 50 条历史消息如果是 50 次 Feign 调用，
     * 那就是典型的 N+1（而且只有两个发送者，绝大多数是白问）。
     * 先把 sendingUserIds 去重再批量查，一个会话通常只有 2 个 id。
     */
    @PostMapping("/internal/user/batch")
    Result<List<UserProfileDTO>> batch(@RequestBody UserBatchRequest request);
}
