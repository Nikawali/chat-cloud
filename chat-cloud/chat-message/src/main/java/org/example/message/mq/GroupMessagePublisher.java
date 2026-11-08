package org.example.message.mq;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.example.message.config.GroupMqConfig;
import java.util.concurrent.TimeUnit;
@Service
@Slf4j
public class GroupMessagePublisher {
 private final RabbitTemplate rabbit;
 private final ThreadPoolTaskExecutor executor;
 @Value("${chat.group.mq-enabled:true}") private boolean enabled=true;
 public GroupMessagePublisher(RabbitTemplate rabbit,@Qualifier("groupPublishExecutor") ThreadPoolTaskExecutor executor) {this.rabbit=rabbit;this.executor=executor;}
 /** Only called after the message transaction commits. Best effort; HTTP sync repairs failure. */
 public void publish(long messageId) {
  if(!enabled) return;
  try {executor.execute(()->deliver(messageId));}
  catch(RuntimeException e) {log.warn("Group publish executor unavailable; client sync will recover: message={}",messageId);}
 }
 void deliver(long id) {
  for(int attempt=0;attempt<3;attempt++) try {
   if(attempt>0) Thread.sleep(250L << attempt);
   var correlation=new CorrelationData(java.util.UUID.randomUUID().toString());
   var message=MessageBuilder.withBody(Long.toString(id).getBytes(java.nio.charset.StandardCharsets.UTF_8)).setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN).setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
   rabbit.send(GroupMqConfig.EXCHANGE,GroupMqConfig.ROUTING,message,correlation);
   var confirm=correlation.getFuture().get(3,TimeUnit.SECONDS);
   if(!confirm.isAck() || correlation.getReturned()!=null) throw new IllegalStateException("MQ publish unconfirmed");
   return;
  } catch(Exception e) {
   if(e instanceof InterruptedException) {Thread.currentThread().interrupt();return;}
   if(attempt==2) log.warn("Group MQ publish exhausted; client sync will recover: message={}",id,e);
  }
 }
}
