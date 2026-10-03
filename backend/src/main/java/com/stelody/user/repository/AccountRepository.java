package com.stelody.user.repository;

import com.stelody.user.dto.CurrentUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
  private final JdbcClient jdbc;

  public AccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public CurrentUser upsertGoogleUser(String subject, String email) {
    // Unique subject + atomic UPSERT prevent duplicate users under simultaneous first logins.
    return jdbc.sql(
            """
        INSERT INTO app.app_user (id, google_subject, email)
        VALUES (:id, :subject, :email)
        ON CONFLICT (google_subject) DO UPDATE
        SET email = EXCLUDED.email, last_login_at = CURRENT_TIMESTAMP
        RETURNING id, email, role, status
        """)
        .param("id", UUID.randomUUID())
        .param("subject", subject)
        .param("email", email)
        .query(CurrentUser.class)
        .single();
  }

  public Optional<CurrentUser> find(UUID id) {
    return jdbc.sql("SELECT id, email, role, status FROM app.app_user WHERE id = :id")
        .param("id", id)
        .query(CurrentUser.class)
        .optional();
  }

  public Optional<String> lockGoogleSubject(UUID id) {
    return jdbc.sql(
            "SELECT google_subject FROM app.app_user WHERE id=:id AND status='ACTIVE' FOR UPDATE")
        .param("id", id)
        .query(String.class)
        .optional();
  }
}
