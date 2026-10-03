package com.stelody.auth.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

// Only server-created authorization attributes can mark a login as withdrawal reauthentication.
public record ReauthenticationContext(
    SessionUser user, String sessionHash, UUID generation, Instant expiresAt)
    implements Serializable {
  public static final String ATTRIBUTE = ReauthenticationContext.class.getName();
}
