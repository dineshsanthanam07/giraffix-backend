package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** PostgreSQL delivers invalidations only after commit, including edits from other writers. */
public class V14__prompt_publication_notifications extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        if(!"PostgreSQL".equals(context.getConnection().getMetaData().getDatabaseProductName()))return;
        try(var statement=context.getConnection().createStatement()){
            statement.execute("""
                    CREATE FUNCTION giraffix_notify_prompt_change() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      IF OLD.active_version IS DISTINCT FROM NEW.active_version THEN
                        PERFORM pg_notify('giraffix_prompt_changed', TG_TABLE_SCHEMA || ':' || NEW.prompt_key || ':' || NEW.active_version::text);
                      END IF;
                      RETURN NEW;
                    END;
                    $$
                    """);
            statement.execute("CREATE TRIGGER giraffix_prompt_change AFTER UPDATE OF active_version ON prompt_templates FOR EACH ROW EXECUTE FUNCTION giraffix_notify_prompt_change()");
        }
    }
}
