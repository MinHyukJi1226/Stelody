package com.stelody.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SessionUserTest {
  @Test
  void sessionsSerializedBeforePersistentLoginRemainReadableWithoutGainingADeviceCredential()
      throws Exception {
    // Produced with the pre-V25 two-component SessionUser class, not with the current serializer.
    byte[] legacy =
        Base64.getDecoder()
            .decode(
                "rO0ABXNyACNjb20uc3RlbG9keS5hdXRoLmRvbWFpbi5TZXNzaW9uVXNlcgAAAAAAAAAAAgACTAACaWR0ABBMamF2YS91dGlsL1VVSUQ7TAAKc2lnbmVkSW5BdHQAE0xqYXZhL3RpbWUvSW5zdGFudDt4cHNyAA5qYXZhLnV0aWwuVVVJRLyZA/eYbYUvAgACSgAMbGVhc3RTaWdCaXRzSgALbW9zdFNpZ0JpdHN4cAAAAAAAAAABAAAAAAAAAABzcgANamF2YS50aW1lLlNlcpVdhLobIkiyDAAAeHB3DQIAAAAAar2igAAAAAB4");
    try (var input = new ObjectInputStream(new ByteArrayInputStream(legacy))) {
      var user = (SessionUser) input.readObject();
      assertThat(user.id()).isEqualTo(new UUID(0, 1));
      assertThat(user.signedInAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
      assertThat(user.loginId()).isNull();
      assertThat(user.loginExpiresAt()).isNull();
    }
  }
}
