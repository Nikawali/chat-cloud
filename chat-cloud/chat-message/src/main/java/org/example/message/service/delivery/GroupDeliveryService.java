package org.example.message.service.delivery;
import org.example.message.vo.GroupMessageVO;

import java.util.List;
import java.util.Map;
import org.example.common.protocol.ChatFrames;
import org.example.message.entity.GroupMessageEntity;
import org.example.message.mapper.GroupMessageMapper;
import org.example.message.ws.RemoteMessagePusher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Online delivery only. Redis state is transient; no per-member database receipt rows. */
@Service
public class GroupDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(GroupDeliveryService.class);
    private final DeliveryQueue queue;
    private final GroupMessageMapper messages;
    private final RemoteMessagePusher pusher;
    @Value("${chat.group.retry-max-attempts:8}") private int maxAttempts = 8;
    public GroupDeliveryService(StringRedisTemplate redis, GroupMessageMapper messages, RemoteMessagePusher pusher) {
        this.queue = new DeliveryQueue(redis,List.of("chat:{group-delivery}:due","chat:{group-delivery}:attempt","chat:{group-delivery}:claim"));
        this.messages=messages; this.pusher=pusher;
    }

    public void acknowledge(Long groupId,Long userId,long after,long upTo) {
        while(after<upTo) {
            var ids=messages.receiptIds(groupId,after,upTo);
            if(ids.isEmpty()) return;
            queue.removeAll(ids.stream().map(org.example.message.mapper.GroupMessageMapper.ReceiptPosition::messageId).toList(),userId);
            after=ids.get(ids.size()-1).seq();
        }
    }
    public void fanout(long messageId) {
        var message = messages.selectById(messageId);
        if (message == null) throw new IllegalStateException("Group message missing: " + messageId);
        long after = 0;
        for (;;) {
            var recipients = messages.recipients(message.getGroupId(),message.getSeq(),after);
            if (recipients.isEmpty()) return;
            var routes=pusher.onlineRoutes(recipients);
            if(!routes.isEmpty()) {
                // recipients() is the membership/visibility snapshot for this page.
                // Changes after enumeration are allowed for this in-flight delivery.
                var online=recipients.stream().filter(routes::containsKey).toList();
                // Register before any WS write. Duplicate registrations preserve leases.
                var added=queue.enqueueBatch(messageId,online,1000);
                if(!added.isEmpty()) {
                    var selected=new java.util.LinkedHashMap<Long,org.example.common.dto.ConnectionRoute>();
                    for(var user:added) selected.put(user,routes.get(user));
                    pusher.pushBatch(selected,ChatFrames.of("group-message","data",groupFrame(message)));
                }
            }
            after=recipients.get(recipients.size()-1);
        }
    }

    @Scheduled(fixedDelayString="${chat.group.retry-scan-ms:1000}")
    public void retry() {
        DeliveryRetryRunner.run(queue,100,30000,
                claims -> messages.selectBatchIds(claims.stream().map(c->c.pending().messageId()).distinct().toList())
                        .stream().collect(java.util.stream.Collectors.toMap(GroupMessageEntity::getMessageId,m->m)),
                this::retryOne,
                (claims,e) -> log.warn("Group retry prefetch failed; leases will recover",e),
                (claim,e) -> log.warn("Group retry failed; lease will recover",e));
    }
    private boolean retryOne(DeliveryQueue.ClaimedDelivery claim,Map<Long,GroupMessageEntity> rows) {
        if (!queue.isClaimActive(claim)) return false;
        var message=rows.get(claim.pending().messageId());
        Long user=claim.pending().receiverUserId();
        if (message==null || claim.attempt()>maxAttempts || !pusher.isOnline(user)
                || messages.eligible(message.getGroupId(),message.getSeq(),user)==0
                || messages.delivered(message.getGroupId(),message.getSeq(),user)>0) {
            queue.removeClaimed(claim);return false;
        }
        return DeliveryRetryRunner.pushAndReschedule(queue,claim,
                Math.min(30000L,1000L << Math.min(20,claim.attempt())),()->{push(user,message);return true;});
    }
    private static Map<String,Object> groupFrame(GroupMessageEntity m) {
        var v=GroupMessageVO.of(m);
        return ChatFrames.of("group","messageId",v.messageId(),"groupId",v.groupId(),"seq",v.seq(),
                "senderUserId",v.senderUserId(),"clientMessageId",v.clientMessageId(),"content",v.content(),"messageType",v.messageType(),"createdAt",v.createdAt());
    }
    private void push(Long user,GroupMessageEntity m) {
        boolean sent=pusher.push(user,ChatFrames.of("group-message","data",groupFrame(m)));
        if (!sent) log.debug("Group WS push failed; Redis retry retained: message={}, user={}",m.getMessageId(),user);
    }
}
