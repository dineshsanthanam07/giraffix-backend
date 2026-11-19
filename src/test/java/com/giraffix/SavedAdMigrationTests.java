package com.giraffix;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class SavedAdMigrationTests {
    @Test void backfillsLiveBrandAndPreservesHistoryWhoseProductWasAlreadyDeleted() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:saved_ad_migration_"+UUID.randomUUID()+";MODE=PostgreSQL","sa","");
            var statement=connection.createStatement()){
            statement.execute("CREATE TABLE products(id UUID PRIMARY KEY,brand_id UUID NOT NULL)");
            statement.execute("CREATE TABLE creations(id UUID PRIMARY KEY,owner_id UUID,product_id UUID,created_at TIMESTAMP WITH TIME ZONE)");
            UUID live=UUID.randomUUID(),missing=UUID.randomUUID(),brand=UUID.randomUUID();
            try(var insert=connection.prepareStatement("INSERT INTO products VALUES(?,?)")){
                insert.setObject(1,live);insert.setObject(2,brand);insert.executeUpdate();
            }
            for(UUID product:java.util.List.of(live,missing)){
                try(var insert=connection.prepareStatement("INSERT INTO creations VALUES(?,?,?,CURRENT_TIMESTAMP)")){
                    insert.setObject(1,UUID.randomUUID());insert.setObject(2,UUID.randomUUID());insert.setObject(3,product);insert.executeUpdate();
                }
            }
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V16__saved_ad_lifecycle.sql"));
            try(var rows=statement.executeQuery("SELECT product_id,brand_id FROM creations")){
                int count=0;
                while(rows.next()){
                    count++;
                    assertThat(rows.getObject("brand_id",UUID.class)).isEqualTo(rows.getObject("product_id",UUID.class).equals(live)?brand:null);
                }
                assertThat(count).isEqualTo(2);
            }
        }
    }
}
