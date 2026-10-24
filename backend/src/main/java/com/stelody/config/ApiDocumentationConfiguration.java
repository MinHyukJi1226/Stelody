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
                    "공개 곡·멤버 조회는 로그인 없이 사용합니다. 개인 API는 Google 로그인 후 SESSION 쿠키, 관리자 API는 현재 ADMIN 역할이 필요합니다. 변경 요청은 /api/v1/auth/csrf에서 받은 토큰을 X-CSRF-TOKEN 헤더로 전달합니다. 로그인·로그아웃 후 토큰을 다시 조회하세요. 일반 USER는 Secure·HttpOnly·SameSite=Lax의 STELODY_LOGIN 쿠키로 최초 Google 로그인부터 30일간 세션을 자동 복구합니다. 자동 복구·탈퇴 재인증은 30일 기한을 연장하지 않습니다. ADMIN은 자동 로그인 없이 비활동 30분·최대 12시간 세션만 사용합니다. 로그인 저장소 장애는 503 LOGIN_STORAGE_UNAVAILABLE이며 로그아웃 또는 작업 성공으로 간주하지 않습니다. Google·YouTube 동의는 브라우저 이동으로 진행하며 OAuth 비밀키를 클라이언트에 전달하지 않습니다."))
        .servers(List.of(new Server().url("/")))
        .components(
            new Components()
                .addSecuritySchemes(
                    "session",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name("SESSION")
                        .description(
                            "Google 로그인으로 설정되는 HttpOnly 세션 쿠키. 비활동 30분·생성 후 최대 12시간입니다. 일반 USER는 별도 STELODY_LOGIN 쿠키가 유효하면 다음 API 요청에서 새 세션을 자동 생성합니다. 브라우저가 쿠키를 관리하며 프론트에서 값을 읽거나 저장하지 않습니다. 관리자·만료·폐기된 쿠키는 복구하지 않습니다."))
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
                                      new ApiResponse()
                                          .description(
                                              "현재 기기의 로그인 유지 자격과 SESSION·STELODY_LOGIN 쿠키를 폐기합니다. 같은 자격으로 복구된 세션도 다음 요청부터 무효입니다. 다른 기기는 유지합니다. 저장소 실패 시 503이며 로그아웃 완료로 표시하지 말고 재시도합니다.")))));
      for (String callback :
          List.of(
              com.stelody.auth.domain.ReauthenticationReturn.CALLBACK,
              com.stelody.auth.domain.ReauthenticationReturn.LOCAL_CALLBACK)) {
        api.getPaths()
            .addPathItem(
                callback,
                new PathItem()
                    .get(
                        new Operation()
                            .summary("회원 탈퇴 Google 재인증 콜백")
                            .description(
                                "브라우저 전용 OAuth 콜백. 새 프론트 복귀 흐름은 303 Location으로 복귀합니다. reauthResult=success/cancelled/failed. 취소·실패 시 reauthError는 SESSION_EXPIRED, REAUTHENTICATION_REQUEST_INVALID, REAUTHENTICATION_REQUEST_EXPIRED, REAUTHENTICATION_ACCOUNT_MISMATCH, GOOGLE_REAUTHENTICATION_CANCELLED, GOOGLE_REAUTHENTICATION_FAILED, ACCOUNT_STORAGE_UNAVAILABLE 중 하나입니다. 결과는 안내용이며 GET /api/v1/me/withdrawal-confirmation으로 실제 재인증 여부·expiresAt을 확인하고 CSRF를 다시 조회합니다. 최종 버튼에서만 DELETE /api/v1/me를 호출합니다. 인증 코드·state·토큰·내부 오류 원문을 복귀 URL로 전달하지 않습니다. 저장된 returnTo를 확인할 수 없는 잘못된 state·세션 만료는 기본 /api/v1/me/withdrawal-confirmation으로 복귀합니다. local은 등록된 localhost origin, default는 GOOGLE_REAUTHENTICATION_FRONTEND_ORIGIN 또는 같은 origin을 사용합니다. 콜백은 세션을 자동 복구하지 않으며 재인증은 30일 로그인 유지 기한을 연장하지 않습니다.")
                            .parameters(
                                List.of(
                                    new Parameter()
                                        .name("state")
                                        .in("query")
                                        .schema(new StringSchema()),
                                    new Parameter()
                                        .name("code")
                                        .in("query")
                                        .schema(new StringSchema()),
                                    new Parameter()
                                        .name("error")
                                        .in("query")
                                        .schema(new StringSchema())))
                            .responses(
                                new ApiResponses()
                                    .addApiResponse(
                                        "303",
                                        new ApiResponse()
                                            .description(
                                                "검증된 프론트 복귀 주소. reauthResult와 허용 reauthError만 추가합니다.")
                                            .headers(
                                                Map.of(
                                                    "Location",
                                                    new Header()
                                                        .schema(new StringSchema())
                                                        .description(
                                                            "서버에 저장된 내부 경로·등록된 origin으로만 복귀"))))
                                    .addApiResponse(
                                        "503",
                                        problem(
                                            "GOOGLE_REAUTHENTICATION_UNAVAILABLE: 콜백 설정을 확인할 수 없습니다.")))));
      }
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
                                    problem(
                                        "요청 검증·업무 조건·외부 연동 오류. HTTP 상태와 code를 확인하세요. 로그인 상태 확인·복구 중 저장소 장애는 모든 API에서 503 LOGIN_STORAGE_UNAVAILABLE일 수 있으며 로그인 해제나 업무 성공으로 처리하지 않습니다."));
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
                            frontendErrors(path, verb, operation);
                            youtubeReturn(path, operation);
                            reauthenticationContract(path, verb, operation);
                            if (List.of("/api/v1/me", "/api/v1/auth/csrf", "/api/v1/auth/logout")
                                .contains(path))
                              errorCodes(
                                  operation,
                                  "503",
                                  "로그인 확인·복구·폐기 저장소 오류입니다. 로그인 해제나 변경 완료로 처리하지 말고 재시도합니다.",
                                  "LOGIN_STORAGE_UNAVAILABLE");
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
      login.addParametersItem(
          new Parameter()
              .name("loginClient")
              .in("query")
              .required(false)
              .schema(new StringSchema()._enum(java.util.List.of("local")))
              .description(
                  "생략하면 기존 운영 콜백을 사용합니다. local은 GOOGLE_LOCAL_REDIRECT_URI로 서버에 등록된 localhost/127.0.0.1 개발 프록시 콜백만 선택합니다. 임의 URL·중복 값·미설정 상태는 400 INVALID_LOGIN_CLIENT입니다. 로그인 시작과 콜백 모두 같은 로컬 /api 프록시를 사용해야 합니다. returnTo는 계속 내부 경로만 허용하며 로그인 복귀도 선택된 로컬 origin을 사용합니다. YouTube 연결·탈퇴 재인증에는 적용되지 않습니다."));
      login
          .getResponses()
          .addApiResponse(
              "400",
              problem(
                  "INVALID_LOGIN_CLIENT: 알 수 없거나 비활성인 loginClient 또는 중복 값입니다. INVALID_LOGIN_RETURN: 허용하지 않는 내부 복귀 경로입니다."));
      login
          .getResponses()
          .addApiResponse(
              "503",
              problem(
                  "GOOGLE_LOGIN_UNAVAILABLE: Google 로그인 설정이 없습니다. LOGIN_STORAGE_UNAVAILABLE: 로그인 유지 정보 저장 실패입니다."));
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
    allowNullableEnum(schema);
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

  // A nullable type still rejects null when enum lists only strings in OpenAPI 3.1.
  @SuppressWarnings({"rawtypes", "unchecked"}) // Schema<?> cannot accept the extra null enum value.
  private static void allowNullableEnum(Schema<?> schema) {
    if (schema.getEnum() != null
        && schema.getEnum().stream().noneMatch(java.util.Objects::isNull)
        && (Boolean.TRUE.equals(schema.getNullable())
            || schema.getTypes() != null && schema.getTypes().contains("null"))) {
      var values = new java.util.ArrayList<Object>(schema.getEnum());
      values.add(null);
      ((Schema) schema).setEnum(values);
    }
  }

  private static Schema<?> problemSchema() {
    return new ObjectSchema()
        .addProperty("type", new StringSchema().example("about:blank"))
        .addProperty("title", new StringSchema())
        .addProperty("status", new IntegerSchema())
        .addProperty("instance", new StringSchema())
        .addProperty(
            "code",
            new StringSchema()
                .description(
                    "기능별 오류 코드. 각 API의 오류 응답 description·x-error-codes와 HTTP status로 분기합니다. fieldErrors[].code와 내보내기 Job.errorCode는 별도입니다. 새 코드는 추가될 수 있으므로 알 수 없는 코드는 일반 오류로 안내하고 traceId를 보관합니다."))
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
                                        "INVALID_VALUE",
                                        "INVALID_DATE",
                                        "DATE_OUT_OF_RANGE",
                                        "INVALID_DATE_RANGE",
                                        "CONFLICTING_FILTER")))
                        .addProperty(
                            "message", new StringSchema().description("입력값을 포함하지 않는 고정 안내 문구"))
                        .required(List.of("field", "code", "message")))
                .description(
                    "DTO·타입 검증과 관리자 폼의 추가 검증 오류를 field/code/message로 반환합니다. REQUIRED: 필수, INVALID_FORMAT: 형식, INVALID_SIZE: 길이·개수, OUT_OF_RANGE: 범위, DUPLICATE: 중복, INVALID_VALUE: 기타 값 오류. links[0].url, aliases[1], configuration.cover[0].text 형식이며 field·code 순서로 정렬합니다. 날짜 검색은 INVALID_DATE: 형식·존재 오류, DATE_OUT_OF_RANGE: 연도 범위, INVALID_DATE_RANGE: 시작일·종료일 역전, CONFLICTING_FILTER: year와 날짜 범위 중복입니다. 다인 참여의 새 필터 multipleParticipants와 이전 호환 파라미터 collaboration이 충돌해도 CONFLICTING_FILTER를 양쪽 필드에 반환합니다. 파싱이 불가능한 JSON이나 특정 입력칸에 연결할 수 없는 업무·인증 오류는 빈 배열입니다. 입력값·비밀번호·토큰·내부 예외 메시지는 포함하지 않습니다."))
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
    else if (path.equals("/api/v1/me/youtube/callback")
        || path.equals("/api/v1/me/youtube/callback/local")) status = "303";
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

  private static void youtubeReturn(String path, Operation operation) {
    if (!(path.equals("/api/v1/me/youtube/callback")
        || path.equals("/api/v1/me/youtube/callback/local"))) return;
    operation.setSecurity(List.of());
    operation.getResponses().remove("401");
    operation.setDescription(
        "Google의 YouTube 추가 동의 콜백입니다. 프론트가 직접 호출하지 않습니다. 모든 인증 결과를 303 Location으로 안내합니다. 유효한 state는 사용자·시작 세션·callbackClient에 묶여 5분 내 한 번만 소비되고, 저장한 returnTo를 사용합니다. 세션 만료/누락·state 오류이면 사용자 지정 경로 대신 /library/youtube로 복귀합니다. local 경로는 서버에 등록한 로컬 origin으로, 일반 경로는 YOUTUBE_FRONTEND_ORIGIN(미설정이면 같은 origin)으로 이동합니다. local 미설정 시 일반 프론트 기본 경로에 INVALID_YOUTUBE_CLIENT를 안내합니다. Host·Forwarded·콜백의 returnTo는 사용하지 않습니다. 복귀 후 GET /api/v1/me/youtube/connection을 다시 조회하며, 401이면 Google 로그인 후 연결을 다시 시작합니다. 코드·토큰·state·내부 오류 원문은 Location에 포함하지 않고 Referrer-Policy: no-referrer와 Cache-Control: no-store를 사용합니다.");
    var response = operation.getResponses().get("303");
    response.setDescription(
        "youtubeResult=success|cancelled|failed. success에는 youtubeError가 없습니다. cancelled에는 YOUTUBE_CONSENT_DENIED. failed의 youtubeError는 SESSION_EXPIRED, INVALID_YOUTUBE_STATE, INVALID_YOUTUBE_CLIENT, YOUTUBE_ACCOUNT_MISMATCH, YOUTUBE_SCOPE_REQUIRED, YOUTUBE_OFFLINE_ACCESS_REQUIRED, INVALID_YOUTUBE_IDENTITY, YOUTUBE_AUTHORIZATION_CHANGED, YOUTUBE_EXPORT_DISABLED, YOUTUBE_OAUTH_UNAVAILABLE, YOUTUBE_AUTHORIZATION_FAILED 중 하나입니다. 알 수 없는 외부 오류는 YOUTUBE_AUTHORIZATION_FAILED로 정규화합니다. 결과는 안내용이며 실제 연결 성공을 보장하는 상태 API를 대신하지 않습니다.");
    response.addHeaderObject(
        "Location",
        new Header()
            .schema(new StringSchema())
            .description(
                "검증된 내부 returnTo에 youtubeResult·youtubeError를 추가한 프론트 URL. 기본 예: /library/youtube?youtubeResult=success. 로컬 예: http://localhost:5173/library/youtube?youtubeResult=cancelled&youtubeError=YOUTUBE_CONSENT_DENIED. 기존 같은 이름의 결과 파라미터·OAuth 민감 파라미터는 제거합니다."));
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

  private static void frontendErrors(String path, String verb, Operation operation) {
    if (operation.getResponses().containsKey("401"))
      errorCodes(
          operation,
          "401",
          "로그인이 필요하거나 세션이 만료되었습니다. 다시 로그인합니다.",
          "AUTHENTICATION_REQUIRED",
          "SESSION_EXPIRED");
    if (operation.getResponses().containsKey("403"))
      errorCodes(operation, "403", "ADMIN 권한 또는 CSRF 토큰을 확인합니다.", "ACCESS_DENIED");
    boolean songPage = path.equals("/api/v1/songs") || path.equals("/api/v1/members/{id}/songs");
    if (songPage) {
      errorCodes(
          operation,
          "400",
          "검색 조건·커서를 확인합니다. 여러 명 참여 필터는 multipleParticipants입니다. 이전 collaboration도 호환용으로 같은 조건으로 해석하며 두 값이 다르면 400 INVALID_CATALOG_QUERY 및 양쪽 fieldErrors[].code=CONFLICTING_FILTER입니다.",
          "INVALID_CATALOG_QUERY");
      if (path.equals("/api/v1/songs"))
        errorCodes(
            operation,
            "400",
            "날짜 범위는 totalCount와 모든 커서 페이지에 동일하게 적용됩니다. 날짜 형식·존재 오류: fieldErrors[].code=INVALID_DATE, 허용 연도 밖: DATE_OUT_OF_RANGE, 시작일>종료일: INVALID_DATE_RANGE(startDate·endDate), year와 범위 동시 사용: CONFLICTING_FILTER(year·지정 날짜 필드). fieldErrors[].field로 입력칸을 식별하며 입력값은 반환하지 않습니다. 기간 변경 후 기존 커서도 INVALID_CATALOG_QUERY입니다.",
            "INVALID_CATALOG_QUERY");
      errorCodes(
          operation,
          "409",
          "VIEWS 정렬의 커서가 이전 조회수 집계를 가리킵니다. 커서를 버리고 첫 페이지부터 다시 조회합니다.",
          "VIEW_PUBLICATION_CHANGED");
    }
    if (path.startsWith("/api/v1/me/favorites")) {
      errorCodes(operation, "400", "곡 ID·페이지 크기·커서를 확인합니다.", "INVALID_FAVORITE_QUERY");
      if (verb.equals("PUT")) {
        errorCodes(operation, "404", "공개 곡을 찾을 수 없습니다.", "CATALOG_NOT_FOUND");
        errorCodes(
            operation,
            "409",
            "즐겨찾기 저장 한도입니다. 기존 곡 중복 저장은 성공하며 순서를 유지합니다.",
            "FAVORITE_LIMIT_REACHED");
      }
    }
    if (path.startsWith("/api/v1/me/playlists") && !path.endsWith("/youtube-exports")) {
      errorCodes(operation, "400", "이름·항목·버전·커서를 확인합니다.", "INVALID_PLAYLIST_REQUEST");
      if (path.contains("/{id}")) {
        errorCodes(operation, "404", "현재 계정의 목록 또는 항목이 없습니다.", "PLAYLIST_NOT_FOUND");
        if (!verb.equals("GET") || path.endsWith("/items"))
          errorCodes(operation, "409", "현재 목록을 다시 조회하고 편집 내용을 확인합니다.", "PLAYLIST_CHANGED");
      }
      if (verb.equals("POST") && path.equals("/api/v1/me/playlists"))
        errorCodes(operation, "409", "플레이리스트 생성 한도입니다.", "PLAYLIST_LIMIT_REACHED");
      if (verb.equals("POST") && path.endsWith("/items")) {
        errorCodes(operation, "404", "추가하려는 공개 곡이 없습니다.", "CATALOG_NOT_FOUND");
        errorCodes(
            operation,
            "409",
            "이미 담긴 곡은 중복 표시하고, 항목 한도이면 다른 항목을 정리합니다.",
            "PLAYLIST_SONG_ALREADY_EXISTS",
            "PLAYLIST_ITEM_LIMIT_REACHED");
      }
    }
    if (path.matches("/api/v1/admin/(members|artists|works|songs|channels)(/.*)?")) {
      errorCodes(
          operation,
          "400",
          "관리자 입력이나 참조를 확인합니다. fieldErrors가 있으면 해당 입력칸에 표시합니다.",
          "INVALID_CATALOG_REQUEST",
          "INVALID_CATALOG_REFERENCE");
      if (!verb.equals("GET"))
        errorCodes(
            operation,
            "409",
            "CATALOG_VERSION_CONFLICT: 최신 내용을 조회해 변경을 확인합니다. CATALOG_DATA_CONFLICT: 중복·연결 조건을 확인합니다.",
            "CATALOG_VERSION_CONFLICT",
            "CATALOG_DATA_CONFLICT");
    }
    boolean createExport = verb.equals("POST") && path.endsWith("/youtube-exports");
    boolean retryExport = path.equals("/api/v1/me/youtube-exports/{id}/retry");
    if (createExport || retryExport || path.equals("/api/v1/me/youtube/authorizations"))
      errorCodes(operation, "503", "내보내기 기능이 비활성화되어 있습니다.", "YOUTUBE_EXPORT_DISABLED");
    if (createExport) {
      errorCodes(
          operation,
          "400",
          "INVALID_EXPORT_REQUEST: 입력 확인. EXPORT_ITEM_LIMIT: 내보내기 상한 500개 초과.",
          "INVALID_EXPORT_REQUEST",
          "EXPORT_ITEM_LIMIT");
      errorCodes(operation, "404", "현재 계정의 원본 목록이 없습니다.", "PLAYLIST_NOT_FOUND");
      errorCodes(
          operation,
          "409",
          "PLAYLIST_CHANGED: 원본 목록 재조회. YOUTUBE_CONNECTION_REQUIRED: 추가 동의. EXPORT_REQUEST_REUSED: 다른 목록·version에 requestId 재사용. EXPORT_ALREADY_ACTIVE: 기존 작업 확인. NO_EXPORTABLE_SONGS: 공개된 내보내기 가능 곡 없음.",
          "PLAYLIST_CHANGED",
          "YOUTUBE_CONNECTION_REQUIRED",
          "EXPORT_REQUEST_REUSED",
          "EXPORT_ALREADY_ACTIVE",
          "NO_EXPORTABLE_SONGS");
    }
    if (path.equals("/api/v1/me/youtube-exports/active")) {
      errorCodes(
          operation,
          "503",
          "EXPORT_LOOKUP_UNAVAILABLE: 저장소의 일시적 장애로 조회하지 못했습니다. 작업 없음으로 처리하거나 새 requestId로 반복 접수하지 않고 조회를 다시 시도합니다.",
          "EXPORT_LOOKUP_UNAVAILABLE");
    }
    if (path.startsWith("/api/v1/me/youtube-exports/")
        && !path.equals("/api/v1/me/youtube-exports/active")) {
      errorCodes(
          operation,
          "404",
          "현재 계정의 작업이 없습니다. 연결 해제·권한 무효화·보관 기한 경과로도 삭제될 수 있습니다.",
          "EXPORT_NOT_FOUND");
      if (retryExport)
        errorCodes(
            operation,
            "409",
            "EXPORT_NOT_RETRYABLE: 취소된 작업. YOUTUBE_CONNECTION_REQUIRED: 추가 동의 후 재시도합니다.",
            "EXPORT_NOT_RETRYABLE",
            "YOUTUBE_CONNECTION_REQUIRED");
    }
    if (path.equals("/api/v1/me/youtube/authorizations")) {
      errorCodes(
          operation,
          "400",
          "선택 JSON 본문은 {returnTo, callbackClient}입니다. 본문/필드 생략 및 null을 허용합니다. returnTo 기본값 /library/youtube, callbackClient 기본값 default. local은 YOUTUBE_LOCAL_REDIRECT_URI 설정 및 Google 콘솔의 해당 승인 URI 등록이 필요합니다. 기존 /api 프록시에서 시작·콜백·연결 상태·CSRF 요청을 같은 세션으로 처리하며 Secure·HttpOnly·SameSite를 유지합니다. 응답 authorizationUrl로 브라우저를 이동시킵니다.",
          "INVALID_YOUTUBE_RETURN",
          "INVALID_YOUTUBE_CLIENT",
          "INVALID_EXPORT_REQUEST");
      errorCodes(operation, "409", "권한 철회 완료 후 추가 동의를 다시 시작합니다.", "YOUTUBE_REVOCATION_PENDING");
      errorCodes(operation, "503", "YouTube OAuth 설정이 없습니다.", "YOUTUBE_OAUTH_UNAVAILABLE");
    }
  }

  private static void reauthenticationContract(String path, String verb, Operation operation) {
    if (path.equals("/api/v1/me/reauthentications")) {
      operation.setDescription(
          operation.getDescription()
              + "\n선택 JSON {returnTo, callbackClient}를 받습니다. 본문·필드·null 생략 허용. callbackClient 기본 default, 허용 default/local. returnTo는 최대 2048자 내부 UI 경로이며 외부 URL·//·공백/제어문자·역슬래시·중첩 인코딩·경로 우회·/api·/actuator 경로는 거절합니다. 응답 authorizationUrl·expiresAt과 CSRF 검증은 유지합니다. returnTo 생략 + default는 기존 Google 콜백과 확인 JSON 복귀를 유지합니다. returnTo를 지정하거나 local을 고르면 전용 콜백 및 303 결과 복귀를 사용합니다. returnTo 생략 시 기본 /api/v1/me/withdrawal-confirmation입니다. default 전용 콜백은 GOOGLE_REAUTHENTICATION_REDIRECT_URI, local은 GOOGLE_REAUTHENTICATION_LOCAL_REDIRECT_URI 및 Google 콘솔 승인 URI가 필요합니다. 로컬 URI http://localhost:5173/api/v1/auth/callback/google-reauthentication-local. 기본 프론트 origin은 GOOGLE_REAUTHENTICATION_FRONTEND_ORIGIN으로 설정합니다. /api 프록시는 모든 Set-Cookie와 Secure·HttpOnly·SameSite를 그대로 전달해야 합니다. 요청·복귀 정보는 같은 세션의 OAuth state에 5분간 묶어 한 번만 사용합니다.");
      errorCodes(
          operation,
          "400",
          "입력값을 응답에 포함하지 않습니다.",
          "INVALID_REAUTHENTICATION_RETURN",
          "INVALID_REAUTHENTICATION_CLIENT",
          "INVALID_REAUTHENTICATION_REQUEST");
      errorCodes(
          operation,
          "503",
          "Google 설정 또는 계정 저장소를 확인하지 못했습니다. 성공으로 처리하지 않습니다.",
          "GOOGLE_REAUTHENTICATION_UNAVAILABLE",
          "ACCOUNT_STORAGE_UNAVAILABLE",
          "LOGIN_STORAGE_UNAVAILABLE");
    }
    if (path.equals("/api/v1/me/withdrawal-confirmation")) {
      operation.setDescription(
          operation.getDescription()
              + "\nreauthenticated=false와 expiresAt=null은 현재 세션에 유효한 동일 계정 재인증이 없다는 뜻입니다. 성공 후 유효기간은 5분이며 UI 복귀 결과만으로 확정하지 않습니다. 조회 실패를 false로 간주하지 마세요.");
      errorCodes(
          operation,
          "503",
          "저장소 오류이며 유효하지 않은 재인증과 구분합니다.",
          "ACCOUNT_STORAGE_UNAVAILABLE",
          "LOGIN_STORAGE_UNAVAILABLE");
    }
    if (path.equals("/api/v1/me") && verb.equals("DELETE")) {
      operation.setDescription(
          operation.getDescription()
              + "\n동일 Google 계정 재인증 5분 내 새 CSRF와 사용자의 최종 버튼으로만 요청합니다. 재인증 복귀 시 자동 탈퇴하지 않습니다. 409/503은 탈퇴 완료가 아니며 재인증 상태·연결 철회·진행 작업을 확인합니다.");
      errorCodes(
          operation,
          "409",
          "REAUTHENTICATION_REQUIRED는 재인증 없음·다른 세션·5분 만료입니다. YOUTUBE_EXPORT_BUSY는 외부 쓰기 작업 중이며 완료 후 사용자가 재시도합니다. YOUTUBE_REVOCATION_PENDING은 Google 철회 대기이며 연결 철회 완료 뒤 재인증 유효 여부를 다시 확인합니다.",
          "REAUTHENTICATION_REQUIRED",
          "YOUTUBE_EXPORT_BUSY",
          "YOUTUBE_REVOCATION_PENDING");
      errorCodes(
          operation,
          "503",
          "ACCOUNT_STORAGE_UNAVAILABLE는 계정 저장소 실패, WITHDRAWAL_JOURNAL_UNAVAILABLE는 독립 탈퇴 기록 저장 실패, LOGIN_STORAGE_UNAVAILABLE는 로그인 상태 확인 실패입니다. 성공으로 표시하거나 자동 탈퇴 재전송하지 않습니다.",
          "ACCOUNT_STORAGE_UNAVAILABLE",
          "WITHDRAWAL_JOURNAL_UNAVAILABLE");
    }
  }

  private static void errorCodes(
      Operation operation, String status, String description, String... codes) {
    var response = operation.getResponses().get(status);
    if (response == null) {
      response = problem("");
      operation.getResponses().addApiResponse(status, response);
    }
    String previous = response.getDescription();
    response.setDescription(
        (previous == null || previous.isBlank() ? "" : previous + "\n")
            + String.join(", ", codes)
            + ": "
            + description);
    var combined = new java.util.ArrayList<String>();
    if (response.getExtensions() != null
        && response.getExtensions().get("x-error-codes") instanceof List<?> existing)
      existing.forEach(code -> combined.add(code.toString()));
    combined.addAll(List.of(codes));
    response.addExtension("x-error-codes", combined.stream().distinct().toList());
  }

  @SuppressWarnings("unchecked") // Swagger exposes raw schemas on Parameter.
  private static void queryConstraints(String path, Parameter parameter) {
    var schema = parameter.getSchema();
    if (schema == null) return;
    // springdoc may share schemas across parameters; constrain an independent deep copy.
    schema = io.swagger.v3.core.util.Json31.mapper().convertValue(schema, Schema.class);
    parameter.setSchema(schema);
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
      case "multipleParticipants" -> {
        schema.setDefault(false);
        parameter.setDescription(
            "기본 false는 참여 인원 제한 없음, true이면 확정 멤버·외부 참여자가 합계 2명 이상인 공개 곡만 조회합니다. 게임·IP·브랜드 협업 필터가 아닙니다. totalCount와 커서에도 동일하게 적용되며 값을 바꾸면 첫 페이지부터 조회합니다. 이전 collaboration과 함께 보내면 값이 같아야 합니다.");
      }
      case "collaboration" ->
          parameter.setDescription(
              "이전 클라이언트의 다인 참여 필터를 위한 호환 파라미터입니다. 게임·IP 협업 여부를 뜻하지 않습니다. 신규 연동은 multipleParticipants를 사용합니다. 새 파라미터 미지정 시 이 값을 사용하며, 둘 다 지정한 값이 다르면 400 INVALID_CATALOG_QUERY와 CONFLICTING_FILTER입니다.");
      case "days" -> schema.setEnum(List.of(7, 30));
      case "year" -> {
        schema.setMinimum(BigDecimal.valueOf(1900));
        schema.setMaximum(BigDecimal.valueOf(2100));
        parameter.setDescription(
            "대표 영상 공개일의 Asia/Seoul 기준 연도입니다. 전체 연도 선택지는 GET /api/v1/songs/years에서 조회합니다.");
      }
      case "startDate", "endDate" -> {
        if (path.equals("/api/v1/songs")) {
          schema.setFormat("date");
          schema.setPattern("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
          parameter.setDescription(
              "YYYY-MM-DD. 대표 영상 publishedAt의 Asia/Seoul 날짜 기준, 1900-01-01~2100-12-31. 시작일은 한국 시간 00:00 이상, 종료일은 다음날 00:00 미만으로 양 끝 날짜를 포함합니다. 한쪽만 지정할 수 있습니다. 둘 다 생략하면 기간 제한이 없습니다. year와 함께 지정하면 400 INVALID_CATALOG_QUERY입니다. 기간을 변경하면 이전 커서는 사용할 수 없습니다.");
        }
      }
      case "q" -> {
        schema.setMaxLength(200);
        if (path.equals("/api/v1/songs") || path.equals("/api/v1/members/{id}/songs"))
          parameter.setDescription(
              "최대 200자. NFKC 정규화 후 공백·영어 대소문자를 무시하여 곡 제목·곡 별칭·원곡 제목과 별칭·참여 멤버 및 아티스트 이름과 별칭을 검색합니다. 정식 제목은 바뀌지 않으며 같은 곡은 한 번만 반환합니다. totalCount·정렬·커서에 같은 검색 규칙을 적용합니다. 생략은 전체 탐색, 명시한 빈 검색어는 기존 SEARCH 계약에 따라 빈 결과입니다.");
      }
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
