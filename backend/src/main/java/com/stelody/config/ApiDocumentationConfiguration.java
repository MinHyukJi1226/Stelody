package com.stelody.config;

import com.stelody.admin.dto.CatalogAdminDtos;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.*;
import io.swagger.v3.oas.models.security.*;
import io.swagger.v3.oas.models.servers.Server;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

@Configuration
@Profile("local | test | prod")
public class ApiDocumentationConfiguration {
  @Bean
  OpenAPI stelodyOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Stelody API")
                .version("v1")
                .description(
                    "공개 곡·멤버 조회는 로그인 없이 사용합니다. 개인 API는 Google 로그인 후 SESSION 쿠키, 관리자 API는 현재 ADMIN 역할이 필요합니다. 변경 요청은 /api/v1/auth/csrf에서 받은 토큰을 X-CSRF-TOKEN 헤더로 전달합니다. 로그인·로그아웃 후 토큰을 다시 조회하세요. Google·YouTube 동의는 브라우저 이동으로 진행하며 OAuth 비밀키를 클라이언트에 전달하지 않습니다."))
        .servers(List.of(new Server().url("/")))
        .components(
            new Components()
                .addSecuritySchemes(
                    "session",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name("SESSION")
                        .description("Google 로그인으로 설정되는 HttpOnly 쿠키. 같은 브라우저의 세션을 사용합니다."))
                .addSecuritySchemes(
                    "csrf",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-CSRF-TOKEN")
                        .description("GET /api/v1/auth/csrf의 token. 로그인·로그아웃 후 새로 조회합니다.")));
  }

  @Bean
  OpenApiCustomizer apiContract() {
    return api -> {
      api.getComponents().addSchemas("ApiProblem", problemSchema());
      api.getPaths()
          .addPathItem(
              "/api/v1/auth/logout",
              new PathItem()
                  .post(
                      new Operation()
                          .summary("로그아웃")
                          .responses(
                              new ApiResponses()
                                  .addApiResponse(
                                      "204",
                                      new ApiResponse().description("세션과 SESSION 쿠키를 제거합니다.")))));
      api.getPaths()
          .forEach(
              (path, item) ->
                  item.readOperationsMap()
                      .forEach(
                          (method, operation) -> {
                            String verb = method.name();
                            boolean mutation = !verb.equals("GET");
                            boolean personal =
                                path.equals("/api/v1/me") || path.startsWith("/api/v1/me/");
                            boolean admin = path.startsWith("/api/v1/admin/");
                            operation.setOperationId(
                                verb.toLowerCase()
                                    + "_"
                                    + path.substring(8)
                                        .replaceAll("[^a-zA-Z0-9]+", "_")
                                        .replaceAll("_$", ""));
                            operation.setTags(
                                List.of(
                                    admin
                                        ? "관리자"
                                        : personal
                                            ? "개인 계정·목록"
                                            : path.startsWith("/api/v1/auth/") ? "인증" : "공개 카탈로그"));
                            operation.setSecurity(
                                personal || admin || mutation
                                    ? List.of(security(personal || admin, mutation))
                                    : List.of());
                            String description =
                                operation.getDescription() == null
                                    ? ""
                                    : operation.getDescription() + "\n\n";
                            if (admin) description += "현재 ADMIN 역할이 필요합니다. ";
                            if (personal) description += "현재 로그인 계정의 자료만 접근합니다. ";
                            if (mutation) description += "X-CSRF-TOKEN 헤더가 필요합니다. ";
                            operation.setDescription(
                                description
                                    + "업무 오류는 application/problem+json의 code와 traceId로 확인합니다.");
                            operation
                                .getResponses()
                                .addApiResponse(
                                    "default",
                                    problem("요청 검증·업무 조건·외부 연동 오류. HTTP 상태와 code를 확인하세요."));
                            if (personal || admin)
                              operation
                                  .getResponses()
                                  .addApiResponse("401", problem("로그인 세션이 없거나 유효하지 않습니다."));
                            if (admin || mutation)
                              operation
                                  .getResponses()
                                  .addApiResponse("403", problem("권한 또는 CSRF 검증에 실패했습니다."));
                            correctSuccess(path, verb, operation);
                            playlistOrdering(path, verb, operation);
                            operation
                                .getResponses()
                                .values()
                                .forEach(
                                    response -> {
                                      if (response.getContent() != null
                                          && response.getContent().containsKey("*/*")) {
                                        var body = response.getContent().remove("*/*");
                                        response
                                            .getContent()
                                            .addMediaType("application/json", body);
                                      }
                                    });
                            if (operation.getParameters() != null)
                              operation
                                  .getParameters()
                                  .forEach(parameter -> queryConstraints(path, parameter));
                          }));
      Map<String, Class<?>> details =
          Map.of(
              "members",
              CatalogAdminDtos.Member.class,
              "artists",
              CatalogAdminDtos.Artist.class,
              "works",
              CatalogAdminDtos.Work.class,
              "songs",
              CatalogAdminDtos.Song.class,
              "channels",
              CatalogAdminDtos.Channel.class);
      details.forEach(
          (resource, type) -> {
            var resolved =
                ModelConverters.getInstance(true)
                    .resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(true));
            resolved.referencedSchemas.forEach(api.getComponents()::addSchemas);
            api.getPaths()
                .get("/api/v1/admin/" + resource + "/{id}")
                .getGet()
                .getResponses()
                .get("200")
                .content(
                    new Content()
                        .addMediaType("application/json", new MediaType().schema(resolved.schema)));
          });
      var login = api.getPaths().get("/api/v1/auth/google").getGet();
      login.addParametersItem(
          new Parameter()
              .name("returnTo")
              .in("query")
              .required(false)
              .schema(new StringSchema().maxLength(2048))
              .description(
                  "사이트 내부 UI 절대 경로. 외부 주소·API·actuator 경로는 허용하지 않습니다. 생략하면 /api/v1/me로 이동합니다."));
      login.getResponses().addApiResponse("503", problem("Google 로그인 설정이 없습니다."));
      login
          .getResponses()
          .get("302")
          .headers(
              Map.of(
                  "Location",
                  new Header().schema(new StringSchema()).description("Google 로그인 시작 주소")));
      api.getComponents()
          .getSchemas()
          .values()
          .forEach(ApiDocumentationConfiguration::normalizeNullableReferences);
    };
  }

  // OpenAPI 3.1 applies $ref siblings conjunctively. A nullable reference needs a union.
  private static void normalizeNullableReferences(Schema<?> schema) {
    if (schema.get$ref() != null
        && (Boolean.TRUE.equals(schema.getNullable())
            || schema.getTypes() != null && schema.getTypes().contains("null"))) {
      String reference = schema.get$ref();
      schema.set$ref(null);
      schema.setType(null);
      schema.setTypes(null);
      schema.setNullable(null);
      schema.setAnyOf(
          List.of(new Schema<>().$ref(reference), new Schema<>().types(java.util.Set.of("null"))));
    }
    if (schema.getProperties() != null)
      schema
          .getProperties()
          .values()
          .forEach(ApiDocumentationConfiguration::normalizeNullableReferences);
    if (schema.getItems() != null) normalizeNullableReferences(schema.getItems());
    if (schema.getAllOf() != null)
      schema.getAllOf().forEach(ApiDocumentationConfiguration::normalizeNullableReferences);
    if (schema.getAnyOf() != null)
      schema.getAnyOf().forEach(ApiDocumentationConfiguration::normalizeNullableReferences);
    if (schema.getOneOf() != null)
      schema.getOneOf().forEach(ApiDocumentationConfiguration::normalizeNullableReferences);
  }

  private static Schema<?> problemSchema() {
    return new ObjectSchema()
        .addProperty("type", new StringSchema().example("about:blank"))
        .addProperty("title", new StringSchema())
        .addProperty("status", new IntegerSchema())
        .addProperty("instance", new StringSchema())
        .addProperty("code", new StringSchema().description("기능별 오류 코드. 상세 의미는 각 API 계약을 참고합니다."))
        .addProperty(
            "fieldErrors",
            new ArraySchema()
                .items(
                    new ObjectSchema()
                        .addProperty(
                            "field",
                            new StringSchema()
                                .description("요청 필드 또는 파라미터 경로. 점은 객체 속성, [n]은 0부터 시작하는 배열 인덱스입니다.")
                                .example("links[0].url"))
                        .addProperty(
                            "code",
                            new StringSchema()
                                ._enum(
                                    List.of(
                                        "REQUIRED",
                                        "INVALID_FORMAT",
                                        "INVALID_SIZE",
                                        "OUT_OF_RANGE",
                                        "DUPLICATE",
                                        "INVALID_VALUE")))
                        .addProperty(
                            "message", new StringSchema().description("입력값을 포함하지 않는 고정 안내 문구"))
                        .required(List.of("field", "code", "message")))
                .description(
                    "DTO·타입 검증과 관리자 폼의 추가 검증 오류를 field/code/message로 반환합니다. REQUIRED: 필수, INVALID_FORMAT: 형식, INVALID_SIZE: 길이·개수, OUT_OF_RANGE: 범위, DUPLICATE: 중복, INVALID_VALUE: 기타 값 오류. links[0].url, aliases[1], configuration.cover[0].text 형식이며 field·code 순서로 정렬합니다. 파싱이 불가능한 JSON이나 특정 입력칸에 연결할 수 없는 업무·인증 오류는 빈 배열입니다. 입력값·비밀번호·토큰·내부 예외 메시지는 포함하지 않습니다."))
        .addProperty("traceId", new StringSchema().format("uuid"))
        .required(List.of("type", "title", "status", "instance", "code", "fieldErrors", "traceId"));
  }

  private static SecurityRequirement security(boolean session, boolean csrf) {
    var requirement = new SecurityRequirement();
    if (session) requirement.addList("session");
    if (csrf) requirement.addList("csrf");
    return requirement;
  }

  private static ApiResponse problem(String description) {
    return new ApiResponse()
        .description(description)
        .content(
            new Content()
                .addMediaType(
                    "application/problem+json",
                    new MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/ApiProblem"))));
  }

  private static void correctSuccess(String path, String verb, Operation operation) {
    String status = null;
    if (verb.equals("DELETE") && path.equals("/api/v1/me")) status = "204";
    else if (path.equals("/api/v1/auth/google")) status = "302";
    else if (path.equals("/api/v1/me/youtube/callback")) status = "303";
    else if (verb.equals("POST")
        && (path.endsWith("/youtube-exports") || path.endsWith("/retries"))) status = "202";
    else if (verb.equals("POST")
        && (path.equals("/api/v1/me/playlists")
            || path.matches("/api/v1/admin/(members|artists|works|songs|channels)")
            || path.endsWith("/registration")
            || path.endsWith("/videos"))) status = "201";
    if (status != null) {
      var response = operation.getResponses().remove("200");
      operation
          .getResponses()
          .addApiResponse(
              status, response == null ? new ApiResponse().description("성공") : response);
      if (status.startsWith("3") || status.equals("204"))
        operation.getResponses().get(status).content(null);
      if (status.equals("303") || path.equals("/api/v1/me/playlists") || status.equals("202"))
        operation
            .getResponses()
            .get(status)
            .addHeaderObject(
                "Location",
                new Header().schema(new StringSchema()).description("이동 대상 또는 생성한 리소스의 상대 URL"));
    }
  }

  private static void playlistOrdering(String path, String verb, Operation operation) {
    if (!(verb.equals("GET") && path.equals("/api/v1/me/playlists/{id}/items"))
        && !(verb.equals("PUT") && path.equals("/api/v1/me/playlists/{id}/order"))) return;
    operation
        .getResponses()
        .addApiResponse(
            "400",
            problem(
                "INVALID_PLAYLIST_REQUEST: 잘못된 입력·커서 또는 전체 항목 순서입니다. 순서 저장 시 누락·중복·다른 목록 항목을 포함할 수 없습니다."))
        .addApiResponse(
            "409",
            problem(
                "PLAYLIST_CHANGED: 조회·편집 중 목록 version이 변경되었습니다. 전체 페이지를 다시 조회하고 편집 내용을 확인하세요. version만 갱신해 기존 순서를 재전송하지 마세요."));
  }

  @SuppressWarnings("unchecked") // Swagger exposes raw schemas on Parameter.
  private static void queryConstraints(String path, Parameter parameter) {
    var schema = parameter.getSchema();
    if (schema == null) return;
    if (parameter.getName().equals("kind") && path.contains("/collection-"))
      schema.setEnum(List.of("VIDEO", "DISCOVERY"));
    if (!"query".equals(parameter.getIn())) return;
    switch (parameter.getName()) {
      case "size" -> {
        schema.setMinimum(BigDecimal.ONE);
        schema.setMaximum(BigDecimal.valueOf(path.endsWith("/recommendations") ? 20 : 50));
      }
      case "page" -> {
        schema.setMinimum(BigDecimal.ZERO);
        schema.setMaximum(BigDecimal.valueOf(10000));
      }
      case "version", "songVersion" -> schema.setMinimum(BigDecimal.ZERO);
      case "cursor" -> {
        if (path.equals("/api/v1/me/favorites"))
          parameter.setDescription(
              "직전 응답의 nextCursor를 그대로 전달합니다. 현재 계정의 최근 저장순 커서이며 size는 변경할 수 있습니다. 새 저장 항목은 첫 페이지를 새로 조회해 확인합니다. 잘못된 커서·다른 계정의 커서는 400 INVALID_FAVORITE_QUERY입니다.");
        else if (path.equals("/api/v1/me/playlists/{id}/items"))
          parameter.setDescription(
              "직전 응답의 nextCursor를 그대로 전달합니다. 현재 계정·목록·version에 묶이며 size는 변경할 수 있습니다. 목록 변경 시 409 PLAYLIST_CHANGED이므로 첫 페이지부터 다시 조회합니다.");
        else parameter.setDescription("직전 응답의 nextCursor를 그대로 전달합니다. 필터와 정렬 조건을 유지합니다.");
      }
      case "days" -> schema.setEnum(List.of(7, 30));
      case "year" -> {
        schema.setMinimum(BigDecimal.valueOf(1900));
        schema.setMaximum(BigDecimal.valueOf(2100));
        parameter.setDescription(
            "대표 영상 공개일의 Asia/Seoul 기준 연도입니다. 전체 연도 선택지는 GET /api/v1/songs/years에서 조회합니다.");
      }
      case "q" -> schema.setMaxLength(200);
      case "type" -> {
        if (!path.contains("/admin/")) schema.setEnum(List.of("ALL", "COVER", "ORIGINAL"));
      }
      case "sort" -> {
        schema.setEnum(List.of("LATEST", "RELEVANCE", "VIEWS"));
        parameter.setDescription("생략 시 검색어가 있으면 RELEVANCE, 없으면 LATEST입니다.");
      }
      case "mode" -> {
        schema.setEnum(List.of("BROWSE", "SEARCH"));
        parameter.setDescription("생략 시 q 미지정은 BROWSE, q 지정은 SEARCH입니다. BROWSE에 검색어는 지정할 수 없습니다.");
      }
      case "status" -> {
        if (path.equals("/api/v1/members")) schema.setEnum(List.of("ALL", "ACTIVE", "GRADUATED"));
        else if (path.equals("/api/v1/admin/reviews"))
          schema.setEnum(List.of("PENDING", "IGNORED", "REGISTERED"));
        else if (path.equals("/api/v1/admin/collection-runs"))
          schema.setEnum(List.of("RUNNING", "SUCCEEDED", "FAILED", "QUOTA_EXHAUSTED", "TIMED_OUT"));
      }
      case "disposition" -> schema.setEnum(List.of("REVIEW", "EXCLUDED", "DEFERRED"));
      case "memberIds" -> {
        schema.setMaxItems(20);
        parameter.setDescription("참여 멤버 ID. 중복을 제외하고 최대 20개입니다.");
      }
      default -> {}
    }
  }
}
