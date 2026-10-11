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
  void servesSwaggerAssetsWithoutOpeningAccountOrAdministration() throws Exception {
    mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    mvc.perform(get("/swagger-ui/swagger-ui-bundle.js")).andExpect(status().isOk());
    mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
    mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/admin/members")).andExpect(status().isUnauthorized());
  }
}
