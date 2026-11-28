package com.grash.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * P-23 (E-2): the executor of mail, notifications and webhooks, apart from the one of exports and imports
 * ({@link AsyncConfig}), so that a slow external service does not delay them. When it is full, a send is dropped and
 * recorded by {@code ExternalSendService}; it never fails the operation that asked for it.
 */
@Configuration
public class ExternalSendConfig {

    public static final String EXECUTOR = "externalSendExecutor";

    @Bean(name = EXECUTOR)
    public ThreadPoolTaskExecutor externalSendExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ExternalSend-");
        return executor;
    }
}
