package com.stelody.auth.domain;

import java.io.Serializable;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

// Store only local identity and absolute login time in the JDBC session.
public record SessionUser(UUID id, Instant signedInAt, UUID loginId, Instant loginExpiresAt)
    implements Principal, Serializable {
  public SessionUser(UUID id, Instant signedInAt) {
    this(id, signedInAt, null, null);
  }

  @Override
  public String getName() {
    return id.toString();
  }
}
