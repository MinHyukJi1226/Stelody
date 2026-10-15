package com.stelody.song.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SongCursorDateRangeTest {
  @Test
  void unboundedSearchAcceptsCursorIdentityFromBeforeDateFiltersWereAdded() throws Exception {
    var mapper = new ObjectMapper();
    var cursors = new SongCursor(mapper);
    var query = SongQuery.create(null, null, null, null, false, null, 20, null, null);
    var encoded =
        cursors.encode(query, null, 1, null, Instant.parse("2025-09-01T00:00:00Z"), new UUID(0, 1));
    var node = mapper.readTree(Base64.getUrlDecoder().decode(encoded));
    var previousIdentity =
        "{\"text\":\"\",\"type\":\"ALL\",\"members\":[],\"year\":null,\"collaboration\":false,\"sort\":\"LATEST\",\"emptySearch\":false,\"requiredMember\":null}";
    var previousHash =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(previousIdentity.getBytes(StandardCharsets.UTF_8)));
    assertThat(node.path("queryHash").asText()).isEqualTo(previousHash);
    assertThat(cursors.decode(encoded, query, null).id()).isEqualTo(new UUID(0, 1));
  }
}
