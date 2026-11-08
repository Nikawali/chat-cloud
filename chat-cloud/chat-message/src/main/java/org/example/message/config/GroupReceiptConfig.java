package org.example.message.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class GroupReceiptConfig {
    @Bean
    public ThreadPoolTaskExecutor groupReceiptExecutor() {
        var executor=new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);executor.setMaxPoolSize(4);executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("group-receipt-");
        // Never run remote notification calls on the receipt request thread when saturated.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
