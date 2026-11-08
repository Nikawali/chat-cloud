package org.example.message.job;
import org.example.message.service.delivery.DeliveryRetryRunner;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.example.message.entity.MessageEntity;
import org.example.message.entity.MessageReceiptEntity;
import org.example.message.mapper.MessageMapper;
import org.example.message.mapper.MessageReceiptMapper;
import org.example.message.service.delivery.DeliveryQueue;
import org.example.message.service.single.MessageVoAssembler;
import org.example.message.ws.MessagePusher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 送达重试任务：把"发出去了但对方没回 ack"的消息再推一次。
 *
 * <h3>它和 ack 的关系</h3>
 * <pre>
 *   发消息 → 推一次 → 登记待送达（score = 1 秒后）
 *      ├── 收到 ack → 出队，结束
 *      └── 没收到   → 本任务到点重推，间隔按指数退避（1s、2s、4s、8s、16s、30s…）
 *                    直到收到 ack，或者超过重试窗口（默认 3 分钟）
 * </pre>
 *
 * <h3>⚠️ "3 分钟后放弃"到底放弃了什么</h3>
 * 放弃的只是<b>实时推送</b>这条路径。<b>消息和收据都还在库里</b>
 * （{@code message_receipt.delivered_at} 仍是空），对方下次上线/重连时调
 * {@code GET /api/message/undelivered} 就会把它捞回去 —— 这才是"不丢消息"的兜底。
 * 所以这个任务里<b>没有任何"删除消息"的动作</b>。
 *
 * <h3>三种"不用重推"的情况，都直接出队</h3>
 * <ol>
 *   <li>收据已经 delivered_at 有值了（ack 到了，但出队那一步没成功）—— 自愈；</li>
 *   <li>消息不存在了（被删）—— 没什么可推的；</li>
 *   <li>对方不在线 —— 对着一个离线的人每 30 秒重试三分钟毫无意义，
 *       交给"上线补拉"更确定（客户端每次连上都会调它）。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryRetryJob {

    private final DeliveryQueue deliveryQueue;
    private final MessageMapper messageMapper;
    private final MessageReceiptMapper receiptMapper;
    private final MessageVoAssembler assembler;
    private final MessagePusher pusher;

    /** 超过这个时长就不再实时重推（交给上线补拉） */
    @Value("${chat.message.retry-window-seconds:180}")
    private long retryWindowSeconds;

    /** 一轮最多处理多少条 */
    @Value("${chat.message.retry-batch-size:200}")
    private int batchSize;

    /** 退避基准：第 1 次重试等 1 秒、第 2 次 2 秒、第 3 次 4 秒…… */
    @Value("${chat.message.retry-base-delay-ms:1000}")
    private long baseDelayMs;

    /** 退避上限：不管重试多少次，最多等 30 秒 */
    @Value("${chat.message.retry-max-delay-ms:30000}")
    private long maxDelayMs;

    @Value("${chat.message.retry-lease-ms:30000}")
    private long leaseMillis = 30000;

    /**
     * 定时扫"到期的待送达"。
     *
     * <p>用 {@code fixedDelay}（上一轮跑完再等 1 秒）而不是 {@code fixedRate}：
     * 万一某一轮处理得慢，fixedRate 会堆积重叠执行。
     */
    @Scheduled(fixedDelayString = "${chat.message.retry-scan-interval-ms:1000}")
    public void retryPendingDeliveries() {
        var result=DeliveryRetryRunner.run(deliveryQueue,batchSize,leaseMillis,
                claims -> {
                    var due=claims.stream().map(DeliveryQueue.ClaimedDelivery::pending).toList();
                    return new RetryBatch(prefetchReceipts(due),prefetchMessages(due));
                },
                (claim,batch) -> retryOne(claim,batch.receipts().get(pendingKey(claim.pending())),batch.messages().get(claim.pending().messageId())),
                (claims,e) -> log.error("批量预取待送达的收据/消息失败，本轮跳过: 到期={}",claims.size(),e),
                (claim,e) -> log.error("重推待送达消息出错: messageId={}, receiver={}",claim.pending().messageId(),claim.pending().receiverUserId(),e));
        if(result.claimed()>0) log.info("送达重试一轮: 到期={}, 已重推={}, 直接出队={}",result.claimed(),result.retried(),result.dropped());
    }
    private record RetryBatch(Map<String,MessageReceiptEntity> receipts,Map<Long,MessageEntity> messages) {}

    /** 一批里所有待送达的 messageId（去重后给 IN 查询用；一轮里同一个消息不会重复出现，但去重更稳） */
    private static List<Long> distinctMessageIds(List<DeliveryQueue.PendingDelivery> due) {
        return due.stream().map(DeliveryQueue.PendingDelivery::messageId).distinct().toList();
    }

    /** 收据在内存里的 key：和 Redis 队列 member 的形状一致（"messageId:receiverUserId"） */
    private static String pendingKey(DeliveryQueue.PendingDelivery pending) {
        return pending.messageId() + ":" + pending.receiverUserId();
    }

    /**
     * 一次查出这一批的收据。
     *
     * <p>用 {@code message_id IN (...)} 查，再按 (messageId, receiverUserId) 精确匹配：
     * 收据的主键是复合键，一条 IN 没法表达"这些 (a,b) 对"；而单聊里一条消息通常只有一行收据，
     * 多查出来的行在内存里按复合键精确匹配。
     */
    private Map<String, MessageReceiptEntity> prefetchReceipts(List<DeliveryQueue.PendingDelivery> due) {
        List<MessageReceiptEntity> rows = receiptMapper.selectList(
                Wrappers.<MessageReceiptEntity>lambdaQuery()
                        .in(MessageReceiptEntity::getMessageId, distinctMessageIds(due)));
        Map<String, MessageReceiptEntity> byKey = new HashMap<>(rows.size());
        for (MessageReceiptEntity receipt : rows) {
            byKey.put(receipt.getMessageId() + ":" + receipt.getReceiverUserId(), receipt);
        }
        return byKey;
    }

    /** 一次查出这一批的消息（{@code selectBatchIds} 就是一条 {@code where message_id in (...)}） */
    private Map<Long, MessageEntity> prefetchMessages(List<DeliveryQueue.PendingDelivery> due) {
        return messageMapper.selectBatchIds(distinctMessageIds(due)).stream()
                .collect(Collectors.toMap(MessageEntity::getMessageId, Function.identity(), (a, b) -> a));
    }

    /**
     * 处理一条。
     *
     * @param receipt 这一条对应的收据（批量预取的；查不到就是 null）
     * @param message 这一条对应的消息（批量预取的；查不到就是 null）
     * @return true = 又推了一次（还在等 ack）；false = 出队了（不用再推）
     */
    private boolean retryOne(DeliveryQueue.ClaimedDelivery claim,
                            MessageReceiptEntity receipt,
                            MessageEntity message) {
        if (!deliveryQueue.isClaimActive(claim)) return false;
        DeliveryQueue.PendingDelivery pending = claim.pending();
        Long messageId = pending.messageId();
        Long receiverUserId = pending.receiverUserId();

        // ① 收据已经送达（ack 到了，只是出队那步没成功）→ 自愈出队
        if (receipt == null || receipt.getDeliveredAt() != null) {
            deliveryQueue.removeClaimed(claim);
            return false;
        }

        // ② 消息没了（理论上不会）→ 出队
        //    （消息是批量预取来的，这里不再单查一次）
        if (message == null) {
            deliveryQueue.removeClaimed(claim);
            return false;
        }

        // ③ 超过重试窗口 → 放弃【实时推送】（不是放弃送达！消息还在库里，靠上线补拉）
        long ageMillis = Duration.between(message.getCreatedAt(), LocalDateTime.now()).toMillis();
        if (ageMillis > retryWindowSeconds * 1000) {
            deliveryQueue.removeClaimed(claim);
            log.info("超过 {} 秒仍未收到 ack，停止实时重推（消息保留，等对方上线补拉）: messageId={}, receiver={}",
                    retryWindowSeconds, messageId, receiverUserId);
            return false;
        }

        // ④ 对方不在线 → 出队，交给上线补拉。
        //    这一点是刻意的：对离线的人死磕重试没有意义，而"连上之后先补拉"是客户端一定会做的事。
        if (!pusher.isOnline(receiverUserId)) {
            deliveryQueue.removeClaimed(claim);
            return false;
        }

        // Always attempt token-checked rescheduling after push, including exceptions.
        // ACK or a newer claim fences this finally; expired claims recover via their score.
        return DeliveryRetryRunner.pushAndReschedule(deliveryQueue,claim,backoffMillis(claim.attempt()),
                () -> pusher.pushMessage(receiverUserId,assembler.assembleOne(receiverUserId,message)));
    }

    /**
     * 指数退避：第 1 次 1s、第 2 次 2s、第 3 次 4s、第 4 次 8s、第 5 次 16s、之后 30s 封顶。
     *
     * <p>为什么不用固定 5 秒：未确认消息多的时候，固定间隔等于"每 5 秒把所有未确认的都重推一遍"，
     * 对方网络一抖就是一次小风暴。退避让"大概率已经失败"的那些消息推得越来越稀疏。
     */
    private long backoffMillis(int attempt) {
        if (attempt <= 1) {
            return baseDelayMs;
        }
        // 用位移算 2 的幂，但先挡住溢出（attempt 很大时位移会绕回去）
        int shift = Math.min(attempt - 1, 20);
        long delay = baseDelayMs << shift;
        return Math.min(delay, maxDelayMs);
    }
}
