package org.example.message.client;

import org.example.common.constant.ServiceNames;
import org.example.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 调用 chat-relation（关系服务）。
 *
 * <p>本服务需要它做<b>发消息前的校验</b>："只能给好友发消息"。
 * 这条规则属于关系域，chat-message 自己判断不了（它不存好友关系）。
 *
 * <p>走 {@code /internal/relation/**}：那是 chat-relation 的内部接口，
 * 网关没有为 {@code /internal/**} 配路由，外部打不到；
 * 而且服务间调用手里没有用户 token，走不了对外的 {@code /api/relation/**}（要过网关鉴权）。
 */
@FeignClient(name = ServiceNames.CHAT_RELATION)
public interface RelationClient {

    /**
     * 这两个人是不是好友。
     *
     * <p>注意 Feign 的参数名要显式写出来（{@code @RequestParam("userId")}）——
     * 编译后方法参数名会丢，不写名字 Feign 就拼不出 URL。
     */
    @GetMapping("/internal/relation/friend/check")
    Result<Boolean> areFriends(@RequestParam("userId") Long userId,
                              @RequestParam("otherUserId") Long otherUserId);
}
