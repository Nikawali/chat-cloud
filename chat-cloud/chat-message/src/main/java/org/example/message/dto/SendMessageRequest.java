package org.example.message.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 发消息的请求体（前端 → chat-message）。
 *
 * <p>当前客户端通过 WS send-message 提交，连接服务转内部 HTTP 后复用本请求及原业务校验。
 * 受理结果通过 WS send-result 返回，用 clientMessageId 关联；公共 HTTP 发送接口保留兼容。
 *
 * @param toUserId        对方 userId（不是手机号）。单聊时由服务端负责"查找或创建会话"
 * @param content         消息内容
 * @param clientMessageId <b>客户端生成的唯一 id（UUID）</b>。
 *                        它的作用是<b>幂等</b>：客户端没收到受理 ack 时会重发同一条，
 *                        服务端靠它 + 数据库唯一索引保证只落一条，并且把已存在的那条返回回去。
 *                        ⚠️ 必须是全局唯一的 UUID，不能用"会话内第几条"（唯一索引是
 *                        (sender_user_id, client_message_id)，不含会话，会话内序号在第二个会话必然撞）。
 *                        ⚠️ 它<b>不是</b>"防重放攻击"的东西（那是登录那套 nonce 的活），别混。
 * @param messageType     消息类型，1=文本（默认）；以后 2=图片 3=文件 4=语音
 */
public record SendMessageRequest(

        @NotNull(message = "对方 userId 不能为空")
        Long toUserId,

        @NotBlank(message = "消息内容不能为空")
        @Size(max = 2000, message = "消息内容不能超过 2000 个字符")
        String content,

        @NotBlank(message = "clientMessageId 不能为空")
        @Size(max = 64, message = "clientMessageId 不能超过 64 个字符")
        String clientMessageId,

        Integer messageType
) {

    /** 消息类型默认值：文本 */
    public static final int TYPE_TEXT = 1;

    /** 取消息类型，没传就按文本处理 */
    public int typeOrDefault() {
        return messageType == null ? TYPE_TEXT : messageType;
    }
}
