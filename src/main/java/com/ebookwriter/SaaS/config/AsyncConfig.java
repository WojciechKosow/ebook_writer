package com.ebookwriter.SaaS.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Executor for background ebook generation. A small bounded pool: each job is
 * long-running and mostly waits on the Anthropic API, so a handful of
 * concurrent generations is plenty for V0.1.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "ebookExecutor")
    public Executor ebookExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ebook-gen-");
        executor.initialize();
        return executor;
    }

    /**
     * Executor for knowledge processing (reading the author's materials with
     * OpenAI). Separate from {@link #ebookExecutor} so a queue of long book
     * generations never delays the much shorter knowledge runs, and vice versa.
     */
    @Bean(name = "knowledgeExecutor")
    public Executor knowledgeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("knowledge-");
        executor.initialize();
        return executor;
    }
}
