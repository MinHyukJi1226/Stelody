package com.stelody.user.repository;

import com.stelody.user.web.AccountException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WithdrawalRepository {
  private final JdbcClient jdbc;

  public WithdrawalRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void lockActive(UUID user) {
    if (jdbc.sql("SELECT id FROM app.app_user WHERE id=:user AND status='ACTIVE' FOR UPDATE")
        .param("user", user)
        .query(UUID.class)
        .optional()
        .isEmpty()) throw new AccountException(401, "SESSION_EXPIRED", "다시 로그인해 주세요");
  }

  public void pending(UUID user, String session, UUID generation, String state, Instant expires) {
    jdbc.sql(
            "DELETE FROM app.account_reauthentication WHERE user_id=:user AND expires_at<=clock_timestamp()")
        .param("user", user)
        .update();
    jdbc.sql(
            """
        INSERT INTO app.account_reauthentication(user_id,session_hash,generation,status,state_hash,expires_at)
        VALUES(:user,:session,:generation,'PENDING',:state,:expires)
        ON CONFLICT(user_id,session_hash) DO UPDATE SET generation=:generation,status='PENDING',
            state_hash=:state,expires_at=:expires
        """)
        .param("user", user)
        .param("session", session)
        .param("generation", generation)
        .param("state", state)
        .param("expires", Timestamp.from(expires))
        .update();
  }

  public boolean consume(UUID user, String session, UUID generation, String state) {
    return jdbc.sql(
                """
        UPDATE app.account_reauthentication SET status='VERIFYING',state_hash=NULL
        WHERE user_id=:user AND session_hash=:session AND generation=:generation
            AND state_hash=:state AND status='PENDING' AND expires_at>clock_timestamp()
        """)
            .param("user", user)
            .param("session", session)
            .param("generation", generation)
            .param("state", state)
            .update()
        == 1;
  }

  public boolean confirm(UUID user, String before, UUID generation, String after, Instant expires) {
    return jdbc.sql(
                """
        UPDATE app.account_reauthentication SET status='CONFIRMED',session_hash=:after,expires_at=:expires
        WHERE user_id=:user AND session_hash=:before AND generation=:generation
            AND status='VERIFYING' AND expires_at>clock_timestamp()
        """)
            .param("user", user)
            .param("before", before)
            .param("generation", generation)
            .param("after", after)
            .param("expires", Timestamp.from(expires))
            .update()
        == 1;
  }

  public Optional<Instant> confirmation(UUID user, String session) {
    return jdbc.sql(
            """
        SELECT expires_at FROM app.account_reauthentication
        WHERE user_id=:user AND session_hash=:session AND status='CONFIRMED'
            AND expires_at>clock_timestamp()
        """)
        .param("user", user)
        .param("session", session)
        .query((r, n) -> r.getTimestamp(1).toInstant())
        .optional();
  }

  public boolean delete(UUID user, String session) {
    return jdbc.sql("SELECT app.withdraw_account(:user,:session)")
        .param("user", user)
        .param("session", session)
        .query(Boolean.class)
        .single();
  }

  public int expire() {
    // A bounded batch skips rows being confirmed/deleted by another transaction.
    return jdbc.sql(
            """
        WITH expired AS (
          SELECT user_id,session_hash FROM app.account_reauthentication
          WHERE expires_at<=clock_timestamp() ORDER BY expires_at LIMIT 1000
          FOR UPDATE SKIP LOCKED
        )
        DELETE FROM app.account_reauthentication proof USING expired
        WHERE proof.user_id=expired.user_id AND proof.session_hash=expired.session_hash
        """)
        .update();
  }
}
