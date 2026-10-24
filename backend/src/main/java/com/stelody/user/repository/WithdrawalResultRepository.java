package com.stelody.user.repository;

import com.stelody.user.web.AccountException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WithdrawalResultRepository {
  private final JdbcClient jdbc;

  public WithdrawalResultRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record Receipt(
      UUID id,
      UUID user,
      String tokenHash,
      String status,
      String errorCode,
      Instant expiresAt,
      Instant completedAt,
      boolean expired,
      boolean stalled) {}

  public Instant create(UUID id, UUID user, String hash) {
    return jdbc.sql(
            """
        INSERT INTO app.withdrawal_result(id,user_id,token_hash,status)
        SELECT :id,id,:hash,'PREPARED' FROM app.app_user WHERE id=:user AND status='ACTIVE'
        RETURNING expires_at
        """)
        .param("id", id)
        .param("user", user)
        .param("hash", hash)
        .query((rs, n) -> rs.getTimestamp(1).toInstant())
        .optional()
        .orElseThrow(() -> new AccountException(401, "SESSION_EXPIRED", "다시 로그인해 주세요"));
  }

  public Optional<Receipt> find(UUID id) {
    return jdbc.sql(
            """
        SELECT *,expires_at<=clock_timestamp() AS expired,
          started_at<=clock_timestamp()-INTERVAL '2 minutes' AS stalled
        FROM app.withdrawal_result WHERE id=:id
        """)
        .param("id", id)
        .query(
            (rs, n) ->
                new Receipt(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getString("token_hash"),
                    rs.getString("status"),
                    rs.getString("error_code"),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("completed_at") == null
                        ? null
                        : rs.getTimestamp("completed_at").toInstant(),
                    rs.getBoolean("expired"),
                    rs.getBoolean("stalled")))
        .optional();
  }

  public boolean claim(UUID id, UUID user) {
    return jdbc.sql(
                """
        UPDATE app.withdrawal_result SET status='PROCESSING',started_at=clock_timestamp()
        WHERE id=:id AND user_id=:user AND status='PREPARED' AND expires_at>clock_timestamp()
        """)
            .param("id", id)
            .param("user", user)
            .update()
        == 1;
  }

  public void completed(UUID id) {
    if (jdbc.sql(
                """
        UPDATE app.withdrawal_result SET status='COMPLETED',completed_at=clock_timestamp(),error_code=NULL
        WHERE id=:id AND status='PROCESSING'
        """)
            .param("id", id)
            .update()
        != 1) throw new IllegalStateException("Withdrawal receipt could not be completed");
  }

  public void failed(UUID id, String status, String code) {
    jdbc.sql(
            """
        UPDATE app.withdrawal_result SET status=:status,error_code=:code
        WHERE id=:id AND status='PROCESSING'
        """)
        .param("id", id)
        .param("status", status)
        .param("code", code)
        .update();
  }

  public int cleanup() {
    return jdbc.sql(
            """
        WITH expired AS (SELECT id FROM app.withdrawal_result
          WHERE expires_at<=clock_timestamp() ORDER BY expires_at LIMIT 1000 FOR UPDATE SKIP LOCKED)
        DELETE FROM app.withdrawal_result r USING expired WHERE r.id=expired.id
        """)
        .update();
  }
}
