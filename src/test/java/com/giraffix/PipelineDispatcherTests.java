package com.giraffix;

import com.giraffix.selection.SelectionDispatcher.Lane;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class PipelineDispatcherTests {
    @Test void fullLaneDoesNotPollOrCreateAnInMemoryBacklog() throws Exception {
        var scans=new AtomicInteger();var calls=new AtomicInteger();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var lane=new Lane("test",1,10000,limit->{
            assertThat(limit).isEqualTo(1);scans.incrementAndGet();return List.of(UUID.randomUUID());
        },id->{calls.incrementAndGet();entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}})){
            lane.tick();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            for(int i=0;i<20;i++)lane.tick();
            assertThat(scans).hasValue(1);assertThat(calls).hasValue(1);
        }finally{release.countDown();}
    }

    @Test void slowGenerationDoesNotBlockSelectionLane() throws Exception {
        var started=new CountDownLatch(1);var selected=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var generation=new Lane("generation",1,10000,limit->List.of(UUID.randomUUID()),id->{
            started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
        });var selection=new Lane("selection",1,10000,limit->List.of(UUID.randomUUID()),id->selected.countDown())){
            generation.tick();assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            selection.tick();assertThat(selected.await(5,TimeUnit.SECONDS)).isTrue();
        }finally{release.countDown();}
    }

    @Test void idleQueuesBackOffAndFailureInOneLaneDoesNotEscape() {
        var scans=new AtomicInteger();
        try(var lane=new Lane("idle",1,10000,limit->{scans.incrementAndGet();return List.of();},id->{})){
            for(int i=0;i<20;i++)lane.tick();
            assertThat(scans).hasValue(1);
        }
        try(var lane=new Lane("failure",1,10000,limit->{throw new IllegalStateException("Database unavailable");},id->{})){
            assertThatCode(lane::tick).doesNotThrowAnyException();
        }
    }
}
