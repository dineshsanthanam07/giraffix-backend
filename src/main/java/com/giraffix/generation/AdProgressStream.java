package com.giraffix.generation;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Bounded live snapshots, grouped per owned operation. No model calls or durable job mutation. */
@Service
public class AdProgressStream {
    private static final Set<String> ACTIVE=Set.of("QUEUED","SCREENING","VERIFYING","SELECTED",
            "WAITING_FOR_PROVIDER","PROMPTING","PROMPT_READY","GENERATING","REVIEWING");
    private final AdStatusService statuses;
    private final long pollMs,heartbeatNanos,timeoutMs;
    private final int maxStreams,maxPerUser;
    private final boolean enabled;
    private final LongSupplier clock;
    private final Object registry=new Object();
    private final Map<String,Integer> counts=new HashMap<>();
    private final ConcurrentMap<Key,Group> groups=new ConcurrentHashMap<>();
    private int active;
    private volatile boolean stopped;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"ad-progress-timer"));
    private final ThreadPoolExecutor workers;

    @Autowired
    public AdProgressStream(AdStatusService statuses,
            @Value("${giraffix.progress.poll-ms:1000}") long pollMs,
            @Value("${giraffix.progress.heartbeat-ms:15000}") long heartbeatMs,
            @Value("${giraffix.progress.connection-timeout-ms:120000}") long timeoutMs,
            @Value("${giraffix.progress.max-connections:200}") int maxStreams,
            @Value("${giraffix.progress.max-connections-per-user:5}") int maxPerUser,
            @Value("${giraffix.progress.worker-enabled:true}") boolean enabled){
        this(statuses,pollMs,heartbeatMs,timeoutMs,maxStreams,maxPerUser,enabled,System::nanoTime);
    }

    AdProgressStream(AdStatusService statuses,long pollMs,long heartbeatMs,long timeoutMs,
                     int maxStreams,int maxPerUser,boolean enabled,LongSupplier clock){
        if(pollMs<250 || pollMs>30000 || heartbeatMs<1000 || timeoutMs<10000 || timeoutMs>1800000
                || heartbeatMs>=timeoutMs || maxStreams<1 || maxStreams>10000 || maxPerUser<1 || maxPerUser>maxStreams)
            throw new IllegalArgumentException("Invalid ad progress limits");
        this.statuses=statuses;this.pollMs=pollMs;this.heartbeatNanos=TimeUnit.MILLISECONDS.toNanos(heartbeatMs);
        this.timeoutMs=timeoutMs;this.maxStreams=maxStreams;this.maxPerUser=maxPerUser;this.enabled=enabled;this.clock=clock;
        workers=new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(maxStreams),
                r->daemon(r,"ad-progress-reader"),new ThreadPoolExecutor.AbortPolicy());
    }

    @jakarta.annotation.PostConstruct public void start(){
        if(enabled)timer.scheduleWithFixedDelay(this::scan,pollMs,pollMs,TimeUnit.MILLISECONDS);
    }

    public SseEmitter subscribe(String subject,UUID id,HttpSession session){
        if(!authenticated(session,subject))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in to stream progress");
        var initial=statuses.get(subject,id); // Reject unknown/foreign operations before opening SSE.
        Client client;
        synchronized(registry){
            if(stopped || !enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Live progress is unavailable");
            if(active>=maxStreams || counts.getOrDefault(subject,0)>=maxPerUser)
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Too many open progress streams");
            var key=new Key(subject,id);
            Group group=groups.computeIfAbsent(key,Group::new);
            client=new Client(group,session);
            active++;counts.merge(subject,1,Integer::sum);group.clients.put(client.id,client);
        }
        client.emitter.onCompletion(()->detach(client));
        client.emitter.onTimeout(()->finish(client));
        client.emitter.onError(error->detach(client));
        client.publish(initial);
        client.ready=true;
        return client.emitter;
    }

    /** Timer only admits bounded work. A slow client/database never runs on a generation worker. */
    void scan(){
        if(stopped)return;
        for(Group group:groups.values())if(group.running.compareAndSet(false,true)){
            try{workers.execute(()->{
                try{refresh(group);}finally{group.running.set(false);}
            });}catch(RejectedExecutionException ex){group.running.set(false);}
        }
    }

    private void refresh(Group group){
        for(Client client:group.clients.values())if(client.ready)client.checkLifetime();
        if(group.clients.values().stream().noneMatch(c->c.ready && !c.closed.get()))return;
        AdController.Status latest;
        try{latest=statuses.get(group.key.subject(),group.key.operation());}
        catch(ResponseStatusException ex){
            for(Client client:group.clients.values())client.issue("AD_UNAVAILABLE",false);
            return;
        }catch(RuntimeException ex){
            // A stream failure is not an ad failure. Reconnect and read durable state again.
            for(Client client:group.clients.values())client.issue("PROGRESS_UNAVAILABLE",true);
            return;
        }
        for(Client client:group.clients.values())if(client.ready)client.publish(latest);
    }

    private boolean authenticated(HttpSession session,String subject){
        if(session==null || subject==null)return false;
        try{
            Object value=session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if(!(value instanceof SecurityContext context))return false;
            var authentication=context.getAuthentication();
            return authentication!=null && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof OidcUser user && subject.equals(user.getSubject());
        }catch(IllegalStateException ex){return false;}
    }

    private void detach(Client client){
        if(!client.closed.compareAndSet(false,true))return;
        synchronized(registry){
            client.group.clients.remove(client.id);active--;
            counts.computeIfPresent(client.group.key.subject(),(key,count)->count==1?null:count-1);
            if(client.group.clients.isEmpty())groups.remove(client.group.key,client.group);
        }
    }
    private void finish(Client client){detach(client);client.emitter.complete();}
    int activeConnections(){synchronized(registry){return active;}}

    @jakarta.annotation.PreDestroy public void close(){
        synchronized(registry){stopped=true;}
        timer.shutdownNow();workers.shutdownNow();
        for(Group group:groups.values())for(Client client:group.clients.values())finish(client);
    }

    private static Thread daemon(Runnable task,String name){var thread=new Thread(task,name);thread.setDaemon(true);return thread;}
    private record Key(String subject,UUID operation){}
    private static final class Group {
        final Key key;
        final ConcurrentMap<UUID,Client> clients=new ConcurrentHashMap<>();
        final AtomicBoolean running=new AtomicBoolean();
        Group(Key key){this.key=key;}
    }

    private final class Client {
        final UUID id=UUID.randomUUID();
        final Group group;
        final HttpSession session;
        final SseEmitter emitter=new SseEmitter(timeoutMs);
        final AtomicBoolean closed=new AtomicBoolean();
        final long deadline=clock.getAsLong()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        volatile boolean ready;
        AdController.Status previous;
        long lastWrite=clock.getAsLong();
        Client(Group group,HttpSession session){this.group=group;this.session=session;}

        boolean checkLifetime(){
            if(closed.get())return false;
            if(!authenticated(session,group.key.subject())){issue("AUTHENTICATION_REQUIRED",false);return false;}
            if(clock.getAsLong()>=deadline){finish(this);return false;}
            return true;
        }
        synchronized void publish(AdController.Status snapshot){
            if(!checkLifetime())return;
            try{
                if(!snapshot.equals(previous)){
                    boolean terminal=!ACTIVE.contains(snapshot.status());
                    emitter.send(SseEmitter.event().name("progress").reconnectTime(2000)
                            .data(new Progress(snapshot.status(),message(snapshot.status()),terminal,snapshot,Instant.now()),MediaType.APPLICATION_JSON));
                    previous=snapshot;lastWrite=clock.getAsLong();
                    if(terminal)finish(this);
                }else if(clock.getAsLong()-lastWrite>=heartbeatNanos){
                    emitter.send(SseEmitter.event().comment("keep-alive"));lastWrite=clock.getAsLong();
                }
            }catch(java.io.IOException | IllegalStateException ex){detach(this);}
        }
        synchronized void issue(String code,boolean retryable){
            if(closed.get())return;
            try{emitter.send(SseEmitter.event().name("stream-error").data(new Issue(code,retryable),MediaType.APPLICATION_JSON));}
            catch(java.io.IOException | IllegalStateException ignored){}
            finally{finish(this);}
        }
    }

    public record Progress(String stage,String message,boolean terminal,AdController.Status ad,Instant observedAt){}
    public record Issue(String code,boolean retryable){}
    private static String message(String stage){
        return switch(stage){
            case "QUEUED"->"Your ad is queued";
            case "SCREENING","VERIFYING"->"Finding a concept for your product";
            case "SELECTED","PROMPTING"->"Preparing the creative direction";
            case "PROMPT_READY"->"Preparing to render your ad";
            case "WAITING_FOR_PROVIDER"->"Waiting for the image service";
            case "GENERATING"->"Generating your ad image";
            case "REVIEWING"->"Checking the generated ad";
            case "SUCCEEDED"->"Your ad is ready";
            case "DELETED"->"This saved ad was deleted";
            case "STALE_CONTEXT"->"The source product changed or was deleted";
            case "RECONCILIATION_REQUIRED"->"Generation needs attention";
            case "POOL_EXHAUSTED","NO_MATCH_WITHIN_BUDGET","TEMPORARILY_SUPPRESSED","SELECTION_BUSY"->"No available concept was selected";
            default->"The ad could not be completed";
        };
    }
}
