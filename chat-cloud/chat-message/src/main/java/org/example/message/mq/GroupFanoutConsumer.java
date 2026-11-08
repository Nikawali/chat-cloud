package org.example.message.mq;
import org.example.message.service.delivery.GroupDeliveryService;


import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.example.message.config.GroupMqConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="chat.group.mq-enabled",havingValue="true",matchIfMissing=true)
public class GroupFanoutConsumer {
    private final GroupDeliveryService delivery;
    private final StringRedisTemplate redis;

    @RabbitListener(queues=GroupMqConfig.QUEUE)
    public void consume(String body) {
        final long id;
        try { id=Long.parseLong(body); if(id<=0) throw new NumberFormatException(); }
        catch (NumberFormatException e) {
            log.error("Malformed group MQ event ID rejected");
            throw new AmqpRejectAndDontRequeueException("Invalid group event ID");
        }
        String key="chat:group-fanout:v2:done:"+id;
        if (Boolean.TRUE.equals(redis.hasKey(key))) return;
        // Listener retries failures with backoff; exhausted deliveries are rejected. HTTP pull repairs loss.
        delivery.fanout(id);
        redis.opsForValue().set(key,"1",Duration.ofDays(7));
    }
}
