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
    assertThat(api.at("/components/schemas/ApiProblem/properties/fieldErrors/maxItems").asInt())
        .isZero();
  }

  @Test
  void retainsDistinctPagesNullableDataAndBodyValidation() throws Exception {
    var api = contract();
    var schemas = api.path("components").path("schemas");
    var songs = schemas.path("com.stelody.song.dto.SongDtos.Page").path("properties");
    var playlists = schemas.path("com.stelody.playlist.dto.PlaylistDtos.Page").path("properties");
    assertThat(songs.path("items").path("items").path("$ref").asText()).endsWith("SongDtos.Card");
    assertThat(playlists.path("items").path("items").path("$ref").asText())
        .endsWith("PlaylistDtos.Summary");
    assertThat(playlists.has("totalCount")).isTrue();
    assertThat(songs.has("totalCount")).isFalse();
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
