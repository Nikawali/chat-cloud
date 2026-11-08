package org.example.relation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 发起好友申请的请求体（前端 → chat-relation）。
 *
 * <p><b>为什么只有 toUserId，没有 fromUserId？</b>
 * "我是谁"必须来自网关注入的 {@code X-User-Id}，绝不能由前端传 ——
 * 否则谁都能以别人的身份发好友申请（这就是"越权"最典型的入口）。
 *
 * <p>{@code toUserId} 是<b>对方的 userId</b>，不是手机号：
 * 手机号只是搜索用的"关键词"，在加好友页搜到人之后就已经换成了 userId。
 *
 * @param toUserId 对方的 userId（前端从搜索结果里拿到的那个）
 * @param message  验证消息，可以不填（对方看不到你的昵称时，这句话就是唯一线索）
 */
public record AddFriendRequest(

        @NotNull(message = "对方 userId 不能为空")
        Long toUserId,

        @Size(max = 100, message = "验证消息不能超过 100 个字符")
        String message
) {
}
