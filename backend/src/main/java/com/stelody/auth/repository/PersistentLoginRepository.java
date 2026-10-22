package com.stelody.auth.repository;

import com.stelody.auth.domain.SessionUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PersistentLoginRepository {
  public record Grant(UUID id, UUID user, String csrf, Instant expiresAt, boolean rotated) {}

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;

  public PersistentLoginRepository(JdbcClient jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    tx = new TransactionTemplate(manager);
    tx.setTimeout(10);
  }

  private boolean eligible(UUID user) {
    return jdbc.sql(
            "SELECT role='USER' AND status='ACTIVE' FROM app.app_user WHERE id=:user FOR UPDATE")
        .param("user", user)
        .query(Boolean.class)
        .optional()
        .orElse(false);
  }

  public Optional<Grant> issue(UUID user, UUID id, String hash, String csrf, Instant now) {
    return tx.execute(
        t -> {
          if (!eligible(user)) {
            revokeUser(user);
            return Optional.empty();
          }
          jdbc.sql(
                  "DELETE FROM app.persistent_login WHERE user_id=:user AND expires_at<=clock_timestamp()")
              .param("user", user)
              .update();
          Instant expires = now.plusSeconds(30L * 24 * 3600);
          jdbc.sql(
                  "INSERT INTO app.persistent_login(id,user_id,token_hash,csrf_token,created_at,expires_at) VALUES(:id,:user,:hash,:csrf,:now,:expires)")
              .param("id", id)
              .param("user", user)
              .param("hash", hash)
              .param("csrf", csrf)
              .param("now", Timestamp.from(now))
              .param("expires", Timestamp.from(expires))
              .update();
          return Optional.of(new Grant(id, user, csrf, expires, true));
        });
  }

  public Optional<Grant> restore(UUID id, String presented, String replacement, UUID expectedUser) {
    return tx.execute(
        t -> {
          var user =
              jdbc.sql("SELECT user_id FROM app.persistent_login WHERE id=:id")
                  .param("id", id)
                  .query(UUID.class)
                  .optional();
          if (user.isEmpty() || (expectedUser != null && !expectedUser.equals(user.get())))
            return Optional.empty();
          if (!eligible(user.get())) {
            revokeUser(user.get());
            return Optional.empty();
          }
          var row =
              jdbc.sql(
                      "SELECT * FROM app.persistent_login WHERE id=:id AND user_id=:user AND expires_at>clock_timestamp() FOR UPDATE")
                  .param("id", id)
                  .param("user", user.get())
                  .query(
                      (rs, n) ->
                          new Object[] {
                            rs.getString("token_hash"),
                            rs.getString("previous_token_hash"),
                            rs.getTimestamp("previous_valid_until"),
                            rs.getString("csrf_token"),
                            rs.getTimestamp("expires_at").toInstant()
                          })
                  .optional();
          if (row.isEmpty()) return Optional.empty();
          Object[] a = row.get();
          boolean current = equal(presented, (String) a[0]);
          boolean previous =
              equal(presented, (String) a[1])
                  && a[2] != null
                  && ((Timestamp) a[2]).toInstant().isAfter(Instant.now());
          if (!current && !previous) return Optional.empty();
          if (current)
            jdbc.sql(
                    "UPDATE app.persistent_login SET previous_token_hash=token_hash,previous_valid_until=clock_timestamp()+INTERVAL '15 seconds',token_hash=:replacement WHERE id=:id")
                .param("replacement", replacement)
                .param("id", id)
                .update();
          // Concurrent requests using the immediately previous cookie may finish, but cannot
          // overwrite
          // the winner's new cookie. Neither rotation nor this grace window extends the 30-day
          // limit.
          return Optional.of(new Grant(id, user.get(), (String) a[3], (Instant) a[4], current));
        });
  }

  private static boolean equal(String a, String b) {
    return a != null
        && b != null
        && MessageDigest.isEqual(
            a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
  }

  public boolean valid(SessionUser user) {
    return jdbc.sql(
            "SELECT EXISTS(SELECT 1 FROM app.persistent_login p JOIN app.app_user u ON u.id=p.user_id WHERE p.id=:id AND p.user_id=:user AND p.expires_at>clock_timestamp() AND u.role='USER' AND u.status='ACTIVE')")
        .param("id", user.loginId())
        .param("user", user.id())
        .query(Boolean.class)
        .single();
  }

  public void revoke(UUID sessionGrant, UUID cookieId, String cookieHash) {
    tx.executeWithoutResult(
        t -> {
          if (sessionGrant != null)
            jdbc.sql("DELETE FROM app.persistent_login WHERE id=:id")
                .param("id", sessionGrant)
                .update();
          if (cookieId != null)
            jdbc.sql(
                    "DELETE FROM app.persistent_login WHERE id=:id AND (token_hash=:hash OR (previous_token_hash=:hash AND previous_valid_until>clock_timestamp()))")
                .param("id", cookieId)
                .param("hash", cookieHash)
                .update();
        });
  }

  public void revokeUser(UUID user) {
    jdbc.sql("DELETE FROM app.persistent_login WHERE user_id=:user").param("user", user).update();
  }

  public void updateCsrf(UUID id, UUID user, String csrf) {
    tx.executeWithoutResult(
        t -> {
          if (!eligible(user)) return;
          jdbc.sql(
                  "UPDATE app.persistent_login SET csrf_token=:csrf WHERE id=:id AND user_id=:user AND expires_at>clock_timestamp()")
              .param("csrf", csrf)
              .param("id", id)
              .param("user", user)
              .update();
        });
  }

  public void cleanup() {
    jdbc.sql(
            "WITH expired AS (SELECT id FROM app.persistent_login WHERE expires_at<=clock_timestamp() ORDER BY expires_at LIMIT 1000 FOR UPDATE SKIP LOCKED) DELETE FROM app.persistent_login p USING expired WHERE p.id=expired.id")
        .update();
  }
}
