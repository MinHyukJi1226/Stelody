package com.stelody.admin.command;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A one-shot operator command. It has no HTTP endpoint and never looks up accounts by email. */
public final class AdminAccountCommand {
  private final DataSource source;

  public AdminAccountCommand(DataSource source) {
    this.source = source;
  }

  public boolean change(UUID id, String role, String reason) {
    if (id == null
        || !Set.of("ADMIN", "USER").contains(role)
        || reason == null
        || reason.isBlank()
        || reason.length() > 500) throw new IllegalArgumentException("Invalid operator request");
    var jdbc = new JdbcTemplate(source);
    return Boolean.TRUE.equals(
        new TransactionTemplate(new JdbcTransactionManager(source))
            .execute(
                tx -> {
                  var rows =
                      jdbc.queryForList(
                          "SELECT role,status FROM app.app_user WHERE id=? FOR UPDATE", id);
                  if (rows.isEmpty()) throw new IllegalArgumentException("Account not found");
                  var row = rows.getFirst();
                  if (!"ACTIVE".equals(row.get("status")))
                    throw new IllegalArgumentException("Account is not active");
                  String before = (String) row.get("role");
                  if (before.equals(role)) return false;
                  jdbc.update("UPDATE app.app_user SET role=? WHERE id=?", role, id);
                  jdbc.update(
                      """
        INSERT INTO app.catalog_audit(id,target_type,target_id,action,before_value,after_value,reason)
        VALUES(?,'ACCOUNT_ROLE',?,'SET_ROLE',jsonb_build_object('role',CAST(? AS text)),jsonb_build_object('role',CAST(? AS text)),?)
        """,
                      UUID.randomUUID(),
                      id,
                      before,
                      role,
                      reason.strip());
                  return true;
                }));
  }
}
