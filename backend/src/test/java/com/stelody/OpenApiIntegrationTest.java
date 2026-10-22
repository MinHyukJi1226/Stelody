package com.stelody;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "prod"})
@Testcontainers
@Tag("integration")
class OpenApiIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "stelody_app");
    registry.add("spring.datasource.password", () -> "test-runtime");
    registry.add("spring.flyway.user", () -> "stelody_migrator");
    registry.add("spring.flyway.password", () -> "test-migrator");
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  private JsonNode contract() throws Exception {
    return mapper.readTree(
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  @Test
  void documentsEveryControllerRouteAndExportsValidatedContract() throws Exception {
    var api = contract();
    assertThat(api.path("openapi").asText()).startsWith("3.1.");
    mappings
        .getHandlerMethods()
        .forEach(
            (mapping, handler) ->
                mapping.getPatternValues().stream()
                    .filter(path -> path.startsWith("/api/v1/"))
                    .forEach(
                        path -> {
                          for (var method : mapping.getMethodsCondition().getMethods())
                            assertThat(
                                    api.path("paths").path(path).has(method.name().toLowerCase()))
                                .as(method + " " + path)
                                .isTrue();
                        }));
    assertThat(api.path("paths").has("/api/v1/auth/logout")).isTrue();
    api.path("paths")
        .properties()
        .forEach(entry -> assertThat(entry.getKey()).startsWith("/api/v1/"));
    verifyReferences(api, api);
    var destination = Path.of("build/openapi/stelody-openapi.json");
    Files.createDirectories(destination.getParent());
    Files.writeString(destination, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(api));
  }

  @Test
  void documentsFixedRegularUserLoginAndStorageFailureWithoutExposingCookieValues()
      throws Exception {
    var api = contract();
    assertThat(api.at("/components/securitySchemes/session/description").asText())
        .contains("30분", "12시간", "STELODY_LOGIN", "관리자");
    assertThat(api.at("/paths/~1api~1v1~1auth~1google/get/description").asText())
        .contains("30일", "ADMIN", "Secure", "HttpOnly", "SameSite=Lax", "탈퇴 재인증");
    assertThat(api.at("/paths/~1api~1v1~1auth~1csrf/get/description").asText())
        .contains("복구", "CSRF", "403");
    assertThat(api.at("/paths/~1api~1v1~1auth~1logout/post/responses/204/description").asText())
        .contains("현재 기기", "다른 기기", "STELODY_LOGIN", "폐기");
    for (String path : new String[] {"/api/v1/me", "/api/v1/auth/csrf", "/api/v1/auth/logout"}) {
      assertErrorCodes(
          api, path, path.endsWith("logout") ? "post" : "get", "503", "LOGIN_STORAGE_UNAVAILABLE");
    }
    assertThat(api.at("/paths/~1api~1v1~1songs/get/responses/default/description").asText())
        .contains("503 LOGIN_STORAGE_UNAVAILABLE");
    assertThat(api.at("/paths/~1api~1v1~1auth~1logout/post/security").toString()).contains("csrf");
    assertThat(api.at("/paths/~1api~1v1~1me/get/security").toString()).contains("session");
  }

  @Test
  void documentsActiveExportAsAnAuthenticatedNullableFullJobWithoutMutation() throws Exception {
    var api = contract();
    var op = api.path("paths").path("/api/v1/me/youtube-exports/active").path("get");
    assertThat(op.path("description").asText())
        .contains(
            "QUEUED",
            "RUNNING",
            "FAILED",
            "UNCERTAIN",
            "job:null",
            "playlistId",
            "EXPORT_ALREADY_ACTIVE");
    assertThat(op.path("parameters").isEmpty()).isTrue();
    assertThat(op.path("security").toString()).contains("session").doesNotContain("csrf");
    assertThat(op.path("responses").has("401")).isTrue();
    assertThat(op.path("responses").has("404")).isFalse();
    assertErrorCodes(
        api, "/api/v1/me/youtube-exports/active", "get", "503", "EXPORT_LOOKUP_UNAVAILABLE");
    String wrapper = "com.stelody.export.dto.ExportDtos.ActiveJob";
    assertThat(op.at("/responses/200/content/application~1json/schema/$ref").asText())
        .isEqualTo("#/components/schemas/" + wrapper);
    var schema = api.path("components").path("schemas").path(wrapper);
    assertThat(schema.path("required").toString()).isEqualTo("[\"job\"]");
    var job = schema.at("/properties/job");
    assertThat(job.path("anyOf").toString())
        .contains("#/components/schemas/com.stelody.export.dto.ExportDtos.Job", "null");
    assertThat(job.path("description").asText()).contains("조회 실패", "null");
  }

  @Test
  void documentsOptInLocalLoginWithoutAcceptingExternalReturnUrls() throws Exception {
    var operation = contract().at("/paths/~1api~1v1~1auth~1google/get");
    var params = new java.util.HashMap<String, JsonNode>();
    operation.path("parameters").forEach(p -> params.put(p.path("name").asText(), p));
    assertThat(params.keySet()).containsExactlyInAnyOrder("returnTo", "loginClient");
    assertThat(params.get("loginClient").at("/schema/enum").toString()).isEqualTo("[\"local\"]");
    assertThat(params.get("loginClient").path("description").asText())
        .contains("GOOGLE_LOCAL_REDIRECT_URI", "프록시", "YouTube", "재인증");
    assertThat(params.get("returnTo").at("/schema/enum").isMissingNode()).isTrue();
    assertThat(params.get("returnTo").at("/schema/maxLength").asInt()).isEqualTo(2048);
    assertThat(operation.at("/responses/400/description").asText())
        .contains("INVALID_LOGIN_CLIENT", "INVALID_LOGIN_RETURN");
  }

  @Test
  void distinguishesMultipleParticipantsFromAllSpecialPurposesInTheContract() throws Exception {
    var api = contract();
    for (String path : new String[] {"/api/v1/songs", "/api/v1/members/{id}/songs"}) {
      var names = new java.util.ArrayList<String>();
      for (var parameter : api.path("paths").path(path).path("get").path("parameters")) {
        String name = parameter.path("name").asText();
        names.add(name);
        if (name.equals("multipleParticipants")) {
          assertThat(parameter.at("/schema/type").asText()).isEqualTo("boolean");
          assertThat(parameter.path("required").asBoolean()).isFalse();
          assertThat(parameter.at("/schema/default").asBoolean()).isFalse();
          assertThat(parameter.path("description").asText())
              .contains("2명 이상", "게임", "필터가 아닙니다", "totalCount", "커서");
        }
      }
      assertThat(names).contains("multipleParticipants", "collaboration");
      for (var parameter : api.path("paths").path(path).path("get").path("parameters")) {
        if (parameter.path("name").asText().equals("collaboration")) {
          assertThat(parameter.path("deprecated").asBoolean()).isTrue();
          assertThat(parameter.at("/schema/type").asText()).isEqualTo("boolean");
          assertThat(parameter.path("description").asText())
              .contains("호환", "multipleParticipants", "다르면");
        }
      }
      assertThat(api.path("paths").path(path).path("get").at("/responses/400/description").asText())
          .contains("CONFLICTING_FILTER", "collaboration", "multipleParticipants");
    }
    var schemas = api.path("components").path("schemas");
    var card = schemas.path("com.stelody.song.dto.SongDtos.Card");
    assertThat(card.path("properties").has("collaboration")).isFalse();
    assertThat(card.path("required").toString())
        .contains("multipleParticipants")
        .doesNotContain("collaboration");
    assertThat(card.at("/properties/multipleParticipants/description").asText())
        .contains("2명 이상", "콜라보 배지");
    assertThat(card.at("/properties/isSpecialEvent/description").asText())
        .contains("생일", "데뷔", "주년", "신의상", "게임 협업", "true만으로");
    var label = card.at("/properties/specialEventLabel");
    assertThat(label.has("enum")).isFalse();
    assertThat(label.path("description").asText()).contains("자유 입력", "자동 판정하지 않습니다");
    var counts = schemas.path("com.stelody.member.dto.MemberDtos.Counts");
    assertThat(counts.path("properties").has("collaborations")).isTrue();
    assertThat(counts.at("/properties/collaborations/deprecated").asBoolean()).isTrue();
    assertThat(counts.at("/properties/collaborations/type").asText()).isEqualTo("integer");
    assertThat(counts.path("required").toString()).contains("collaborations");
    assertThat(counts.at("/properties/collaborations/description").asText())
        .contains("호환", "multipleParticipants", "게임");
    assertThat(counts.path("properties").has("multipleParticipants")).isTrue();
    assertThat(counts.at("/properties/multipleParticipants/type").asText()).isEqualTo("integer");
    assertThat(
            api.at(
                    "/components/schemas/ApiProblem/properties/fieldErrors/items/properties/code/enum")
                .toString())
        .contains("CONFLICTING_FILTER");
  }

  @Test
  void documentsRelatedSongsAsSameWorkCoversByDifferentMembers() throws Exception {
    var api = contract();
    var related =
        api.path("components")
            .path("schemas")
            .path("com.stelody.song.dto.SongDtos.Detail")
            .path("properties")
            .path("relatedSongs");
    assertThat(related.path("type").asText()).isEqualTo("array");
    assertThat(related.path("maxItems").asInt()).isEqualTo(6);
    assertThat(related.path("uniqueItems").asBoolean()).isTrue();
    assertThat(related.path("description").asText())
        .contains(
            "work.id",
            "COVER",
            "확정 멤버",
            "제외",
            "공개",
            "조건 적용 후",
            "publishedAt",
            "곡 ID 내림차순",
            "6개",
            "빈 배열",
            "페이지 조회는 지원하지 않습니다");
    assertThat(
            api.path("paths").path("/api/v1/songs/{id}").path("get").path("description").asText())
        .contains("relatedSongs", "같은 원곡", "다른 멤버");
  }

  @Test
  void documentsWhitespaceInsensitiveSearchAndExistingSongAliasEditing() throws Exception {
    var api = contract();
    for (var p : api.path("paths").path("/api/v1/songs").path("get").path("parameters"))
      if (p.path("name").asText().equals("q")) {
        assertThat(p.path("description").asText())
            .contains("공백", "대소문자", "별칭", "200", "totalCount");
        assertThat(p.at("/schema/maxLength").asInt()).isEqualTo(200);
      }
    var alias =
        api.path("components")
            .path("schemas")
            .path("com.stelody.admin.dto.CatalogAdminDtos.SongInput")
            .path("properties")
            .path("aliases");
    assertThat(alias.path("description").asText())
        .contains("번역명", "발음", "PUT", "빈 배열", "50", "300");
  }

  @Test
  void documentsDateRangeAndValidationWithoutPollutingOtherParameters() throws Exception {
    var api = contract();
    assertThat(
            api.at(
                    "/components/schemas/ApiProblem/properties/fieldErrors/items/properties/code/enum")
                .toString())
        .contains("INVALID_DATE", "DATE_OUT_OF_RANGE", "INVALID_DATE_RANGE", "CONFLICTING_FILTER");
    var operation = api.path("paths").path("/api/v1/songs").path("get");
    var names = new java.util.ArrayList<String>();
    for (var p : operation.path("parameters")) {
      var name = p.path("name").asText();
      if (name.equals("startDate") || name.equals("endDate")) {
        names.add(name);
        assertThat(p.path("required").asBoolean()).isFalse();
        assertThat(p.at("/schema/type").asText()).isEqualTo("string");
        assertThat(p.at("/schema/format").asText()).isEqualTo("date");
        assertThat(p.path("schema").has("enum")).isFalse();
        assertThat(p.path("description").asText())
            .contains("Asia/Seoul", "1900", "2100", "year", "커서");
      } else if (java.util.List.of("q", "cursor", "type", "sort", "mode").contains(name)) {
        assertThat(p.path("schema").has("format")).isFalse();
        assertThat(p.path("schema").has("pattern")).isFalse();
      }
    }
    assertThat(names).containsExactlyInAnyOrder("startDate", "endDate");
    assertThat(operation.at("/responses/400/description").asText())
        .contains(
            "INVALID_CATALOG_QUERY",
            "INVALID_DATE",
            "DATE_OUT_OF_RANGE",
            "INVALID_DATE_RANGE",
            "CONFLICTING_FILTER",
            "totalCount");
  }

  @Test
  void parameterConstraintsStayIsolated() throws Exception {
    var api = contract();
    for (var path : new String[] {"/api/v1/songs", "/api/v1/members/{id}/songs"}) {
      var parameters = api.path("paths").path(path).path("get").path("parameters");
      for (var parameter : parameters) {
        var name = parameter.path("name").asText();
        var schema = parameter.path("schema");
        switch (name) {
          case "q" -> {
            assertThat(schema.path("type").asText()).isEqualTo("string");
            assertThat(schema.has("enum")).isFalse();
            assertThat(schema.path("maxLength").asInt()).isEqualTo(200);
          }
          case "type" -> assertParameterEnum(schema, "ALL", "COVER", "ORIGINAL");
          case "sort" -> assertParameterEnum(schema, "LATEST", "RELEVANCE", "VIEWS");
          case "mode" -> assertParameterEnum(schema, "BROWSE", "SEARCH");
          default -> {}
        }
      }
    }
    api.path("paths")
        .properties()
        .forEach(
            entry -> {
              entry
                  .getValue()
                  .properties()
                  .forEach(
                      operation -> {
                        for (var parameter : operation.getValue().path("parameters")) {
                          var name = parameter.path("name").asText();
                          if (name.equals("cursor") || entry.getKey().contains("callback")) {
                            var schema = parameter.path("schema");
                            assertThat(schema.path("type").asText())
                                .as(entry.getKey() + " " + name)
                                .isEqualTo("string");
                            assertThat(schema.has("enum"))
                                .as(entry.getKey() + " " + name)
                                .isFalse();
                            assertThat(schema.has("maxLength"))
                                .as(entry.getKey() + " " + name)
                                .isFalse();
                          }
                        }
                      });
            });
  }

  @Test
  void publicMemberRoutesAreNotClassifiedAsPersonalAccountRoutes() throws Exception {
    var api = contract();
    for (var path :
        new String[] {"/api/v1/members", "/api/v1/members/{id}", "/api/v1/members/{id}/songs"}) {
      var operation = api.path("paths").path(path).path("get");
      assertThat(operation.path("tags").get(0).asText()).isEqualTo("공개 카탈로그");
      assertThat(operation.path("security").isEmpty()).isTrue();
      assertThat(operation.path("description").asText()).doesNotContain("현재 로그인 계정");
      assertThat(operation.path("responses").has("401")).isFalse();
    }
    mvc.perform(get("/api/v1/members")).andExpect(status().isOk());
    var missingMember = "/api/v1/members/00000000-0000-0000-0000-000000000000";
    mvc.perform(get(missingMember)).andExpect(status().isNotFound());
    mvc.perform(get(missingMember + "/songs")).andExpect(status().isNotFound());
  }

  private void verifyReferences(JsonNode root, JsonNode node) {
    if (node.isObject()) {
      if (node.has("$ref")) {
        var ref = node.path("$ref").asText();
        assertThat(ref).startsWith("#/");
        assertThat(root.at(ref.substring(1)).isMissingNode()).as(ref).isFalse();
      }
      node.properties().forEach(entry -> verifyReferences(root, entry.getValue()));
    } else if (node.isArray()) node.forEach(child -> verifyReferences(root, child));
  }

  @Test
  void documentsPublicYearOptionsAndKoreanYearFilter() throws Exception {
    var api = contract();
    var operation = api.path("paths").path("/api/v1/songs/years").path("get");
    assertThat(operation.path("security").isEmpty()).isTrue();
    assertThat(operation.path("parameters").isEmpty()).isTrue();
    assertThat(operation.path("description").asText())
        .contains("일반 탐색", "대표 영상", "Asia/Seoul", "현재 검색 조건으로 좁히지");
    assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asText())
        .isEqualTo("#/components/schemas/com.stelody.song.dto.SongDtos.Years");
    var schema = api.path("components").path("schemas").path("com.stelody.song.dto.SongDtos.Years");
    assertThat(schema.path("required").toString()).contains("years");
    var years = schema.path("properties").path("years");
    assertThat(years.path("type").asText()).isEqualTo("array");
    assertThat(years.path("uniqueItems").asBoolean()).isTrue();
    assertThat(years.path("items").path("type").asText()).isEqualTo("integer");
    assertThat(years.path("items").path("minimum").asInt()).isEqualTo(1900);
    assertThat(years.path("items").path("maximum").asInt()).isEqualTo(2100);
    assertThat(years.path("description").asText()).contains("내림차순", "빈 배열", "필터와 무관");
    for (var parameter : api.path("paths").path("/api/v1/songs").path("get").path("parameters"))
      if (parameter.path("name").asText().equals("year"))
        assertThat(parameter.path("description").asText()).contains("Asia/Seoul", "/songs/years");
    mvc.perform(get("/api/v1/songs/years")).andExpect(status().isOk());
  }

  @Test
  void documentsSessionCsrfAndConcreteAdminResponses() throws Exception {
    var api = contract();
    assertThat(api.at("/paths/~1api~1v1~1songs/get/security").isEmpty()).isTrue();
    assertThat(api.at("/paths/~1api~1v1~1me/get/security/0").has("session")).isTrue();
    var change = api.at("/paths/~1api~1v1~1me~1playlists/post/security/0");
    assertThat(change.has("session") && change.has("csrf")).isTrue();
    assertThat(api.at("/components/securitySchemes/session/name").asText()).isEqualTo("SESSION");
    assertThat(api.at("/components/securitySchemes/csrf/name").asText()).isEqualTo("X-CSRF-TOKEN");
    assertThat(api.at("/paths/~1api~1v1~1admin~1songs/get/description").asText()).contains("ADMIN");
    for (var resource : new String[] {"members", "artists", "works", "songs", "channels"}) {
      var body =
          api.path("paths")
              .path("/api/v1/admin/" + resource + "/{id}")
              .path("get")
              .path("responses")
              .path("200")
              .path("content")
              .path("application/json")
              .path("schema");
      var schema = api.at(body.path("$ref").asText().substring(1));
      assertThat(schema.path("properties").has("id")).isTrue();
      assertThat(schema.path("properties").has("version")).isTrue();
    }
    var problem =
        api.at(
            "/paths/~1api~1v1~1me/get/responses/401/content/application~1problem+json/schema/$ref");
    assertThat(problem.asText()).isEqualTo("#/components/schemas/ApiProblem");
    var errors = api.at("/components/schemas/ApiProblem/properties/fieldErrors");
    assertThat(errors.has("maxItems")).isFalse();
    assertThat(errors.at("/items/required").toString()).contains("field", "code", "message");
    assertThat(errors.at("/items/properties/field/example").asText()).isEqualTo("links[0].url");
    assertThat(errors.at("/items/properties/code/enum").toString())
        .contains(
            "REQUIRED",
            "INVALID_FORMAT",
            "INVALID_SIZE",
            "OUT_OF_RANGE",
            "DUPLICATE",
            "INVALID_VALUE");
    assertThat(errors.path("description").asText())
        .contains("aliases[1]", "configuration.cover[0].text", "빈 배열", "비밀번호", "토큰");
  }

  @Test
  void documentsMemberProfileFieldsAndAbsentValues() throws Exception {
    var schemas = contract().path("components").path("schemas");
    for (var name :
        new String[] {
          "com.stelody.member.dto.MemberDtos.Card", "com.stelody.admin.dto.CatalogAdminDtos.Member"
        }) {
      var schema = schemas.path(name);
      for (var field : new String[] {"unitName", "chzzkUrl", "xUrl"}) {
        var property = schema.path("properties").path(field);
        assertThat(property.path("type").toString())
            .as(name + "." + field)
            .contains("string", "null");
        assertThat(property.path("description").asText()).contains("null");
        assertThat(schema.path("required").toString()).contains(field);
      }
    }
    var input = schemas.path("com.stelody.admin.dto.CatalogAdminDtos.MemberInput");
    for (var field : new String[] {"unitName", "chzzkUrl", "xUrl"}) {
      assertThat(input.path("required").toString()).doesNotContain(field);
      assertThat(input.path("properties").path(field).path("description").asText())
          .contains("기존 값 삭제");
    }
    assertThat(input.path("properties").path("unitName").path("maxLength").asInt()).isEqualTo(100);
    assertThat(input.path("properties").path("chzzkUrl").path("maxLength").asInt()).isEqualTo(2000);
    assertThat(
            schemas
                .path("com.stelody.member.dto.MemberDtos.Detail")
                .path("properties")
                .path("channels")
                .path("description")
                .asText())
        .contains("빈 배열", "null은 반환하지");
  }

  @Test
  void documentsOptionalPlaylistSongLookupAndNullableMembership() throws Exception {
    var api = contract();
    var operation = api.path("paths").path("/api/v1/me/playlists").path("get");
    var songParameter =
        java.util.stream.StreamSupport.stream(operation.path("parameters").spliterator(), false)
            .filter(parameter -> parameter.path("name").asText().equals("songId"))
            .findFirst()
            .orElseThrow();
    assertThat(songParameter.path("in").asText()).isEqualTo("query");
    assertThat(songParameter.path("required").asBoolean()).isFalse();
    assertThat(songParameter.path("schema").path("format").asText()).isEqualTo("uuid");
    assertThat(songParameter.path("description").asText()).contains("null", "이용 불가", "false");
    assertThat(operation.path("description").asText()).contains("필터링하지", "중복 검증");
    var summary =
        api.path("components")
            .path("schemas")
            .path("com.stelody.playlist.dto.PlaylistDtos.Summary");
    var membership = summary.path("properties").path("containsSong");
    assertThat(membership.path("type").toString()).contains("boolean", "null");
    assertThat(membership.path("description").asText()).contains("이용 불가", "상세", "null", "조회 시점");
    assertThat(summary.path("required").toString()).contains("containsSong");
  }

  @Test
  void documentsAdminSongListInformationSeparatelyFromOtherCatalogLists() throws Exception {
    var api = contract();
    var operation = api.path("paths").path("/api/v1/admin/songs").path("get");
    assertThat(operation.path("description").asText()).contains("발견", "공개일·등록일", "ADMIN", "정보 완성도");
    var ref = operation.at("/responses/200/content/application~1json/schema/$ref").asText();
    var page = api.at(ref.substring(1));
    var summary = api.at(page.at("/properties/items/items/$ref").asText().substring(1));
    for (var field :
        new String[] {
          "id",
          "name",
          "version",
          "status",
          "participants",
          "discoveredAt",
          "missingFields",
          "informationComplete"
        }) assertThat(summary.path("required").toString()).contains("\"" + field + "\"");
    assertThat(summary.at("/properties/status/enum").toString())
        .contains("DRAFT", "PUBLISHED", "HIDDEN");
    assertThat(summary.at("/properties/discoveredAt/type").toString()).contains("string", "null");
    assertThat(summary.at("/properties/discoveredAt/description").asText())
        .contains("first_seen_at", "null");
    assertThat(summary.at("/properties/informationComplete/description").asText())
        .contains("aliases", "모든 곡");
    var participant =
        api.at(summary.at("/properties/participants/items/$ref").asText().substring(1));
    assertThat(participant.at("/properties/kind/enum").toString()).contains("MEMBER", "EXTERNAL");
    assertThat(participant.path("required").toString()).contains("confirmed");
    var memberList =
        api.at(
                "/paths/~1api~1v1~1admin~1members/get/responses/200/content/application~1json/schema/$ref")
            .asText();
    assertThat(memberList).isNotEqualTo(ref);
  }

  @Test
  void documentsInboxCountDefinitionsAndMatchingFailureListWindow() throws Exception {
    var api = contract();
    var inbox = api.path("paths").path("/api/v1/admin/inbox").path("get");
    assertThat(inbox.path("description").asText()).contains("동일한 조건", "DB 스냅샷", "ADMIN");
    assertThat(inbox.path("security").get(0).has("session")).isTrue();
    var schema =
        api.at(
            inbox.at("/responses/200/content/application~1json/schema/$ref").asText().substring(1));
    assertThat(schema.path("required").size()).isEqualTo(6);
    for (String field :
        new String[] {
          "newReviewCount",
          "incompleteAutoRegistrationCount",
          "specialEventReviewCount",
          "collectionFailureCount"
        }) {
      assertThat(schema.path("required").toString()).contains(field);
      assertThat(schema.path("properties").path(field).path("minimum").asInt()).isZero();
      assertThat(schema.path("properties").path(field).path("description").asText())
          .contains("GET /api/v1/admin/");
    }
    assertThat(schema.at("/properties/incompleteAutoRegistrationCount/description").asText())
        .contains("자동 등록 곡만", "별칭은 선택");
    assertThat(schema.at("/properties/collectionFailureCount/description").asText())
        .contains("24시간", "작업 수", "SUCCEEDED", "실패 영상 수");
    var failures = api.path("paths").path("/api/v1/admin/collection-failures").path("get");
    assertThat(failures.path("description").asText())
        .contains("failureWindowStart", "checkedAt", "FAILED", "QUOTA_EXHAUSTED", "TIMED_OUT");
    for (String name : new String[] {"from", "to"}) {
      var parameter =
          java.util.stream.StreamSupport.stream(failures.path("parameters").spliterator(), false)
              .filter(p -> p.path("name").asText().equals(name))
              .findFirst()
              .orElseThrow();
      assertThat(parameter.path("required").asBoolean()).isFalse();
      assertThat(parameter.path("schema").path("format").asText()).isEqualTo("date-time");
    }
  }

  @Test
  void retainsDistinctPagesNullableDataAndBodyValidation() throws Exception {
    var api = contract();
    var schemas = api.path("components").path("schemas");
    var songs = schemas.path("com.stelody.song.dto.SongDtos.Page").path("properties");
    var playlists = schemas.path("com.stelody.playlist.dto.PlaylistDtos.Page").path("properties");
    var favoriteList = api.path("paths").path("/api/v1/me/favorites").path("get");
    assertThat(favoriteList.path("description").asText())
        .contains(
            "최근 저장순만 지원",
            "sort 파라미터는 제공하지",
            "savedAt 내림차순",
            "songId 내림차순",
            "이용 불가",
            "DB 스냅샷",
            "첫 페이지",
            "INVALID_FAVORITE_QUERY");
    var favoriteParameters = new java.util.ArrayList<String>();
    favoriteList.path("parameters").forEach(p -> favoriteParameters.add(p.path("name").asText()));
    assertThat(favoriteParameters).containsExactlyInAnyOrder("size", "cursor");
    for (var parameter : favoriteList.path("parameters"))
      if (parameter.path("name").asText().equals("cursor"))
        assertThat(parameter.path("description").asText())
            .contains("현재 계정", "최근 저장순", "size는 변경", "INVALID_FAVORITE_QUERY");
    var favoritePage =
        schemas.path("com.stelody.favorite.dto.FavoriteDtos.Page").path("properties");
    assertThat(favoritePage.path("items").path("description").asText())
        .contains("savedAt 내림차순", "songId 내림차순", "이용 불가");
    assertThat(favoritePage.path("nextCursor").path("description").asText())
        .contains("계정 전용", "다음 페이지가 없으면 null");
    assertThat(
            schemas
                .path("com.stelody.favorite.dto.FavoriteDtos.Item")
                .path("properties")
                .path("savedAt")
                .path("description")
                .asText())
        .contains("최근 저장순", "중복 저장");
    assertThat(
            api.path("paths")
                .path("/api/v1/me/favorites/{songId}")
                .path("put")
                .path("description")
                .asText())
        .contains("savedAt과 목록 순서를 유지", "해제 후 다시 저장");
    assertThat(songs.path("items").path("items").path("$ref").asText()).endsWith("SongDtos.Card");
    assertThat(playlists.path("items").path("items").path("$ref").asText())
        .endsWith("PlaylistDtos.Summary");
    assertThat(playlists.has("totalCount")).isTrue();
    assertThat(songs.path("totalCount").path("type").asText()).isEqualTo("integer");
    assertThat(songs.path("totalCount").path("format").asText()).isEqualTo("int64");
    assertThat(songs.path("totalCount").path("minimum").asLong()).isZero();
    assertThat(songs.path("totalCount").path("description").asText())
        .contains("전체 고유 곡 수", "커서와 무관", "스냅샷");
    assertThat(schemas.path("com.stelody.song.dto.SongDtos.Page").path("required").toString())
        .contains("items", "nextCursor", "hasNext", "totalCount");
    var card = schemas.path("com.stelody.song.dto.SongDtos.Card").path("properties");
    assertThat(card.path("viewCount").path("type").toString()).contains("integer", "null");
    assertThat(card.path("work").path("anyOf").toString()).contains("SongDtos.Work", "null");
    assertThat(card.path("work").has("$ref")).isFalse();
    var audit = schemas.path("com.stelody.admin.dto.CatalogAdminDtos.Audit").path("properties");
    assertThat(audit.path("before").path("type").toString()).contains("object", "array", "null");
    assertThat(audit.path("after").path("type").toString()).contains("object", "array", "null");
    assertThat(schemas.path("com.stelody.song.dto.SongDtos.Card").path("required").toString())
        .contains("id", "title", "work", "viewCount");
    var input = schemas.path("com.stelody.export.dto.ExportDtos.Create");
    assertThat(input.path("required").toString()).contains("requestId", "version");
    assertThat(input.path("properties").path("requestId").path("format").asText())
        .isEqualTo("uuid");
    assertThat(input.path("properties").path("version").path("minimum").asInt()).isZero();
    assertThat(api.at("/paths/~1api~1v1~1auth~1csrf/get").has("parameters")).isFalse();
    assertThat(schemas.has("com.stelody.auth.domain.SessionUser")).isFalse();
  }

  @Test
  void documentsWholePlaylistOrderAndVersionConflicts() throws Exception {
    var api = contract();
    var schemas = api.path("components").path("schemas");
    var list = api.path("paths").path("/api/v1/me/playlists/{id}/items").path("get");
    var order = api.path("paths").path("/api/v1/me/playlists/{id}/order").path("put");
    assertThat(list.path("description").asText())
        .contains("position 오름차순", "이용 불가", "hasNext=false", "모든 페이지", "같은 version", "카탈로그 공개 상태");
    assertThat(order.path("description").asText())
        .contains("전체 itemIds", "songId가 아닌", "이용 불가", "한 번씩", "version만 갱신", "version을 1 증가");
    for (var operation : java.util.List.of(list, order)) {
      assertThat(operation.at("/responses/400/description").asText())
          .contains("INVALID_PLAYLIST_REQUEST");
      assertThat(operation.at("/responses/409/description").asText())
          .contains("PLAYLIST_CHANGED", "전체 페이지를 다시 조회");
      assertThat(
              operation.at("/responses/409/content/application~1problem+json/schema/$ref").asText())
          .isEqualTo("#/components/schemas/ApiProblem");
    }
    for (var parameter : list.path("parameters"))
      if (parameter.path("name").asText().equals("cursor"))
        assertThat(parameter.path("description").asText())
            .contains("계정·목록·version", "PLAYLIST_CHANGED");
    var input = schemas.path("com.stelody.playlist.dto.PlaylistDtos.Order");
    assertThat(input.path("required").toString()).contains("itemIds", "version");
    assertThat(input.at("/properties/itemIds/uniqueItems").asBoolean()).isTrue();
    assertThat(input.at("/properties/itemIds/items/format").asText()).isEqualTo("uuid");
    assertThat(input.at("/properties/itemIds/description").asText())
        .contains("전체 목록 항목 id", "songId", "이용 불가", "빈 목록");
    assertThat(input.at("/properties/version/description").asText()).contains("전체 페이지", "1 증가");
    var page = schemas.path("com.stelody.playlist.dto.PlaylistDtos.Items");
    assertThat(page.at("/properties/version/description").asText()).contains("같은 version");
    assertThat(page.at("/properties/nextCursor/description").asText()).contains("PLAYLIST_CHANGED");
    var item = schemas.path("com.stelody.playlist.dto.PlaylistDtos.Item");
    assertThat(item.at("/properties/id/description").asText()).contains("itemIds", "songId와 구분");
    assertThat(item.at("/properties/position/minimum").asInt()).isZero();
  }

  @Test
  void documentsFrontendStateEnumsAndActionErrors() throws Exception {
    var api = contract();
    var schemas = api.path("components").path("schemas");
    String song = "com.stelody.song.dto.SongDtos.";
    String admin = "com.stelody.admin.dto.CatalogAdminDtos.";
    String export = "com.stelody.export.dto.ExportDtos.";
    for (var target : java.util.List.of(song + "Card", admin + "Song"))
      assertEnum(schemas.path(target).at("/properties/type"), "ORIGINAL", "COVER");
    for (var target : java.util.List.of("com.stelody.member.dto.MemberDtos.Card", admin + "Member"))
      assertEnum(schemas.path(target).at("/properties/activityStatus"), "ACTIVE", "GRADUATED");
    assertEnum(
        schemas.path(song + "SearchHelp").at("/properties/visibility"),
        "UNCHECKED",
        "NORMAL",
        "DIFFICULT");
    assertEnum(
        schemas.path(admin + "Song").at("/properties/searchVisibility"),
        "UNCHECKED",
        "NORMAL",
        "DIFFICULT");
    assertEnum(
        schemas.path(admin + "Song").at("/properties/visibility"), "DRAFT", "PUBLISHED", "HIDDEN");
    assertEnum(
        schemas.path(export + "Connection").at("/properties/status"),
        "DISABLED",
        "DISCONNECTED",
        "CONNECTED",
        "RECONNECT_REQUIRED",
        "REVOKING");
    var job = schemas.path(export + "Job");
    assertEnum(
        job.at("/properties/status"),
        "QUEUED",
        "RUNNING",
        "SUCCEEDED",
        "FAILED",
        "UNCERTAIN",
        "CANCELLED");
    assertThat(job.at("/properties/status/description").asText())
        .contains("건너뛴", "EXPORT_NOT_RETRYABLE", "삭제하지");
    assertThat(job.at("/properties/version/description").asText()).contains("원본 플레이리스트", "작업 자체");
    assertThat(job.at("/properties/remainingCount/description").asText()).contains("이미 반영");
    assertThat(job.at("/properties/errorCode/description").asText())
        .contains(
            "ApiProblem.code",
            "YOUTUBE_RESULT_UNCONFIRMED",
            "REMOTE_PLAYLIST_CHANGED",
            "YOUTUBE_TOKEN_UNAVAILABLE",
            "연결을 유지",
            "잠시 후 같은 작업의 retry");
    var retry = api.path("paths").path("/api/v1/me/youtube-exports/{id}/retry").path("post");
    assertThat(retry.path("description").asText()).contains("외부", "대조", "CANCELLED", "작업 완료");
    var cancel = api.path("paths").path("/api/v1/me/youtube-exports/{id}/cancel").path("post");
    assertThat(cancel.path("description").asText()).contains("SUCCEEDED", "진행 중인", "삭제하지");
    assertErrorCodes(api, "/api/v1/songs", "get", "409", "VIEW_PUBLICATION_CHANGED");
    assertErrorCodes(api, "/api/v1/members/{id}/songs", "get", "409", "VIEW_PUBLICATION_CHANGED");
    assertErrorCodes(api, "/api/v1/me/favorites/{songId}", "put", "409", "FAVORITE_LIMIT_REACHED");
    assertErrorCodes(api, "/api/v1/me/playlists", "post", "409", "PLAYLIST_LIMIT_REACHED");
    assertErrorCodes(
        api,
        "/api/v1/me/playlists/{id}/items",
        "post",
        "409",
        "PLAYLIST_CHANGED",
        "PLAYLIST_SONG_ALREADY_EXISTS",
        "PLAYLIST_ITEM_LIMIT_REACHED");
    assertErrorCodes(
        api,
        "/api/v1/admin/songs/{id}",
        "put",
        "409",
        "CATALOG_VERSION_CONFLICT",
        "CATALOG_DATA_CONFLICT");
    assertErrorCodes(
        api,
        "/api/v1/me/playlists/{id}/youtube-exports",
        "post",
        "409",
        "PLAYLIST_CHANGED",
        "YOUTUBE_CONNECTION_REQUIRED",
        "EXPORT_REQUEST_REUSED",
        "EXPORT_ALREADY_ACTIVE",
        "NO_EXPORTABLE_SONGS");
    assertErrorCodes(
        api,
        "/api/v1/me/youtube-exports/{id}/retry",
        "post",
        "409",
        "EXPORT_NOT_RETRYABLE",
        "YOUTUBE_CONNECTION_REQUIRED");
    assertThat(schemas.path("ApiProblem").at("/properties/code/description").asText())
        .contains("x-error-codes", "fieldErrors", "Job.errorCode", "새 코드");
  }

  private static void assertParameterEnum(JsonNode schema, String... values) {
    assertThat(schema.path("type").asText()).isEqualTo("string");
    assertThat(schema.path("enum").valueStream().map(JsonNode::asText).toList())
        .containsExactly(values);
    assertThat(schema.has("maxLength")).isFalse();
  }

  private static void assertEnum(JsonNode schema, String... values) {
    var actual = new java.util.ArrayList<String>();
    schema.path("enum").forEach(value -> actual.add(value.asText()));
    assertThat(actual).containsExactly(values);
    assertThat(schema.path("description").asText()).isNotBlank();
  }

  @Test
  void nullableStatusEnumsAcceptExternalParticipantsAndUnclassifiedAdminResources()
      throws Exception {
    var schemas = contract().path("components").path("schemas");
    for (var property :
        java.util.List.of(
            schemas
                .path("com.stelody.song.dto.SongDtos.Participant")
                .at("/properties/activityStatus"),
            schemas
                .path("com.stelody.admin.dto.CatalogAdminDtos.Summary")
                .at("/properties/status"))) {
      assertThat(property.path("type").toString()).contains("string", "null");
      assertThat(property.path("enum").valueStream().anyMatch(JsonNode::isNull)).isTrue();
      assertThat(property.path("description").asText()).contains("null");
    }
  }

  private static void assertErrorCodes(
      JsonNode api, String path, String method, String status, String... codes) {
    var response = api.path("paths").path(path).path(method).path("responses").path(status);
    var actual = new java.util.ArrayList<String>();
    response.path("x-error-codes").forEach(value -> actual.add(value.asText()));
    assertThat(actual).as(path + " " + method + " " + status).containsExactly(codes);
    assertThat(response.path("description").asText()).contains(codes);
    assertThat(response.at("/content/application~1problem+json/schema/$ref").asText())
        .isEqualTo("#/components/schemas/ApiProblem");
  }

  @Test
  void documentsActualStatusesAndUniqueStableOperationIds() throws Exception {
    var api = contract();
    var ids = new java.util.HashSet<String>();
    api.path("paths")
        .properties()
        .forEach(
            path ->
                path.getValue()
                    .properties()
                    .forEach(
                        method -> {
                          assertThat(method.getValue().path("summary").asText()).isNotBlank();
                          assertThat(ids.add(method.getValue().path("operationId").asText()))
                              .as(path.getKey() + " " + method.getKey())
                              .isTrue();
                        }));
    assertThat(api.at("/paths/~1api~1v1~1me/delete/responses").has("204")).isTrue();
    assertThat(api.at("/paths/~1api~1v1~1me/delete/responses").has("200")).isFalse();
    assertThat(api.at("/paths/~1api~1v1~1me~1playlists/post/responses").has("201")).isTrue();
    assertThat(
            api.at("/paths/~1api~1v1~1me~1playlists~1{id}~1youtube-exports/post/responses")
                .has("202"))
        .isTrue();
    assertThat(api.at("/paths/~1api~1v1~1me~1youtube~1callback/get/responses").has("303")).isTrue();
    assertThat(api.at("/paths/~1api~1v1~1auth~1google/get/responses").has("302")).isTrue();
    var size =
        api.path("paths")
            .path("/api/v1/songs/recommendations")
            .path("get")
            .path("parameters")
            .get(0)
            .path("schema");
    assertThat(size.path("minimum").asInt()).isEqualTo(1);
    assertThat(size.path("maximum").asInt()).isEqualTo(20);
    assertThat(size.path("default").asInt()).isEqualTo(6);
    mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
  }

  @Test
  void youtubeAuthorizationDocumentsOptionalInputAndAllFrontendReturnOutcomes() throws Exception {
    var api = contract();
    var start = api.path("paths").path("/api/v1/me/youtube/authorizations").path("post");
    assertThat(start.path("requestBody").path("required").asBoolean()).isFalse();
    var schema = start.path("requestBody").path("content").path("application/json").path("schema");
    if (schema.has("$ref")) schema = api.at(schema.path("$ref").asText().substring(1));
    var properties = schema.path("properties");
    assertThat(properties.path("returnTo").path("default").asText()).isEqualTo("/library/youtube");
    assertThat(properties.path("returnTo").path("maxLength").asInt()).isEqualTo(2048);
    assertThat(properties.path("callbackClient").path("default").asText()).isEqualTo("default");
    assertThat(properties.path("callbackClient").path("enum").toString())
        .contains("default", "local");
    for (var path :
        new String[] {"/api/v1/me/youtube/callback", "/api/v1/me/youtube/callback/local"}) {
      var callback = api.path("paths").path(path).path("get");
      assertThat(callback.path("security").isEmpty()).isTrue();
      assertThat(callback.path("responses").has("401")).isFalse();
      var response = callback.path("responses").path("303");
      assertThat(response.has("content")).isFalse();
      assertThat(response.path("description").asText())
          .contains(
              "youtubeResult=success|cancelled|failed",
              "SESSION_EXPIRED",
              "INVALID_YOUTUBE_STATE",
              "YOUTUBE_CONSENT_DENIED",
              "YOUTUBE_AUTHORIZATION_FAILED");
      assertThat(response.path("headers").path("Location").path("description").asText())
          .contains("/library/youtube", "http://localhost:5173");
    }
  }

  @Test
  void servesSwaggerAssetsWithoutOpeningAccountOrAdministration() throws Exception {
    mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    mvc.perform(get("/swagger-ui/swagger-ui-bundle.js")).andExpect(status().isOk());
    mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
    mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/admin/members")).andExpect(status().isUnauthorized());
  }
}
