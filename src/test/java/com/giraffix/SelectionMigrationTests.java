package com.giraffix;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class SelectionMigrationTests {
    @Test void migratesExistingConceptsAsDraftsWithoutChangingIdsOrAssets() throws Exception {
        boolean postgres=System.getProperty("selection.test.jdbc")!=null;
        String url=System.getProperty("selection.test.jdbc","jdbc:h2:mem:selection_migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        String user=postgres?"selection_test":"sa";
        String schema="selection_migration_"+UUID.randomUUID().toString().replace("-","");
        Flyway.configure().dataSource(url,user,"").schemas(schema).defaultSchema(schema).target("4").load().migrate();
        UUID id=UUID.randomUUID();
        try(var connection=DriverManager.getConnection(url,user,"")){
            connection.setSchema(schema);
            try(var stmt=connection.prepareStatement("""
                INSERT INTO concepts(id,image_bucket,image_key,image_url,image_filename,image_media_type,image_size_bytes,
                    usable,analysis_json,schema_version,created_by,created_at)
                VALUES(?,'original-bucket','original-key','https://original/url','original.png','image/png',123,TRUE,?,1,'original-user',CURRENT_TIMESTAMP)
                """)){stmt.setObject(1,id);stmt.setString(2,ConceptFixtures.VALID);stmt.executeUpdate();}
        }
        assertThat(Flyway.configure().dataSource(url,user,"").schemas(schema).defaultSchema(schema).load().migrate().migrationsExecuted).isEqualTo(7);
        try(var connection=DriverManager.getConnection(url,user,"")){
            connection.setSchema(schema);
            try(var stmt=connection.createStatement();var row=stmt.executeQuery("SELECT c.id,c.image_bucket,c.image_key,r.id,r.analysis_json,r.status FROM concepts c JOIN concept_revisions r ON r.concept_id=c.id")){
                assertThat(row.next()).isTrue();assertThat(row.getObject(1,UUID.class)).isEqualTo(id);
                assertThat(row.getString(2)).isEqualTo("original-bucket");assertThat(row.getString(3)).isEqualTo("original-key");
                assertThat(row.getObject(4,UUID.class)).isEqualTo(id);assertThat(row.getString(5)).isEqualTo(ConceptFixtures.VALID);
                assertThat(row.getString(6)).isEqualTo("DRAFT");assertThat(row.next()).isFalse();
            }
        }
        assertThat(Flyway.configure().dataSource(url,user,"").schemas(schema).defaultSchema(schema).load().migrate().migrationsExecuted).isZero();
    }
}
