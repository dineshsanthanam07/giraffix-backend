package com.giraffix.prompt;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.*;

/** One idle PostgreSQL session per replica, listening for commits rather than polling prompt tables. */
@Component
public class PromptChangeListener implements SmartLifecycle {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(PromptChangeListener.class);
    private final DataSource source;
    private final PromptTemplates templates;
    private final boolean enabled;
    private volatile boolean running;
    private volatile Connection connection;
    private ExecutorService executor;

    public PromptChangeListener(DataSource source,PromptTemplates templates,
                                @Value("${giraffix.prompts.cluster-notifications:true}") boolean enabled){
        this.source=source;this.templates=templates;this.enabled=enabled;
    }
    @Override public synchronized void start(){
        if(!enabled || running)return;
        running=true;
        executor=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"prompt-change-listener");thread.setDaemon(true);return thread;});
        executor.execute(this::listen);
    }
    private void listen(){
        long retryMs=1000;
        while(running){
            try(var session=source.getConnection()){
                if(!session.isWrapperFor(PGConnection.class)){running=false;return;}
                connection=session;session.setAutoCommit(true);
                String schema=session.getSchema();
                try(var statement=session.createStatement()){statement.execute("LISTEN giraffix_prompt_changed");}
                // LISTEN first, then invalidate: updates during reconnect cannot be silently missed.
                templates.invalidate();retryMs=1000;
                PGConnection pg=session.unwrap(PGConnection.class);
                while(running){
                    // Socket wait only. This timeout does not send SQL or read prompt tables.
                    PGNotification[] events=pg.getNotifications(1000);
                    if(events!=null)for(PGNotification event:events)accept(schema,event.getParameter());
                }
            }catch(Exception ex){
                if(!running)break;
                templates.invalidate();
                log.warn("stage=prompt_notifications_reconnecting errorType={}",ex.getClass().getSimpleName());
                try{Thread.sleep(retryMs);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}
                retryMs=Math.min(30000,retryMs*2);
            }finally{connection=null;}
        }
    }
    void accept(String schema,String payload){
        String prefix=schema+":";
        if(payload==null || !payload.startsWith(prefix))return;
        String value=payload.substring(prefix.length());int split=value.lastIndexOf(':');
        if(split<1)return;
        try{templates.changed(value.substring(0,split),Integer.parseInt(value.substring(split+1)));}
        catch(NumberFormatException | org.springframework.web.server.ResponseStatusException ignored){templates.invalidate();}
    }
    @Override public synchronized void stop(){
        running=false;
        if(executor!=null)executor.shutdownNow();
        Connection session=connection;
        if(session!=null)try{session.close();}catch(java.sql.SQLException ignored){}
    }
    @Override public boolean isRunning(){return running;}
}
