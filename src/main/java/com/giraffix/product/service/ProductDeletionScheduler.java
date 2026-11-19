package com.giraffix.product.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Separate scheduler so storage outages cannot block selection/generation admission. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "giraffix.storage.cleanup.worker-enabled", havingValue = "true", matchIfMissing = true)
public class ProductDeletionScheduler {
    private final ProductImageDeletionQueue queue;
    private final com.giraffix.generation.GeneratedAdDeletionQueue ads;

    public ProductDeletionScheduler(ProductImageDeletionQueue queue,com.giraffix.generation.GeneratedAdDeletionQueue ads) {
        this.queue = queue;this.ads=ads;
    }

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("pipeline-poll-");
        return scheduler;
    }

    @Bean
    public ThreadPoolTaskScheduler productDeletionTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("product-deletion-");
        return scheduler;
    }

    @Scheduled(scheduler = "productDeletionTaskScheduler",
            fixedDelayString = "${giraffix.storage.cleanup.poll-ms:5000}", initialDelayString = "${giraffix.storage.cleanup.poll-ms:5000}")
    public void poll() {
        // A failure in one queue must not stop the other cleanup class.
        for(Runnable cleanup:java.util.List.<Runnable>of(queue::processPending,ads::processPending)){
            try{cleanup.run();}catch(RuntimeException ex){
                org.slf4j.LoggerFactory.getLogger(ProductDeletionScheduler.class)
                        .error("stage=asset_cleanup_poll_failed errorType={}",ex.getClass().getSimpleName());
            }
        }
    }
}
