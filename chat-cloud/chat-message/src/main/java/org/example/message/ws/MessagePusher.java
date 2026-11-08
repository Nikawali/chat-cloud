package org.example.message.ws;

import org.example.message.vo.MessageVO;

/**
 * 消息业务的投递出口。当前实现通过内部 HTTP 调用 chat-connection，
 * 根据 Redis 连接归属和 Nacos 实例元数据定位目标实例及会话，再发送 WS 帧。
 *
 * <p>返回 false 表示未在线或本次调用失败，不影响消息已落库；后续重试及补拉继续兜底。
 * 路由切换时有限重试，消息是否最终送达仍由客户端 ACK 决定。
 */
public interface MessagePusher {

    /** 是否值得尝试投递：无路由为 false；路由查询故障保守返回 true，避免删除待重推任务。 */
    boolean isOnline(Long userId);

    /**
     * 推一条新消息。
     *
     * @return true = 发出去了；false = 对方不在线或发送失败
     */
    boolean pushMessage(Long userId, MessageVO message);

    /**
     * 通知发送方"你的消息已送达"（界面上从单勾变双勾）。
     *
     * @param upToMessageId 这个会话里<b>已送达到的最大 messageId</b>。
     *                      用"送到哪条了"而不是"哪几条"，是因为客户端渲染的是
     *                      "我在这段对话里发出去的消息，到某条为止都送达了"。
     */
    boolean pushDelivered(Long userId, Long conversationId, Long upToMessageId);

    /**
     * 通知发送方"对方已读"（双勾变蓝 / 显示已读）。
     *
     * @param upToMessageId 已读到的最大 messageId（同样用"读到哪了"表达）
     */
    boolean pushRead(Long userId, Long conversationId, Long upToMessageId);
}
