package org.example.message.service.single;

import java.util.List;
import org.example.message.entity.MessageEntity;
import org.example.message.entity.MessageReceiptEntity;
import org.example.message.mapper.MessageReceiptMapper;
import org.example.message.service.delivery.DeliveryQueue;
import org.example.message.ws.MessagePusher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 单聊首次投递和重复提交补登记；Redis重试状态不替代数据库收据。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SingleMessageDelivery {
    private final MessageReceiptMapper receiptMapper;
    private final DeliveryQueue deliveryQueue;
    private final MessagePusher pusher;
    private final MessageVoAssembler assembler;
    @Value("${chat.message.retry-base-delay-ms:1000}") private long retryBaseDelayMs;

    public void deliver(MessageEntity message, Long toUserId) {
        deliveryQueue.enqueue(message.getMessageId(), toUserId, retryBaseDelayMs);

        if (!pusher.isOnline(toUserId)) {
            log.info("对方不在线，消息留在库里等上线补拉: messageId={}, receiver={}",
                    message.getMessageId(), toUserId);
            return;
        }
        boolean pushed = pusher.pushMessage(toUserId, assembler.assembleOne(toUserId, message));
        log.info("消息已推送: messageId={}, receiver={}, 成功={}",
                message.getMessageId(), toUserId, pushed);
    }

    public void repairDeliveryIfNeeded(MessageEntity message) {
        try {
            // 单聊收据的delivered_at为空时补登记投递，重复查询和投递均允许幂等重试。
            List<MessageReceiptEntity> receipts = receiptMapper.selectList(
                    Wrappers.<MessageReceiptEntity>lambdaQuery()
                            .eq(MessageReceiptEntity::getMessageId, message.getMessageId()));
            for (MessageReceiptEntity receipt : receipts) {
                if (receipt.getDeliveredAt() != null) {
                    continue;   // 已经送达了，没什么可补的
                }
                log.info("重复提交 → 补登记投递: messageId={}, receiver={}",
                        message.getMessageId(), receipt.getReceiverUserId());
                deliver(message, receipt.getReceiverUserId());
            }
        } catch (Exception e) {
            log.warn("补登记投递失败（不影响本次重复提交的返回）: messageId={}",
                    message.getMessageId(), e);
        }
    }
}
