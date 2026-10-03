package com.stelody.admin;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.stelody.admin.dto.CoverPublicationDtos.*;
import com.stelody.admin.service.CoverPublicationService;
import com.stelody.auth.domain.SessionUser;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@SpringBootTest(properties = {"stelody.cover-publication.policy-allowed=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class CoverPublicationManagementIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17.6-alpine").withInitScript("db/create-test-roles.sql");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", () -> "stelody_app");
    r.add("spring.datasource.password", () -> "test-runtime");
    r.add("spring.flyway.user", () -> "stelody_migrator");
    r.add("spring.flyway.password", () -> "test-migrator");
  }

  static final UUID ADMIN = new UUID(0, 1), USER = new UUID(0, 2);
  static final String BASE = "/api/v1/admin/cover-auto-publication";
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired FindByIndexNameSessionRepository<?> sessions;
  @Autowired JdbcTemplate runtime;
  @Autowired CoverPublicationService service;
  JdbcTemplate writer;
  Browser admin, user;

  record Browser(Cookie cookie, String header, String token) {}

  @BeforeEach
  void setup() throws Exception {
    writer =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_migrator", "test-migrator"));
    writer.execute(
        "TRUNCATE app.app_user,app.member,app.channel,app.song_entry,app.catalog_audit,session.spring_session CASCADE");
    writer.execute("UPDATE app.cover_publication_control SET enabled=false,version=0");
    writer.update(
        "INSERT INTO app.app_user(id,google_subject,email,role) VALUES(?,'admin','admin@example.invalid','ADMIN'),(?,'user','user@example.invalid','USER')",
        ADMIN,
        USER);
    admin = browser(ADMIN);
    user = browser(USER);
  }

  <S extends Session> Browser browser(UUID id) throws Exception {
    @SuppressWarnings("unchecked")
    var storage = (FindByIndexNameSessionRepository<S>) sessions;
    var session = storage.createSession();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new SessionUser(id, Instant.now()),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    storage.save(session);
    var cookie =
        new Cookie(
            "SESSION",
            Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    var token =
        mapper.readTree(
            mvc.perform(get("/api/v1/auth/csrf").cookie(cookie))
                .andReturn()
                .getResponse()
                .getContentAsString());
    return new Browser(cookie, token.path("headerName").asText(), token.path("token").asText());
  }

  MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, Browser who) {
    return b.cookie(who.cookie()).header(who.header(), who.token());
  }

  MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, Object body) {
    return auth(b, admin).contentType("application/json").content(mapper.writeValueAsString(body));
  }

  @Test
  void routesRequireAdminAndMutationsRequireCsrf() throws Exception {
    for (String path : List.of(BASE, BASE + "/registrations", BASE + "/audit")) {
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
      mvc.perform(auth(get(path), user)).andExpect(status().isForbidden());
      mvc.perform(auth(get(path), admin))
          .andExpect(status().isOk())
          .andExpect(header().string("Cache-Control", "no-store"));
    }
    String body = mapper.writeValueAsString(new Change(0L, true, "activate"));
    mvc.perform(put(BASE).cookie(admin.cookie()).contentType("application/json").content(body))
        .andExpect(status().isForbidden());
    mvc.perform(auth(put(BASE), user).contentType("application/json").content(body))
        .andExpect(status().isForbidden());
    writer.update("UPDATE app.app_user SET role='USER' WHERE id=?", ADMIN);
    mvc.perform(json(put(BASE), new Change(0L, true, "activate")))
        .andExpect(status().isForbidden());
  }

  @Test
  void stopControlUsesVersionAndKeepsAudit() throws Exception {
    mvc.perform(json(put(BASE), new Change(0L, true, "start clear solo covers")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.policyAllowed").value(true));
    mvc.perform(json(put(BASE), new Change(0L, false, "old change")))
        .andExpect(status().isConflict());
    mvc.perform(json(put(BASE), new Change(1L, false, "pause publication")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false));
    mvc.perform(auth(get(BASE + "/audit"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2));
    assertThat(
            writer.queryForObject(
                "SELECT count(*) FROM app.catalog_audit WHERE target_type='COVER_PUBLICATION' AND actor_id=?",
                Integer.class,
                ADMIN))
        .isEqualTo(2);
  }

  @Test
  void nullVersionEnabledOrBlankReasonAndInvalidPageAreRejected() throws Exception {
    for (String body :
        List.of(
            "{}",
            "{\"version\":0,\"reason\":\"x\"}",
            "{\"version\":-1,\"enabled\":true,\"reason\":\"x\"}",
            "{\"version\":0,\"enabled\":true,\"reason\":\" \"}"))
      mvc.perform(auth(put(BASE), admin).contentType("application/json").content(body))
          .andExpect(status().isBadRequest());
    mvc.perform(auth(get(BASE + "/registrations?size=51"), admin))
        .andExpect(status().isBadRequest());
    mvc.perform(auth(get(BASE + "/audit?page=-1"), admin)).andExpect(status().isBadRequest());
  }

  @Test
  void policyDisabledServiceAllowsStopButRejectsStart() {
    var disabled =
        new CoverPublicationService(
            org.springframework.jdbc.core.simple.JdbcClient.create(runtime.getDataSource()),
            new com.stelody.admin.repository.CatalogAdminQueries(
                org.springframework.jdbc.core.simple.JdbcClient.create(runtime.getDataSource()),
                mapper),
            false);
    assertThatThrownBy(() -> disabled.change(new Change(0L, true, "start"), ADMIN))
        .isInstanceOf(com.stelody.admin.web.AdminCatalogException.class);
    assertThat(disabled.change(new Change(0L, false, "stop"), ADMIN).enabled()).isFalse();
  }

  @Test
  void auditFailureRollsBackControlChange() {
    writer.execute("REVOKE INSERT ON app.catalog_audit FROM stelody_app");
    try {
      assertThatThrownBy(() -> service.change(new Change(0L, true, "activate"), ADMIN))
          .isInstanceOf(DataAccessException.class);
      assertThat(service.current().enabled()).isFalse();
      assertThat(service.current().version()).isZero();
    } finally {
      writer.execute("GRANT INSERT ON app.catalog_audit TO stelody_app");
    }
  }

  void registrationFixture() {
    writer.execute(
        "INSERT INTO app.member(id,name,search_name,activity_status) VALUES('00000000-0000-0000-0000-000000000010','member','member','ACTIVE')");
    writer.execute(
        "INSERT INTO app.channel(id,youtube_id,name,channel_type,collection_enabled) VALUES('00000000-0000-0000-0000-000000000011','UCaaaaaaaaaaaaaaaaaaaaaa','official','GROUP',true)");
    writer.execute(
        "INSERT INTO app.song_entry(id,title,search_title,song_type,visibility) VALUES('00000000-0000-0000-0000-000000000020','auto title','auto title','COVER','PUBLISHED')");
    writer.execute(
        "INSERT INTO app.video(id,song_id,youtube_id,channel_id,video_kind,availability,source_title) VALUES('00000000-0000-0000-0000-000000000021','00000000-0000-0000-0000-000000000020','abcdefghijk','00000000-0000-0000-0000-000000000011','OFFICIAL_COVER','PUBLIC','source title')");
    writer.execute(
        "UPDATE app.song_entry SET representative_video_id='00000000-0000-0000-0000-000000000021'");
    writer.execute(
        "INSERT INTO app.review_item(id,youtube_id,channel_id,source_observed_at,availability,disposition,suggested_type,rule_version,decision_reason,first_seen_at,review_status,registered_video_id) VALUES('00000000-0000-0000-0000-000000000022','abcdefghijk','00000000-0000-0000-0000-000000000011',now(),'PUBLIC','REVIEW','COVER','title-v2:0','EXPLICIT_SOLO_COVER_CREDIT',now(),'REGISTERED','00000000-0000-0000-0000-000000000021')");
    writer.execute(
        "INSERT INTO app.cover_auto_registration VALUES('00000000-0000-0000-0000-000000000022','00000000-0000-0000-0000-000000000020','00000000-0000-0000-0000-000000000021','00000000-0000-0000-0000-000000000010','title-v2:0/solo-credit-v1','EXPLICIT_SOLO_COVER_CREDIT',now())");
  }

  @Test
  void missingInformationQueueUsesCurrentManualMetadataAndOptionalAliasesDoNotPreventCompletion()
      throws Exception {
    registrationFixture();
    mvc.perform(auth(get(BASE + "/registrations"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].title").value("auto title"))
        .andExpect(jsonPath("$.items[0].missingFields.length()").value(6));
    writer.execute(
        "INSERT INTO app.musical_work(id,title,search_title) VALUES('00000000-0000-0000-0000-000000000030','work','work')");
    writer.execute(
        "INSERT INTO app.artist(id,name,search_name) VALUES('00000000-0000-0000-0000-000000000031','artist','artist')");
    writer.execute(
        "INSERT INTO app.work_artist VALUES('00000000-0000-0000-0000-000000000030','00000000-0000-0000-0000-000000000031',0)");
    writer.execute(
        "UPDATE app.song_entry SET work_id='00000000-0000-0000-0000-000000000030',search_visibility='NORMAL',title='manual title',visibility='HIDDEN'");
    writer.execute(
        "INSERT INTO app.karaoke_entry(id,work_id,provider,status) VALUES(gen_random_uuid(),'00000000-0000-0000-0000-000000000030','TJ','NOT_LISTED'),(gen_random_uuid(),'00000000-0000-0000-0000-000000000030','KY','NOT_LISTED')");
    mvc.perform(auth(get(BASE + "/registrations"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
    mvc.perform(auth(get(BASE + "/registrations?incompleteOnly=false"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].title").value("manual title"))
        .andExpect(jsonPath("$.items[0].visibility").value("HIDDEN"))
        .andExpect(jsonPath("$.items[0].missingFields[0]").value("aliases"));
  }
}
