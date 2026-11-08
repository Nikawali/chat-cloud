package org.example.message.service.single;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.example.message.dto.ReadMessageRequest;
import org.example.message.entity.ConversationEntity;
import org.example.message.entity.MessageEntity;
import org.example.message.entity.MessageReceiptEntity;
import org.example.message.mapper.MessageMapper;
import org.example.message.mapper.MessageReceiptMapper;
import org.example.message.service.delivery.DeliveryQueue;
import org.example.message.ws.MessagePusher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 单聊送达和已读收据：先提交数据库事实，再清理队列并推送回执。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SingleMessageReceipt {
    private static final int DELIVERY_DONE=1;
    private final MessageMapper messageMapper;
    private final MessageReceiptMapper receiptMapper;
    private final ConversationService conversationService;
    private final DeliveryQueue deliveryQueue;
    private final MessagePusher pusher;
    private final TransactionTemplate transactionTemplate;

    public void markDelivered(Long myUserId, List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        List<Long> ids = messageIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        // ① 幂等写"已送达"：只更新还没记过送达时间的行。
        //    重复的 ack（客户端重发、断线重连后重放）不会把时间覆盖成更晚的 ——
        //    和 markRead 里"只前进不后退"是同一套思路。
        Integer updated = transactionTemplate.execute(status -> receiptMapper.update(null,
                Wrappers.<MessageReceiptEntity>lambdaUpdate()
                        // 收据的主键是 (messageId, receiverUserId)，但这里只按接收者过滤，
                        // 所以【必须】同时限定 messageId 在收到的 ack 列表里，否则会误标别人的消息
                        .eq(MessageReceiptEntity::getReceiverUserId, myUserId)
                        .in(MessageReceiptEntity::getMessageId, ids)
                        .isNull(MessageReceiptEntity::getDeliveredAt)
                        .set(MessageReceiptEntity::getDeliveredAt, now)
                        .set(MessageReceiptEntity::getDeliveryStatus, DELIVERY_DONE)));

        // ② 出队：这条消息不用再重推了。
        //    Redis 出队失败也不影响正确性 —— 重试任务会看到 delivered_at 已经有值，自己把它清掉。
        deliveryQueue.removeAll(ids, myUserId);

        // ③ 回推发送方"已送达"（对方界面上的单勾要变成双勾）。
        //    这一步是"通知"，失败绝不能影响上面的落库结果，所以整段吞异常。
        try {
            notifySendersDelivered(myUserId, ids);
        } catch (Exception e) {
            log.warn("回推送达回执失败（不影响已送达状态）: receiver={}, messageIds={}", myUserId, ids, e);
        }

        log.info("收到送达 ack: receiver={}, 条数={}, 本次新标记={}", myUserId, ids.size(), updated);
    }

    private void notifySendersDelivered(Long receiverUserId, List<Long> messageIds) {
        List<MessageEntity> messages = messageMapper.selectBatchIds(messageIds);
        if (messages.isEmpty()) {
            return;
        }
        // conversationId → senderUserId → 这一批里最大的 messageId
        Map<Long, Map<Long, Long>> maxByConversationAndSender = new HashMap<>();
        for (MessageEntity message : messages) {
            if (receiverUserId.equals(message.getSenderUserId())) {
                continue;   // 自己发给自己的情况不存在（send 已经拦了），防一手
            }
            maxByConversationAndSender
                    .computeIfAbsent(message.getConversationId(), key -> new HashMap<>())
                    .merge(message.getSenderUserId(), message.getMessageId(), Math::max);
        }

        maxByConversationAndSender.forEach((conversationId, bySender) ->
                bySender.forEach((senderUserId, maxMessageId) -> {
                    boolean pushed = pusher.pushDelivered(senderUserId, conversationId, maxMessageId);
                    if (pushed) {
                        log.debug("已回推送达回执: sender={}, conversationId={}, upTo={}",
                                senderUserId, conversationId, maxMessageId);
                    }
                }));
    }

    public void markRead(Long myUserId, ReadMessageRequest req) {
        ConversationEntity conversation = conversationService.findSingle(myUserId, req.peerUserId());
        if (conversation == null) {
            // 没有会话就没什么可标的。不报错：用户可能只是点进资料页又退出来了
            return;
        }
        conversationService.requireMember(conversation.getConversationId(), myUserId);

        LocalDateTime now = LocalDateTime.now();
        transactionTemplate.executeWithoutResult(status -> {
            // ① 我的读游标（未读数靠它算），只前进不后退
            conversationService.advanceReadCursor(
                    conversation.getConversationId(), myUserId, req.lastReadMessageId());

            // ② 把"已读"写进收据 —— 发送方界面上的双勾就来自这里。
            //    只更新还没记过已读时间的那些行（read_at is null），重复上报不会覆盖成更晚的时间。
            //    注意这里没有 join message 表：收据上有冗余的 conversation_id（当初加它就是为了这一刻）
            receiptMapper.update(null, Wrappers.<MessageReceiptEntity>lambdaUpdate()
                    .eq(MessageReceiptEntity::getReceiverUserId, myUserId)
                    .eq(MessageReceiptEntity::getConversationId, conversation.getConversationId())
                    .le(MessageReceiptEntity::getMessageId, req.lastReadMessageId())
                    .isNull(MessageReceiptEntity::getReadAt)
                    .set(MessageReceiptEntity::getReadAt, now));

            // ③ 【自愈】已读必然意味着已送达 —— 顺手把 delivered_at 补上。
            //
            //    为什么必须有这一步：正常顺序是"送达 ack → delivered_at → 后来才已读"，
            //    但 ack 是可能丢的（网络抖动、客户端崩了、断线期间只完成了读上报）。
            //    不补的话数据库里会出现"read_at 有值、delivered_at 为空"——
            //    一个自相矛盾的状态，发送方看到的是"已读但未送达"，排查时能把人绕晕。
            //    补上之后这个状态机就是单调的：delivered_at <= read_at 永远成立。
            receiptMapper.update(null, Wrappers.<MessageReceiptEntity>lambdaUpdate()
                    .eq(MessageReceiptEntity::getReceiverUserId, myUserId)
                    .eq(MessageReceiptEntity::getConversationId, conversation.getConversationId())
                    .le(MessageReceiptEntity::getMessageId, req.lastReadMessageId())
                    .isNull(MessageReceiptEntity::getDeliveredAt)
                    .set(MessageReceiptEntity::getDeliveredAt, now)
                    .set(MessageReceiptEntity::getDeliveryStatus, DELIVERY_DONE));
        });

        // ④ 通知发送方"对方已读"（事务之后做网络 IO）。
        //    同样整段吞异常：已读的事实已经落库了，推不出去只是勾晚一点变蓝。
        try {
            notifySendersRead(myUserId, conversation.getConversationId(), req.lastReadMessageId());
        } catch (Exception e) {
            log.warn("回推已读回执失败（不影响已读状态）: reader={}, conversationId={}",
                    myUserId, conversation.getConversationId(), e);
        }
    }

    private void notifySendersRead(Long myUserId, Long conversationId, Long upToMessageId) {
        List<MessageMapper.SenderMaxMessageId> rows =
                messageMapper.selectMaxMessageIdBySender(conversationId, upToMessageId, myUserId);
        for (MessageMapper.SenderMaxMessageId row : rows) {
            boolean pushed = pusher.pushRead(row.senderUserId(), conversationId, row.maxMessageId());
            if (pushed) {
                log.debug("已回推已读回执: sender={}, conversationId={}, upTo={}",
                        row.senderUserId(), conversationId, row.maxMessageId());
            }
        }
    }
}
