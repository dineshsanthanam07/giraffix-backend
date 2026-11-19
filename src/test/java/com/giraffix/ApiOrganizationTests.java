package com.giraffix;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:api_organization;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
        "giraffix.ai.api-key=test-only","spring.jpa.show-sql=false","giraffix.selection.worker-enabled=false"})
class ApiOrganizationTests {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Test void defaultApiUsesAdsHidesLegacySelectionAndGroupsResources() throws Exception {
        var mvc=MockMvcBuilders.webAppContextSetup(context).build();
        var response=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse();
        var paths=json.readTree(response.getContentAsString()).path("paths");
        assertThat(paths.has("/api/v1/selection-requests")).isFalse();
        assertThat(paths.has("/admin/prompts")).isFalse();
        assertThat(paths.at("/~1api~1v1~1ads/post/tags/0").asText()).isEqualTo("05 Ads");
        assertThat(paths.at("/~1api~1v1~1admin~1prompts/get/tags/0").asText()).isEqualTo("09 Admin - Prompts");
        assertThat(paths.at("/~1api~1v1~1brands/get/parameters").toString()).contains("page","size");
        assertThat(paths.at("/~1api~1v1~1brands~1{brandId}~1products/get/parameters").toString()).contains("page","size");
    }
}
