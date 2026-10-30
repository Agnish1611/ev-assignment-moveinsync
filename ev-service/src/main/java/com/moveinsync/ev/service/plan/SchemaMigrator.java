package com.moveinsync.ev.service.plan;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/** Applies the schema as the migration owner, before the application role connects. */
public final class SchemaMigrator {

  private SchemaMigrator() {
    throw new IllegalStateException("utility");
  }

  public static void migrate(String url, String user, String password) {
    MigrateResult result =
        Flyway.configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .load()
            .migrate();
    if (!result.success) {
      throw new IllegalStateException("schema migration failed");
    }
  }
}
