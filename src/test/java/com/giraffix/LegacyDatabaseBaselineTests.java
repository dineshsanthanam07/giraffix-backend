package com.giraffix;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyDatabaseBaselineTests {
    @Test
    void baselinesExistingTablesWithoutRecreatingThemOrLosingData() throws Exception {
        String url = "jdbc:h2:mem:legacy_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V1__initial_schema.sql"));
            try (var statement = connection.prepareStatement(
                    "INSERT INTO users (id, google_id, email, onboarding_completed, created_at) "
                            + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
                statement.setObject(1, UUID.randomUUID());
                statement.setString(2, "existing-google-user");
                statement.setString(3, "existing@example.com");
                statement.setBoolean(4, true);
                statement.executeUpdate();
            }
        }

        Flyway flyway = Flyway.configure().dataSource(url, "sa", "")
                .baselineOnMigrate(true).baselineVersion("1").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(10);
        assertThat(flyway.info().all()).anySatisfy(migration -> {
            assertThat(migration.getType().name()).isEqualTo("BASELINE");
            assertThat(migration.getVersion().toString()).isEqualTo("1");
        });
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("11");

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT google_id FROM users")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("existing-google-user");
            assertThat(rows.next()).isFalse();
        }

        // Once tracked, subsequent starts work with automatic baselining disabled.
        assertThat(Flyway.configure().dataSource(url, "sa", "")
                .baselineOnMigrate(false).load().migrate().migrationsExecuted).isZero();
    }
}
