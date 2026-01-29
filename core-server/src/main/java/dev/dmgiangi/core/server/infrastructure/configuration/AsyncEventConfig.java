package dev.dmgiangi.core.server.infrastructure.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Configuration for asynchronous event processing.
 *
 * <p>Per Phase 0.6 decision:
 * <ul>
 *   <li>Dedicated thread pool (4-8 threads)</li>
 *   <li>CallerRunsPolicy for overflow (backpressure)</li>
 *   <li>Async event listeners for non-blocking processing</li>
 * </ul>
 *
 * <p>CallerRunsPolicy ensures that when the queue is full, the calling thread
 * executes the task. This provides natural backpressure - if event processing
 * can't keep up, the producer (AMQP listener) slows down.
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncEventConfig implements AsyncConfigurer {

    private static final int CORE_POOL_SIZE = 4;
    private static final int MAX_POOL_SIZE = 8;
    private static final int QUEUE_CAPACITY = 100;
    private static final String THREAD_NAME_PREFIX = "event-async-";

    /**
     * Creates the default async executor for @Async methods.
     *
     * <p>Thread pool configuration:
     * <ul>
     *   <li>Core size: 4 threads (always available)</li>
     *   <li>Max size: 8 threads (scales up under load)</li>
     *   <li>Queue capacity: 100 tasks (buffer before scaling)</li>
     *   <li>Rejection policy: CallerRunsPolicy (backpressure)</li>
     * </ul>
     */
    @Override
    @Bean(name = "asyncEventExecutor")
    public Executor getAsyncExecutor() {
        final var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        log.info("Async event executor initialized: corePoolSize={}, maxPoolSize={}, queueCapacity={}",
                CORE_POOL_SIZE, MAX_POOL_SIZE, QUEUE_CAPACITY);

        return executor;
    }

    /**
     * Custom exception handler for uncaught exceptions in async methods.
     * Logs the exception with full context for debugging.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) -> {
            log.error("Uncaught exception in async method '{}' with params {}: {}",
                    method.getName(),
                    params,
                    throwable.getMessage(),
                    throwable);
        };
    }
}

