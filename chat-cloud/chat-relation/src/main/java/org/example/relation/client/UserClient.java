package org.example.relation.client;

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
 * 调用 chat-user 取用户资料。写服务名而不是 ip:port —— Feign + LoadBalancer 去 Nacos 解析。
 *
 * <p>路径都是 {@code /internal/user/**}：那是 chat-user 的<b>内部接口</b>，
 * 网关没有为 {@code /internal/**} 配路由，外部打不到。
 *
 * <p><b>为什么用内部接口，不用对外的 {@code /api/user/{id}}？</b>
 * <ol>
 *   <li>对外接口的契约是给前端设计的（返回 {@code UserDetailVO}），
 *       服务间调用需要的是内部 {@code UserProfileDTO}，两者形状不一样，
 *       混用早晚会"改了一边另一边悄悄坏掉"。</li>
 *   <li>耦合：以后给对外接口加限流/权限，会连带把内部调用卡死。</li>
 *   <li>走网关要多绕一跳（自己 → 网关 → chat-user），白花时间。</li>
 * </ol>
 */
@FeignClient(name = ServiceNames.CHAT_USER)
public interface UserClient {

    /** 单个查（发起申请前确认对方存在，用得上） */
    @GetMapping("/internal/user/{id}")
    Result<UserProfileDTO> getById(@PathVariable("id") Long id);

    /**
     * <b>批量</b>查，拼好友列表用。
     *
     * <p>好友列表里只有 userId，要显示昵称头像必须问 chat-user。
     * 一个个问是 N+1（20 个好友 = 20 次往返），所以必须走批量接口。
     */
    @PostMapping("/internal/user/batch")
    Result<List<UserProfileDTO>> batch(@RequestBody UserBatchRequest request);
}
