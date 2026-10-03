package com.stelody.admin;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.stelody.admin.dto.CollectionRuleDtos.*;
import com.stelody.admin.service.CollectionRuleService;
import com.stelody.auth.domain.SessionUser;
import com.stelody.collector.domain.RuleConfiguration;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class ClassificationManagementIntegrationTest {
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

  static final UUID ADMIN = new UUID(0, 1),
      USER = new UUID(0, 2),
      MEMBER = new UUID(0, 11),
      OTHER = new UUID(0, 12),
      CHANNEL = new UUID(0, 13),
      SONG = new UUID(0, 20),
      VIDEO = new UUID(0, 21);
  static final String RULES = "/api/v1/admin/collection-rules";
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired FindByIndexNameSessionRepository<?> sessions;
  @Autowired JdbcTemplate runtime;
  @Autowired CollectionRuleService rules;
  JdbcTemplate writer, collector;
  Browser admin, user;

  record Browser(Cookie cookie, String header, String token) {}

  @BeforeEach
  void setup() throws Exception {
    var owner =
        new DriverManagerDataSource(postgres.getJdbcUrl(), "stelody_migrator", "test-migrator");
    writer = new JdbcTemplate(owner);
    writer.execute(
        "TRUNCATE app.app_user,app.member,app.channel,app.song_entry,app.catalog_audit,session.spring_session CASCADE");
    writer.update(
        "UPDATE app.collection_rule SET version=0,configuration=CAST(? AS jsonb)",
        mapper.writeValueAsString(RuleConfiguration.defaults()));
    writer.update(
        "INSERT INTO app.app_user(id,google_subject,email,role) VALUES(?,'admin','admin@example.invalid','ADMIN'),(?,'user','user@example.invalid','USER')",
        ADMIN,
        USER);
    Instant published = Instant.now().minusSeconds(3600);
    LocalDate date = published.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
    writer.update(
        "INSERT INTO app.member(id,name,search_name,activity_status,birthday_month,birthday_day,debut_date) VALUES(?,'member','member','ACTIVE',?,?,?)",
        MEMBER,
        date.getMonthValue(),
        date.getDayOfMonth(),
        date.minusYears(2));
    writer.update(
        "INSERT INTO app.member(id,name,search_name,activity_status,birthday_month,birthday_day) VALUES(?,'other','other','ACTIVE',?,?)",
        OTHER,
        date.getMonthValue(),
        date.getDayOfMonth());
    writer.update(
        "INSERT INTO app.channel(id,youtube_id,name,channel_type,member_id,collection_enabled) VALUES(?,'UCaaaaaaaaaaaaaaaaaaaaaa','member channel','MEMBER',?,true)",
        CHANNEL,
        OTHER);
    writer.update(
        "INSERT INTO app.song_entry(id,title,search_title,song_type,visibility) VALUES(?,'A song','a song','COVER','DRAFT')",
        SONG);
    writer.update(
        "INSERT INTO app.video(id,song_id,youtube_id,channel_id,video_kind,availability,source_title,source_published_at,source_observed_at) VALUES(?,?,'abcdefghijk',?,'OFFICIAL_COVER','PUBLIC','source cover',?,now())",
        VIDEO,
        SONG,
        CHANNEL,
        java.sql.Timestamp.from(published));
    writer.update("UPDATE app.song_entry SET representative_video_id=? WHERE id=?", VIDEO, SONG);
    writer.update(
        "INSERT INTO app.song_member(song_id,member_id,position,confirmed) VALUES(?,?,0,true)",
        SONG,
        MEMBER);
    new ResourceDatabasePopulator(
            new ClassPathResource("collector-grants.sql"),
            new ClassPathResource("discovery-grants.sql"),
            new ClassPathResource("collection-rule-grants.sql"))
        .execute(owner);
    collector =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_collector", "test-collector"));
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
  void routesRequireAdministratorAndCsrf() throws Exception {
    for (String path : List.of(RULES)) {
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
      mvc.perform(auth(get(path), user)).andExpect(status().isForbidden());
      mvc.perform(auth(get(path), admin))
          .andExpect(status().isOk())
          .andExpect(header().string("Cache-Control", "no-store"));
    }
    mvc.perform(
            put(RULES)
                .cookie(admin.cookie())
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(
                        new Change(0L, RuleConfiguration.defaults(), "edit"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void previewDoesNotSaveAndPrioritizesExclusion() throws Exception {
    var preview =
        new Preview(
            0L,
            RuleConfiguration.defaults(),
            List.of(
                new Sample("Cover #Shorts", "PUBLIC"),
                new Sample("Cover", "PRIVATE"),
                new Sample("Discovery", "PUBLIC"),
                new Sample("Covered　by Singer", "PUBLIC")));
    var result =
        mapper.readTree(
            mvc.perform(json(post(RULES + "/preview"), preview))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(result.path("decisions").get(0).path("disposition").asText()).isEqualTo("EXCLUDED");
    assertThat(result.path("decisions").get(1).path("disposition").asText()).isEqualTo("DEFERRED");
    assertThat(result.path("decisions").get(2).path("suggestedType").asText()).isEqualTo("UNKNOWN");
    assertThat(result.path("decisions").get(3).path("suggestedType").asText()).isEqualTo("COVER");
    assertThat(rules.current().version()).isZero();
    assertThat(writer.queryForObject("SELECT count(*) FROM app.catalog_audit", Integer.class))
        .isZero();
  }

  @Test
  void ruleChangeIsVersionedAndAuditedAndStaleRequestsConflict() throws Exception {
    var body =
        new Change(0L, new RuleConfiguration(List.of(), List.of(), List.of()), "disable markers");
    mvc.perform(json(put(RULES), body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
    mvc.perform(json(put(RULES), body)).andExpect(status().isConflict());
    mvc.perform(
            json(
                post(RULES + "/preview"),
                new Preview(
                    0L, RuleConfiguration.defaults(), List.of(new Sample("Cover", "PUBLIC")))))
        .andExpect(status().isConflict());
    var audit = rules.audits(0, 20);
    assertThat(audit.items()).hasSize(1);
    assertThat(audit.items().getFirst().actorId()).isEqualTo(ADMIN);
    assertThat(collector.queryForObject("SELECT version FROM app.collection_rule", Long.class))
        .isEqualTo(1);
    assertThatThrownBy(() -> collector.update("UPDATE app.collection_rule SET version=2"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> runtime.execute("DELETE FROM app.collection_rule"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void invalidConfigurationAndSamplesDoNotMutateDatabase() throws Exception {
    var marker = new RuleConfiguration.Marker("cover", RuleConfiguration.Match.WORD);
    for (var config :
        List.of(
            new RuleConfiguration(Collections.nCopies(31, marker), List.of(), List.of()),
            new RuleConfiguration(
                List.of(
                    marker, new RuleConfiguration.Marker("ＣＯＶＥＲ", RuleConfiguration.Match.PHRASE)),
                List.of(),
                List.of()),
            new RuleConfiguration(
                List.of(new RuleConfiguration.Marker("　", RuleConfiguration.Match.WORD)),
                List.of(),
                List.of())))
      mvc.perform(json(put(RULES), new Change(0L, config, "test")))
          .andExpect(status().isBadRequest());
    mvc.perform(
            json(
                post(RULES + "/preview"),
                new Preview(
                    0L,
                    RuleConfiguration.defaults(),
                    Collections.nCopies(21, new Sample("Cover", "PUBLIC")))))
        .andExpect(status().isBadRequest());
    assertThat(rules.current().version()).isZero();
  }

  @Test
  void failedAuditRollsBackRuleUpdate() {
    assertThatThrownBy(
            () ->
                rules.change(
                    new Change(
                        0L, new RuleConfiguration(List.of(), List.of(), List.of()), "fixture"),
                    UUID.randomUUID()))
        .isInstanceOf(DataAccessException.class);
    assertThat(rules.current().version()).isZero();
  }

  @Test
  void simultaneousRuleEditsCannotBothCommit() throws Exception {
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var task =
          (Callable<Boolean>)
              () -> {
                start.await();
                try {
                  rules.change(
                      new Change(
                          0L, new RuleConfiguration(List.of(), List.of(), List.of()), "race"),
                      ADMIN);
                  return true;
                } catch (com.stelody.admin.web.AdminCatalogException e) {
                  assertThat(e.status()).isEqualTo(409);
                  return false;
                }
              };
      var first = executor.submit(task);
      var second = executor.submit(task);
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(rules.current().version()).isEqualTo(1);
    assertThat(rules.audits(0, 20).items()).hasSize(1);
  }
}
