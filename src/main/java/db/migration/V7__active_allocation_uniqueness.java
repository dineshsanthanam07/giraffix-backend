package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Production defense in depth; the occupancy table provides the portable guarantee too. */
public class V7__active_allocation_uniqueness extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        if (context.getConnection().getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
            try (var statement = context.getConnection().createStatement()) {
                statement.execute("CREATE UNIQUE INDEX uq_active_concept_allocation ON concept_allocations(owner_id,product_id,concept_id) WHERE state IN ('RESERVED','CONSUMED')");
            }
        }
    }
}
