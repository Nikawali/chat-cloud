package org.example.message.vo;

import java.time.LocalDateTime;

/**
 * 发消息的<b>受理结果</b>（当前客户端经 WS send-result 返回，兼容 HTTP 接口仍返回本结构）。
 *
 * <p>前端拿到它就做三件事：把界面上那条"发送中"改成"已发送"、
 * 把本地乐观插入的那条替换成服务端这条（换到真正的 messageId）、清空输入框。
 *
 * <p>{@code duplicated = true} 表示这次是<b>重复提交</b>（同一个 clientMessageId 之前已经发过了），
 * 返回的是<b>已经存在的那条消息</b>。这时前端应该当成功处理 ——
 * 重发本来就是客户端的正常行为（它没收到上一次的受理 ack），报错反而让人懵。
 * 这就是"幂等"该有的样子：同样的请求发一次和发十次，结果一样。
 *
 * @param messageId      服务端生成的消息 id（后续 ack / 已读上报都用它）
 * @param conversationId 落在哪个会话里
 * @param createdAt      服务端时间（客户端不要用本地时间当权威时间）
 * @param duplicated     是不是重复提交（返回的是已存在的那条）
 */
public record SendMessageVO(
        Long messageId,
        Long conversationId,
        LocalDateTime createdAt,
        boolean duplicated
) {
}
