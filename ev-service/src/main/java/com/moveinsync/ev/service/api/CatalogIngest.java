package com.moveinsync.ev.service.api;

import com.moveinsync.ev.service.api.ApiModels.BulkResult;
import com.moveinsync.ev.service.api.ApiModels.Intake;
import com.moveinsync.ev.service.api.ApiModels.RecordResult;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Inserts the valid rows of one batch. A bad row rolls back to its savepoint. */
final class CatalogIngest {

  private CatalogIngest() {
    throw new IllegalStateException("utility");
  }

  static <T> Outcome apply(
      Connection connection,
      List<Intake<T>> rows,
      int maxIdLength,
      Function<T, String> identifier,
      RowStore<T> store)
      throws SQLException {
    List<RecordResult> results = new ArrayList<>();
    int stored = 0;
    for (Intake<T> row : rows) {
      if (row.value() == null || !row.code().isEmpty()) {
        results.add(rejected("", row.code()));
        continue;
      }
      if (write(connection, row.value(), maxIdLength, identifier, store, results)) {
        stored++;
      }
    }
    return new Outcome(new BulkResult(results), stored);
  }

  private static <T> boolean write(
      Connection connection,
      T value,
      int maxIdLength,
      Function<T, String> identifier,
      RowStore<T> store,
      List<RecordResult> results)
      throws SQLException {
    String id = subject(identifier.apply(value), maxIdLength);
    Savepoint save = connection.setSavepoint();
    try {
      store.store(connection, value);
      connection.releaseSavepoint(save);
      results.add(new RecordResult(id, "STORED", ""));
      return true;
    } catch (ApiException ex) {
      connection.rollback(save);
      results.add(new RecordResult(id, "QUARANTINED", ex.code()));
      return false;
    } catch (SQLException ex) {
      connection.rollback(save);
      results.add(new RecordResult(id, "QUARANTINED", sqlCode(ex)));
      return false;
    }
  }

  private static RecordResult rejected(String id, String code) {
    String stable = code == null || code.isEmpty() ? "INVALID_BODY" : code;
    return new RecordResult(id, "QUARANTINED", stable);
  }

  static String subject(String value, int maxLength) {
    try {
      return OpaqueIds.require(value, maxLength);
    } catch (ApiException ex) {
      return "";
    }
  }

  private static String sqlCode(SQLException failure) {
    String state = failure.getSQLState();
    if ("23505".equals(state)) {
      return "DUPLICATE";
    }
    if ("23503".equals(state)) {
      return "MISSING_REFERENCE";
    }
    if ("23514".equals(state)) {
      return "INVALID_ROW";
    }
    return "STORE_REJECTED";
  }

  @FunctionalInterface
  interface RowStore<T> {
    void store(Connection connection, T row) throws SQLException;
  }

  record Outcome(BulkResult result, int stored) {}
}
