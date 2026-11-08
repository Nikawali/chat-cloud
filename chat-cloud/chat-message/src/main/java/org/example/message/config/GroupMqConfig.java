package org.example.message.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.*;

@Configuration

public class GroupMqConfig {
    public static final String EXCHANGE = "chat.group.events.v2";
    public static final String QUEUE = "chat.group.fanout.v2";
    public static final String ROUTING = "message.created";
    @Bean org.springframework.amqp.rabbit.retry.MessageRecoverer groupRetryRecoverer() {
        return new org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer();
    }
    @Bean org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("message-jobs-");
        return scheduler;
    }
    @Bean org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor groupPublishExecutor() {
        var executor=new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);executor.setMaxPoolSize(4);executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("group-publish-");return executor;
    }
    @Bean Declarables groupMqTopology() {
        var exchange = new DirectExchange(EXCHANGE,true,false);
        var queue = QueueBuilder.durable(QUEUE).quorum()
                .withArgument("x-delivery-limit",-1).build();
        return new Declarables(exchange,queue,BindingBuilder.bind(queue).to(exchange).with(ROUTING));
    }
}
