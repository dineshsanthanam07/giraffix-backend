package com.giraffix.selection;

import com.giraffix.generation.GenerationWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="giraffix.selection.worker-enabled",havingValue="true",matchIfMissing=true)
public class SelectionDispatcher {
    private static final Logger log=LoggerFactory.getLogger(SelectionDispatcher.class);
    private final SelectionStore store;
    private final SelectionWorker selection;
    private final GenerationWorker generation;
    public SelectionDispatcher(SelectionStore store,SelectionWorker selection,GenerationWorker generation){this.store=store;this.selection=selection;this.generation=generation;}
    @Scheduled(fixedDelayString="${giraffix.selection.poll-ms:1000}")
    public void poll(){
        try {for(var id:store.pending())selection.run(id);generation.poll();}
        catch(RuntimeException ex){log.error("stage=selection_dispatch_failed errorType={}",ex.getClass().getSimpleName());}
    }
}
