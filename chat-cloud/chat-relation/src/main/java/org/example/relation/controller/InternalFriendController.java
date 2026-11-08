package org.example.relation.controller;

import org.example.common.result.Result;
import org.example.relation.service.FriendService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 【内部接口】只给其他服务通过 Feign 调用，不对公网开放。
 *
 * <p>路径故意用 {@code /internal/**}：网关只配了 {@code /api/relation/**} 的路由，
 * 外部请求打不到 {@code /internal/**}。
 *
 * <p><b>为什么服务间调用不走对外的 {@code /api/relation/**}？</b>
 * <ol>
 *   <li>对外接口要经过网关，而网关会要求 {@code Authorization} 头（token）——
 *       服务间调用手里没有用户的 token，只有 userId。</li>
 *   <li>对外接口是给前端设计的，以后给它加限流/权限会连带把内部调用卡死。</li>
 *   <li>语义清楚：看到 {@code /internal/} 就知道"这个不该暴露到公网"。</li>
 * </ol>
 */
@RestController
@RequestMapping("/internal/relation/friend")
@RequiredArgsConstructor
public class InternalFriendController {

    private final FriendService friendService;

    /**
     * 这两个人是不是好友 —— chat-message 发消息前用它校验"只能给好友发消息"。
     *
     * <p>返回值是布尔而不是"五种关系状态"：调用方只关心能不能发，
     * 把"我已申请/对方申请我"这些页面语义塞给别的服务没有意义。
     *
     * @return true = 是好友（双向都成立）
     */
    @GetMapping("/check")
    public Result<Boolean> check(
            @RequestParam("userId") Long userId,
            @RequestParam("otherUserId") Long otherUserId) {
        return Result.success(friendService.areFriends(userId, otherUserId));
    }
}
