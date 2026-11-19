package com.giraffix;

import com.giraffix.generation.*;
import com.giraffix.selection.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Prevent regression to repeated ownership/status reads on frequently polled endpoints. */
class PipelineStatusQueryTests {
    @Test void selectionStatusUsesOneAuthorizedQueryAndNoProviderOrSnapshotLoad(){
        JdbcTemplate db=mock(JdbcTemplate.class);
        var contexts=mock(SelectionContextService.class);
        var store=new SelectionStore(db,new JsonSupport(new ObjectMapper()),mock(PlatformTransactionManager.class));
        var service=new SelectionService(store,contexts,new SelectionPolicy());
        assertThatThrownBy(()->service.status("owner",UUID.randomUUID())).isInstanceOf(ResponseStatusException.class);
        assertSingleAuthorizedQuery(db,true);
        verifyNoInteractions(contexts);
    }

    @Test void adStatusUsesOneAuthorizedQueryAndNoProviderOrSelectionReload(){
        JdbcTemplate db=mock(JdbcTemplate.class);
        var store=new SelectionStore(db,new JsonSupport(new ObjectMapper()),mock(PlatformTransactionManager.class));
        var selections=mock(SelectionService.class);var contexts=mock(SelectionContextService.class);var provider=mock(ImageGenerator.class);
        var controller=new AdController(selections,new AdStatusService(store),provider);
        var user=mock(OidcUser.class);when(user.getSubject()).thenReturn("owner");
        assertThatThrownBy(()->controller.get(user,UUID.randomUUID())).isInstanceOf(ResponseStatusException.class);
        assertSingleAuthorizedQuery(db,false);
        verifyNoInteractions(selections,contexts,provider);
    }

    private void assertSingleAuthorizedQuery(JdbcTemplate db,boolean needsLiveProduct){
        var invocations=mockingDetails(db).getInvocations();
        assertThat(invocations).hasSize(1);
        var invocation=invocations.iterator().next();
        assertThat(invocation.getMethod().getName()).isEqualTo("query");
        String sql=(String)invocation.getArgument(0);
        assertThat(sql).contains("u.google_id=?","u.id=s.owner_id");
        if(needsLiveProduct)assertThat(sql).contains("b.user_id=u.id","p.id=s.product_id");
        else assertThat(sql).doesNotContain("JOIN products");
    }
}
