package com.giraffix.prompt;

import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PromptChangeListenerTests {
    @Test void notificationsAreScopedToTheApplicationSchemaAndValidatePayloads(){
        var templates=mock(PromptTemplates.class);
        var listener=new PromptChangeListener(mock(DataSource.class),templates,true);
        listener.accept("public","other_schema:ad-creative-director:2");
        listener.accept("public",null);
        verifyNoInteractions(templates);
        listener.accept("public","public:ad-creative-director:2");
        verify(templates).changed("ad-creative-director",2);
        listener.accept("public","public:ad-creative-director:invalid");
        verify(templates).invalidate();
    }

    @Test void listenerRegistersOnceAndWaitsForPushWithoutQueryPolling() throws Exception {
        var source=mock(DataSource.class);var session=mock(Connection.class);var statement=mock(Statement.class);
        var pg=mock(PGConnection.class);var notification=mock(PGNotification.class);var templates=mock(PromptTemplates.class);
        when(source.getConnection()).thenReturn(session);when(session.isWrapperFor(PGConnection.class)).thenReturn(true);
        when(session.unwrap(PGConnection.class)).thenReturn(pg);when(session.getSchema()).thenReturn("public");when(session.createStatement()).thenReturn(statement);
        when(notification.getParameter()).thenReturn("public:ad-creative-director:2");
        var first=new AtomicBoolean(true);var changed=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(pg.getNotifications(anyInt())).thenAnswer(call->{
            if(first.compareAndSet(true,false))return new PGNotification[]{notification};
            release.await(5,TimeUnit.SECONDS);return null;
        });
        doAnswer(call->{changed.countDown();return null;}).when(templates).changed("ad-creative-director",2);
        var listener=new PromptChangeListener(source,templates,true);
        try{
            listener.start();assertThat(changed.await(5,TimeUnit.SECONDS)).isTrue();
            verify(statement,times(1)).execute("LISTEN giraffix_prompt_changed");
            verify(session,never()).prepareStatement(anyString());
            verify(templates).invalidate();
        }finally{listener.stop();release.countDown();}
    }
}
