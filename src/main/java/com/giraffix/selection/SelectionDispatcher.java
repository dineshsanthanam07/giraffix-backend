package com.giraffix.selection;

import com.giraffix.generation.GenerationWorker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Independent bounded lanes: long renders never block selection, and no in-memory job backlog. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name="giraffix.selection.worker-enabled",havingValue="true",matchIfMissing=true)
public class SelectionDispatcher {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SelectionDispatcher.class);
    private final Lane selections;
    private final Lane generations;

    public SelectionDispatcher(SelectionStore store,SelectionWorker selection,GenerationWorker generation,
                               @Value("${giraffix.pipeline.selection-concurrency:1}") int selectionConcurrency,
                               @Value("${giraffix.pipeline.generation-concurrency:1}") int generationConcurrency,
                               @Value("${giraffix.pipeline.max-idle-poll-ms:10000}") long maxIdleMs){
        selections=new Lane("selection",selectionConcurrency,maxIdleMs,store::pending,selection::run);
        generations=new Lane("generation",generationConcurrency,maxIdleMs,generation::pending,generation::run);
    }
    @Scheduled(fixedDelayString="${giraffix.selection.poll-ms:1000}")
    public void poll(){selections.tick();generations.tick();}
    @jakarta.annotation.PreDestroy public void close(){selections.close();generations.close();}

    /** Testable queue pump. Durable DB leases remain the authority across application instances. */
    public static final class Lane implements AutoCloseable {
        private final String name;
        private final int concurrency;
        private final long maxIdleMs;
        private final IntFunction<List<UUID>> pending;
        private final Consumer<UUID> run;
        private final ExecutorService executor;
        private final Set<UUID> running=ConcurrentHashMap.newKeySet();
        private long idleMs=1000;
        private long nextScan;

        public Lane(String name,int concurrency,long maxIdleMs,IntFunction<List<UUID>> pending,Consumer<UUID> run){
            if(concurrency<1 || concurrency>16 || maxIdleMs<1000 || maxIdleMs>60000)
                throw new IllegalArgumentException("Invalid pipeline concurrency or idle polling limit");
            this.name=name;this.concurrency=concurrency;this.maxIdleMs=maxIdleMs;this.pending=pending;this.run=run;
            executor=Executors.newFixedThreadPool(concurrency,task->{var thread=new Thread(task,"pipeline-"+name);thread.setDaemon(true);return thread;});
        }
        public synchronized void tick(){
            int capacity=concurrency-running.size();
            if(capacity<=0 || System.nanoTime()<nextScan || executor.isShutdown())return;
            try{
                var ids=pending.apply(capacity);
                if(ids.isEmpty()){
                    nextScan=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(idleMs);
                    idleMs=Math.min(maxIdleMs,idleMs*2);
                    return;
                }
                idleMs=1000;nextScan=0;
                for(UUID id:ids){
                    if(running.size()>=concurrency)break;
                    if(!running.add(id))continue;
                    try{executor.execute(()->{
                        try{run.accept(id);}catch(RuntimeException ex){log.error("stage=pipeline_job_failed lane={} jobId={} errorType={}",name,id,ex.getClass().getSimpleName());}
                        finally{running.remove(id);}
                    });}catch(RejectedExecutionException ex){running.remove(id);}
                }
            }catch(RuntimeException ex){
                nextScan=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(maxIdleMs);
                log.error("stage=pipeline_poll_failed lane={} errorType={}",name,ex.getClass().getSimpleName());
            }
        }
        @Override public void close(){executor.shutdownNow();}
    }
}
