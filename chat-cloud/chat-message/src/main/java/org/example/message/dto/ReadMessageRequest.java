package org.example.message.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 上报已读的请求体。
 *
 * <p>前端在"打开会话 / 滚到底部 / 收到新消息且窗口是激活的"时候调它。
 *
 * <p><b>为什么要传 lastReadMessageId，而不是"全部标记已读"？</b>
 * 因为用户可能只滚到中间就退出去了 —— 传"我读到哪条"才准确。
 * 服务端会取 {@code max(已有值, 这次传的值)}，所以乱序上报（先报大的后报小的）也不会倒退。
 *
 * @param peerUserId        单聊对方的 userId（服务端据此定位会话）
 * @param lastReadMessageId 我读到哪条消息了
 */
public record ReadMessageRequest(

        @NotNull(message = "对方 userId 不能为空")
        Long peerUserId,

        @NotNull(message = "lastReadMessageId 不能为空")
        Long lastReadMessageId
) {
}
