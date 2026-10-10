package com.stelody.admin;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.stelody.admin.command.AdminAccountCommand;
import com.stelody.admin.dto.CatalogAdminDtos.*;
import com.stelody.admin.service.CatalogManagementService;
import com.stelody.auth.domain.SessionUser;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class CatalogManagementIntegrationTest {
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
      CHANNEL = new UUID(0, 3),
      REVIEW = new UUID(0, 4);
  static final String ROOT = "/api/v1/admin";
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired FindByIndexNameSessionRepository<?> sessions;
  @Autowired JdbcTemplate runtime;
  @Autowired CatalogManagementService service;
  @Autowired PlatformTransactionManager transactions;
  @Autowired jakarta.persistence.EntityManager entities;
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
        "TRUNCATE app.app_user,app.member,app.artist,app.musical_work,app.channel,app.song_entry,app.catalog_audit,session.spring_session CASCADE");
    writer.update(
        "INSERT INTO app.app_user(id,google_subject,email,role) VALUES(?,'admin','admin@example.invalid','ADMIN'),(?,'user','user@example.invalid','USER')",
        ADMIN,
        USER);
    writer.update(
        "INSERT INTO app.channel(id,youtube_id,name,channel_type,collection_enabled) VALUES(?,'UCaaaaaaaaaaaaaaaaaaaaaa','Test','GROUP',true)",
        CHANNEL);
    seed(REVIEW, "abcdefghijk");
    admin = browser(ADMIN);
    user = browser(USER);
  }

  void seed(UUID id, String youtube) {
    writer.update(
        """
    INSERT INTO app.review_item(id,youtube_id,channel_id,source_title,source_published_at,source_thumbnail_url,source_duration_seconds,source_observed_at,availability,disposition,suggested_type,rule_version,decision_reason,first_seen_at)
    VALUES(?,?,?,'source cover',now()-interval '1 day','https://example.invalid/source',120,now(),'PUBLIC','REVIEW','COVER','title-v1','REVIEW',now())
    """,
        id,
        youtube,
        CHANNEL);
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

  JsonNode create(String path, Object body) throws Exception {
    return mapper.readTree(
        mvc.perform(json(post(ROOT + path), body))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  UUID member() throws Exception {
    return UUID.fromString(create("/members", memberBody()).path("item").path("id").asText());
  }

  Map<String, Object> memberBody() {
    var b = new HashMap<String, Object>();
    b.put("version", 0);
    b.put("name", "Member");
    b.put("generation", 1);
    b.put("activityStatus", "ACTIVE");
    b.put("aliases", List.of("メンバー"));
    b.put("birthdayMonth", 2);
    b.put("birthdayDay", 29);
    b.put("reason", "verified fixture");
    return b;
  }

  UUID song(UUID member, String type) throws Exception {
    return UUID.fromString(
        create("/songs", songBody(member, type)).path("item").path("id").asText());
  }

  Map<String, Object> songBody(UUID member, String type) {
    var b = new HashMap<String, Object>();
    b.put("version", 0);
    b.put("title", "Manual title");
    b.put("type", type);
    b.put("visibility", "DRAFT");
    b.put("aliases", List.of("歌"));
    b.put("memberIds", member == null ? List.of() : List.of(member));
    b.put("externalArtistIds", List.of());
    b.put("isSpecialEvent", false);
    b.put("searchVisibility", "UNCHECKED");
    b.put("links", List.of());
    b.put("karaoke", List.of());
    b.put("reason", "verified fixture");
    return b;
  }

  UUID attach(UUID song, UUID review, long reviewVersion, long songVersion, String kind)
      throws Exception {
    return UUID.fromString(
        create(
                "/reviews/" + review + "/registration",
                Map.of(
                    "version",
                    reviewVersion,
                    "songVersion",
                    songVersion,
                    "songId",
                    song,
                    "kind",
                    kind,
                    "reason",
                    "same recording confirmed"))
            .path("id")
            .asText());
  }

  JsonNode songList(String query, int page, int size) throws Exception {
    return mapper.readTree(
        mvc.perform(
                auth(
                    get(ROOT + "/songs")
                        .param("q", query)
                        .param("page", Integer.toString(page))
                        .param("size", Integer.toString(size)),
                    admin))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  JsonNode adminGet(String path) throws Exception {
    return mapper.readTree(
        mvc.perform(auth(get(path), admin))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  void clearCollectionRuns() {
    writer.execute(
        "TRUNCATE app.collection_run,app.discovery_run,app.collection_retry_request CASCADE");
  }

  @Test
  void inboxReviewAndSpecialCountsMatchLinkedListsAndExcludeHandledAndDeferredCandidates()
      throws Exception {
    clearCollectionRuns();
    UUID expired = UUID.randomUUID(),
        excluded = UUID.randomUUID(),
        deferred = UUID.randomUUID(),
        ignored = UUID.randomUUID();
    seed(expired, "lmnopqrstuv");
    seed(excluded, "wxyz1234567");
    seed(deferred, "890abcdefgh");
    seed(ignored, "ijklmnopqrs");
    writer.update(
        "UPDATE app.review_item SET source_observed_at=now()-interval '31 days' WHERE id=?",
        expired);
    writer.update("UPDATE app.review_item SET disposition='EXCLUDED' WHERE id=?", excluded);
    writer.update("UPDATE app.review_item SET disposition='DEFERRED' WHERE id=?", deferred);
    writer.update("UPDATE app.review_item SET review_status='IGNORED' WHERE id=?", ignored);
    UUID pendingSong = song(null, "COVER"),
        confirmedSong = song(null, "COVER"),
        dismissedSong = song(null, "ORIGINAL");
    for (var entry :
        Map.of(pendingSong, "PENDING", confirmedSong, "CONFIRMED", dismissedSong, "DISMISSED")
            .entrySet()) {
      writer.update(
          "INSERT INTO app.special_event_review(id,song_id,status,evidence,active,source_expires_at) VALUES(?,?,?,'[]',false,now()-interval '1 day')",
          UUID.randomUUID(),
          entry.getKey(),
          entry.getValue());
    }
    var inbox = adminGet(ROOT + "/inbox");
    assertThat(inbox.path("newReviewCount").asLong()).isEqualTo(1);
    assertThat(inbox.path("newReviewCount").asLong())
        .isEqualTo(
            adminGet(ROOT + "/reviews?status=PENDING&disposition=REVIEW").path("items").size());
    assertThat(inbox.path("specialEventReviewCount").asLong()).isEqualTo(1);
    assertThat(inbox.path("specialEventReviewCount").asLong())
        .isEqualTo(adminGet(ROOT + "/special-event-reviews?status=PENDING").path("items").size());
    assertThat(inbox.path("incompleteAutoRegistrationCount").asLong()).isZero();
    assertThat(inbox.path("collectionFailureCount").asLong()).isZero();
    assertThat(Instant.parse(inbox.path("checkedAt").asText()).minusSeconds(86400))
        .isEqualTo(Instant.parse(inbox.path("failureWindowStart").asText()));
    writer.update("UPDATE app.review_item SET review_status='IGNORED' WHERE id=?", REVIEW);
    writer.update(
        "UPDATE app.special_event_review SET status='DISMISSED' WHERE song_id=?", pendingSong);
    inbox = adminGet(ROOT + "/inbox");
    assertThat(inbox.path("newReviewCount").asLong()).isZero();
    assertThat(inbox.path("specialEventReviewCount").asLong()).isZero();
    mvc.perform(get(ROOT + "/inbox")).andExpect(status().isUnauthorized());
    mvc.perform(auth(get(ROOT + "/inbox"), user)).andExpect(status().isForbidden());
  }

  @Test
  void inboxInformationCountTargetsOnlyIncompleteAutomaticRegistrationsAndMatchesQueue()
      throws Exception {
    clearCollectionRuns();
    UUID member = member(), automatic = song(member, "COVER");
    song(null, "COVER"); // Incomplete manual song is outside this queue.
    UUID video = attach(automatic, REVIEW, 0, 0, "OFFICIAL_COVER");
    writer.update(
        "INSERT INTO app.cover_auto_registration(review_id,song_id,video_id,member_id,rule_version,reason,processed_at) VALUES(?,?,?,?,'test','test',now())",
        REVIEW,
        automatic,
        video,
        member);
    writer.update("UPDATE app.song_entry SET visibility='HIDDEN' WHERE id=?", automatic);
    assertThat(adminGet(ROOT + "/inbox").path("incompleteAutoRegistrationCount").asLong())
        .isEqualTo(1);
    assertThat(
            adminGet(ROOT + "/cover-auto-publication/registrations?incompleteOnly=true")
                .path("items")
                .size())
        .isEqualTo(1);
    UUID work = UUID.randomUUID(), artist = UUID.randomUUID();
    writer.update(
        "INSERT INTO app.musical_work(id,title,search_title) VALUES(?,'work','work')", work);
    writer.update(
        "INSERT INTO app.artist(id,name,search_name) VALUES(?,'artist','artist')", artist);
    writer.update(
        "INSERT INTO app.work_artist(work_id,artist_id,position) VALUES(?,?,0)", work, artist);
    writer.update(
        "UPDATE app.song_entry SET work_id=?,search_visibility='NORMAL' WHERE id=?",
        work,
        automatic);
    for (String provider : List.of("TJ", "KY"))
      writer.update(
          "INSERT INTO app.karaoke_entry(id,work_id,provider,status) VALUES(?,?,?,'NOT_LISTED')",
          UUID.randomUUID(),
          work,
          provider);
    writer.update("DELETE FROM app.song_alias WHERE song_id=?", automatic);
    assertThat(adminGet(ROOT + "/inbox").path("incompleteAutoRegistrationCount").asLong()).isZero();
    assertThat(
            adminGet(ROOT + "/cover-auto-publication/registrations?incompleteOnly=true")
                .path("items")
                .isEmpty())
        .isTrue();
    assertThat(
            adminGet(ROOT + "/cover-auto-publication/registrations?incompleteOnly=false")
                .path("items")
                .get(0)
                .path("missingFields")
                .toString())
        .isEqualTo("[\"aliases\"]");
  }

  UUID failureRun(String kind, String status, Instant finished, int slot) {
    UUID id = UUID.randomUUID();
    var start = java.sql.Timestamp.from(finished.minusSeconds(60));
    if (kind.equals("VIDEO")) {
      writer.update(
          "INSERT INTO app.collection_run(id,logical_slot,status,attempt,started_at,finished_at) VALUES(?,date_trunc('hour',now())-?*interval '1 hour',?,1,?,?)",
          id,
          slot,
          status,
          start,
          java.sql.Timestamp.from(finished));
    } else {
      writer.update(
          "INSERT INTO app.discovery_run(id,mode,status,started_at,finished_at) VALUES(?,'NEW',?,?,?)",
          id,
          status,
          start,
          java.sql.Timestamp.from(finished));
    }
    return id;
  }

  @Test
  void inboxFailuresMatchCombinedWindowedListAndDropSuccessfulRetriesWithoutHidingOtherFailures()
      throws Exception {
    clearCollectionRuns();
    Instant now = Instant.now().minusSeconds(10);
    UUID video = failureRun("VIDEO", "FAILED", now.minusSeconds(100), 0);
    failureRun("DISCOVERY", "QUOTA_EXHAUSTED", now.minusSeconds(200), 0);
    failureRun("DISCOVERY", "TIMED_OUT", now.minusSeconds(300), 0);
    UUID retried = failureRun("DISCOVERY", "FAILED", now.minusSeconds(400), 0);
    UUID execution = failureRun("DISCOVERY", "SUCCEEDED", now.minusSeconds(50), 0);
    writer.update(
        "INSERT INTO app.collection_retry_request(id,kind,run_id,expected_attempt,status,execution_run_id) VALUES(?,'DISCOVERY',?,1,'SUCCEEDED',?)",
        UUID.randomUUID(),
        retried,
        execution);
    failureRun("VIDEO", "FAILED", now.minusSeconds(90000), 1);
    failureRun("VIDEO", "SUCCEEDED", now, 2);
    failureRun("DISCOVERY", "FAILED", now.plusSeconds(600), 0);
    failureRun("DISCOVERY", "RUNNING", now, 0);
    var inbox = adminGet(ROOT + "/inbox");
    assertThat(inbox.path("collectionFailureCount").asLong()).isEqualTo(3);
    String window =
        "?from="
            + inbox.path("failureWindowStart").asText()
            + "&to="
            + inbox.path("checkedAt").asText();
    var all = adminGet(ROOT + "/collection-failures" + window);
    assertThat(all.path("items").size()).isEqualTo(inbox.path("collectionFailureCount").asInt());
    assertThat(all.path("items").get(0).path("id").asText()).isEqualTo(video.toString());
    assertThat(
            adminGet(ROOT + "/collection-failures" + window + "&size=1")
                .path("hasNext")
                .asBoolean())
        .isTrue();
    assertThat(
            adminGet(ROOT + "/collection-failures" + window + "&size=1&page=2")
                .path("hasNext")
                .asBoolean())
        .isFalse();
    assertThat(adminGet(ROOT + "/collection-failures" + window + "&page=1").path("items").isEmpty())
        .isTrue();
    writer.update("UPDATE app.collection_run SET status='SUCCEEDED' WHERE id=?", video);
    assertThat(adminGet(ROOT + "/inbox").path("collectionFailureCount").asLong()).isEqualTo(2);
    mvc.perform(get(ROOT + "/collection-failures")).andExpect(status().isUnauthorized());
    mvc.perform(auth(get(ROOT + "/collection-failures"), user)).andExpect(status().isForbidden());
    for (String query :
        List.of(
            "?from=bad",
            "?size=51",
            "?page=-1",
            "?from=2026-02-02T00:00:00Z&to=2026-02-01T00:00:00Z"))
      mvc.perform(auth(get(ROOT + "/collection-failures" + query), admin))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_COLLECTION_REQUEST"));
  }

  @Test
  void songListReturnsOrderedParticipantsAndEarliestLinkedDiscoveryRatherThanPublicationDate()
      throws Exception {
    UUID member = member(), song = song(member, "COVER"), external = UUID.randomUUID();
    writer.update(
        "INSERT INTO app.artist(id,name,search_name) VALUES(?,'Guest','guest')", external);
    writer.update(
        "INSERT INTO app.song_external_artist(song_id,artist_id,position,confirmed) VALUES(?,?,0,false)",
        song,
        external);
    UUID firstVideo = attach(song, REVIEW, 0, 0, "OFFICIAL_COVER");
    UUID secondReview = UUID.randomUUID();
    seed(secondReview, "lmnopqrstuv");
    attach(song, secondReview, 0, 1, "OTHER");
    Instant firstSeen = Instant.parse("2026-01-02T00:00:00Z");
    writer.update(
        "UPDATE app.review_item SET first_seen_at=?,source_published_at=? WHERE id=?",
        java.sql.Timestamp.from(Instant.parse("2026-01-03T00:00:00Z")),
        java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
        REVIEW);
    writer.update(
        "UPDATE app.review_item SET first_seen_at=? WHERE id=?",
        java.sql.Timestamp.from(firstSeen),
        secondReview);
    writer.update(
        "UPDATE app.song_entry SET representative_video_id=?,visibility='PUBLISHED' WHERE id=?",
        firstVideo,
        song);
    var item = songList("", 0, 20).path("items").get(0);
    assertThat(item.path("id").asText()).isEqualTo(song.toString());
    assertThat(item.path("participants").size()).isEqualTo(2);
    assertThat(item.path("participants").get(0).path("id").asText()).isEqualTo(member.toString());
    assertThat(item.path("participants").get(0).path("kind").asText()).isEqualTo("MEMBER");
    assertThat(item.path("participants").get(0).path("confirmed").asBoolean()).isTrue();
    assertThat(item.path("participants").get(1).path("name").asText()).isEqualTo("Guest");
    assertThat(item.path("participants").get(1).path("kind").asText()).isEqualTo("EXTERNAL");
    assertThat(item.path("participants").get(1).path("confirmed").asBoolean()).isFalse();
    assertThat(Instant.parse(item.path("discoveredAt").asText())).isEqualTo(firstSeen);
    assertThat(item.path("status").asText()).isEqualTo("PUBLISHED");
    assertThat(item.path("informationComplete").asBoolean()).isFalse();
    writer.update(
        "UPDATE app.review_item SET source_published_at=now(),source_observed_at=now() WHERE id=?",
        secondReview);
    assertThat(songList("", 0, 20).path("items").get(0).path("discoveredAt").asText())
        .isEqualTo(item.path("discoveredAt").asText());
    writer.update(
        "UPDATE app.review_item SET review_status='PENDING',registered_video_id=NULL WHERE id=?",
        secondReview);
    writer.update("DELETE FROM app.video WHERE youtube_id='lmnopqrstuv'");
    assertThat(
            Instant.parse(songList("", 0, 20).path("items").get(0).path("discoveredAt").asText()))
        .isEqualTo(Instant.parse("2026-01-03T00:00:00Z"));
  }

  @Test
  void songListReportsCurrentMissingInformationIndependentlyOfVisibilityAndOptionalAliases()
      throws Exception {
    UUID song = song(null, "COVER");
    writer.update("DELETE FROM app.song_alias WHERE song_id=?", song);
    var item = songList("", 0, 20).path("items").get(0);
    assertThat(item.path("participants").isEmpty()).isTrue();
    assertThat(item.path("discoveredAt").isNull()).isTrue();
    assertThat(item.path("missingFields").toString())
        .isEqualTo(
            "[\"work\",\"originalArtists\",\"aliases\",\"searchCheck\",\"TJ\",\"KY\",\"members\",\"representativeVideo\"]");
    UUID member = member(), work = UUID.randomUUID(), artist = UUID.randomUUID();
    writer.update(
        "INSERT INTO app.musical_work(id,title,search_title) VALUES(?,'work','work')", work);
    writer.update(
        "INSERT INTO app.artist(id,name,search_name) VALUES(?,'artist','artist')", artist);
    writer.update(
        "INSERT INTO app.work_artist(work_id,artist_id,position) VALUES(?,?,0)", work, artist);
    writer.update(
        "INSERT INTO app.song_member(song_id,member_id,position) VALUES(?,?,0)", song, member);
    UUID video = attach(song, REVIEW, 0, 0, "OFFICIAL_COVER");
    writer.update(
        "UPDATE app.song_entry SET work_id=?,representative_video_id=?,search_visibility='NORMAL',visibility='HIDDEN' WHERE id=?",
        work,
        video,
        song);
    writer.update(
        "INSERT INTO app.karaoke_entry(id,work_id,provider,status) VALUES(?,?,'TJ','NOT_LISTED')",
        UUID.randomUUID(),
        work);
    writer.update(
        "INSERT INTO app.karaoke_entry(id,song_id,provider,status,number,source_url,checked_at) VALUES(?,?,'KY','REGISTERED','12345','https://example.invalid/karaoke',now())",
        UUID.randomUUID(),
        song);
    item = songList("", 0, 20).path("items").get(0);
    assertThat(item.path("status").asText()).isEqualTo("HIDDEN");
    assertThat(item.path("missingFields").toString()).isEqualTo("[\"aliases\"]");
    assertThat(item.path("informationComplete").asBoolean()).isTrue();
    writer.update(
        "UPDATE app.karaoke_entry SET status='UNKNOWN',number=NULL WHERE song_id=?", song);
    item = songList("", 0, 20).path("items").get(0);
    assertThat(item.path("missingFields").toString()).isEqualTo("[\"aliases\",\"KY\"]");
    assertThat(item.path("informationComplete").asBoolean()).isFalse();
  }

  @Test
  void songListKeepsTitleAliasSearchPaginationAndAdminAccess() throws Exception {
    UUID a = song(null, "ORIGINAL"), b = song(null, "COVER");
    writer.update(
        "UPDATE app.song_entry SET title='A',search_title='a',visibility='PUBLISHED' WHERE id=?",
        a);
    writer.update(
        "UPDATE app.song_entry SET title='B',search_title='b',visibility='HIDDEN' WHERE id=?", b);
    var page = songList("歌", 0, 1);
    assertThat(page.path("items").size()).isEqualTo(1);
    assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(a.toString());
    assertThat(page.path("hasNext").asBoolean()).isTrue();
    page = songList("歌", 1, 1);
    assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(b.toString());
    assertThat(page.path("hasNext").asBoolean()).isFalse();
    assertThat(songList("b", 0, 20).path("items").size()).isEqualTo(1);
    assertThat(songList("missing", 0, 20).path("items").isEmpty()).isTrue();
    mvc.perform(get(ROOT + "/songs")).andExpect(status().isUnauthorized());
    mvc.perform(auth(get(ROOT + "/songs"), user)).andExpect(status().isForbidden());
    for (var params :
        List.of(Map.of("page", "-1"), Map.of("size", "51"), Map.of("q", "x".repeat(201)))) {
      var request = auth(get(ROOT + "/songs"), admin);
      params.forEach(request::param);
      mvc.perform(request).andExpect(status().isBadRequest());
    }
    var members =
        mapper.readTree(
            mvc.perform(auth(get(ROOT + "/members"), admin))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(members.path("items").isEmpty()).isTrue();
  }

  void publish(UUID song, UUID member, UUID video, long version, String type) throws Exception {
    var b = songBody(member, type);
    b.put("version", version);
    b.put("visibility", "PUBLISHED");
    b.put("representativeVideoId", video);
    mvc.perform(json(put(ROOT + "/songs/" + song), b)).andExpect(status().isOk());
  }

  @Test
  void draftRegistrationPublicationAndHiddenRoundTrip() throws Exception {
    UUID member = member(), song = song(member, "COVER");
    mvc.perform(get("/api/v1/songs/" + song)).andExpect(status().isNotFound());
    UUID video = attach(song, REVIEW, 0, 0, "OFFICIAL_COVER");
    assertThat(writer.queryForObject("SELECT review_status FROM app.review_item", String.class))
        .isEqualTo("REGISTERED");
    assertThat(writer.queryForObject("SELECT registered_video_id FROM app.review_item", UUID.class))
        .isEqualTo(video);
    publish(song, member, video, 1, "COVER");
    mvc.perform(get("/api/v1/songs/" + song))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.song.title").value("Manual title"))
        .andExpect(jsonPath("$.representativeVideo.embeddable").value(false));
    var b = songBody(member, "COVER");
    b.put("version", 2);
    b.put("visibility", "HIDDEN");
    b.put("representativeVideoId", video);
    mvc.perform(json(put(ROOT + "/songs/" + song), b)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/songs/" + song)).andExpect(status().isNotFound());
    publish(song, member, video, 3, "COVER");
    assertThat(
            writer.queryForObject(
                "SELECT count(*) FROM app.catalog_audit WHERE target_type='SONGS'", Long.class))
        .isEqualTo(5);
  }

  @Test
  void accessRequiresCurrentAdminRoleAndCsrf() throws Exception {
    mvc.perform(get(ROOT + "/members")).andExpect(status().isUnauthorized());
    mvc.perform(auth(get(ROOT + "/members"), user)).andExpect(status().isForbidden());
    mvc.perform(
            post(ROOT + "/members")
                .cookie(admin.cookie())
                .contentType("application/json")
                .content(mapper.writeValueAsString(memberBody())))
        .andExpect(status().isForbidden());
    writer.update("UPDATE app.app_user SET role='USER' WHERE id=?", ADMIN);
    mvc.perform(auth(get(ROOT + "/members"), admin)).andExpect(status().isForbidden());
    mvc.perform(json(delete(ROOT + "/members/" + USER), memberBody()))
        .andExpect(status().isForbidden());
    assertThat(writer.queryForObject("SELECT count(*) FROM app.member", Long.class)).isZero();
  }

  @Test
  void staleAndMissingVersionsDoNotReplaceAliases() throws Exception {
    UUID id = member();
    var b = memberBody();
    b.put("name", "Changed");
    b.put("aliases", List.of("replacement"));
    mvc.perform(json(put(ROOT + "/members/" + id), b)).andExpect(status().isOk());
    mvc.perform(json(put(ROOT + "/members/" + id), b))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CATALOG_VERSION_CONFLICT"));
    b.remove("version");
    mvc.perform(json(put(ROOT + "/members/" + id), b)).andExpect(status().isBadRequest());
    assertThat(writer.queryForObject("SELECT alias FROM app.member_alias", String.class))
        .isEqualTo("replacement");
  }

  @Test
  void graduationPreservesSongRelationshipsAndCounts() throws Exception {
    UUID id = member(),
        song = song(id, "COVER"),
        video = attach(song, REVIEW, 0, 0, "OFFICIAL_COVER");
    publish(song, id, video, 1, "COVER");
    var b = memberBody();
    b.put("activityStatus", "GRADUATED");
    mvc.perform(json(put(ROOT + "/members/" + id), b)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/members/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.member.activityStatus").value("GRADUATED"))
        .andExpect(jsonPath("$.member.songCounts.total").value(1));
  }

  @Test
  void memberProfilesRoundTripThroughAdminAndPublicApisAndCanBeCleared() throws Exception {
    var body = memberBody();
    body.put("unitName", "  Mystic  ");
    body.put("chzzkUrl", " https://chzzk.naver.com/member ");
    body.put("xUrl", "https://x.com/member");
    var item = create("/members", body).path("item");
    UUID id = UUID.fromString(item.path("id").asText());
    assertThat(item.path("generation").asInt()).isEqualTo(1);
    assertThat(item.path("unitName").asText()).isEqualTo("Mystic");
    assertThat(item.path("chzzkUrl").asText()).isEqualTo("https://chzzk.naver.com/member");
    assertThat(item.path("xUrl").asText()).isEqualTo("https://x.com/member");
    mvc.perform(auth(get(ROOT + "/members/" + id), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.unitName").value("Mystic"))
        .andExpect(jsonPath("$.chzzkUrl").value("https://chzzk.naver.com/member"))
        .andExpect(jsonPath("$.xUrl").value("https://x.com/member"));
    mvc.perform(get("/api/v1/members"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].unitName").value("Mystic"))
        .andExpect(jsonPath("$.items[0].chzzkUrl").value("https://chzzk.naver.com/member"))
        .andExpect(jsonPath("$.items[0].xUrl").value("https://x.com/member"));
    mvc.perform(get("/api/v1/members/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.member.unitName").value("Mystic"))
        .andExpect(jsonPath("$.channels").isEmpty());

    body.put("unitName", "Universe");
    body.put("chzzkUrl", "https://chzzk.naver.com/updated");
    body.put("xUrl", "https://x.com/updated");
    mvc.perform(json(put(ROOT + "/members/" + id), body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.item.version").value(1))
        .andExpect(jsonPath("$.item.unitName").value("Universe"))
        .andExpect(jsonPath("$.item.chzzkUrl").value("https://chzzk.naver.com/updated"))
        .andExpect(jsonPath("$.item.xUrl").value("https://x.com/updated"));
    mvc.perform(json(put(ROOT + "/members/" + id), body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CATALOG_VERSION_CONFLICT"));
    mvc.perform(auth(get(ROOT + "/members/" + id + "/audit"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].before.unitName").value("Mystic"))
        .andExpect(jsonPath("$.items[0].after.unitName").value("Universe"));

    body.put("version", 1);
    body.put("unitName", "  ");
    body.put("chzzkUrl", null);
    body.remove("xUrl");
    var cleared =
        mapper
            .readTree(
                mvc.perform(json(put(ROOT + "/members/" + id), body))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("item");
    assertThat(cleared.has("unitName") && cleared.path("unitName").isNull()).isTrue();
    assertThat(cleared.has("chzzkUrl") && cleared.path("chzzkUrl").isNull()).isTrue();
    assertThat(cleared.has("xUrl") && cleared.path("xUrl").isNull()).isTrue();
    var member =
        mapper
            .readTree(
                mvc.perform(get("/api/v1/members/" + id))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("member");
    for (var field : List.of("unitName", "chzzkUrl", "xUrl"))
      assertThat(member.has(field) && member.path(field).isNull()).as(field).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"chzzkUrl", "xUrl"})
  void invalidProfileLinksDoNotPartiallyUpdateMember(String field) throws Exception {
    UUID id = member();
    var body = memberBody();
    body.put("unitName", "Must not be saved");
    body.put(field, "javascript:alert(1)");
    mvc.perform(json(put(ROOT + "/members/" + id), body)).andExpect(status().isBadRequest());
    assertThat(writer.queryForObject("SELECT version FROM app.member WHERE id=?", Long.class, id))
        .isZero();
    assertThat(
            writer.queryForObject("SELECT unit_name FROM app.member WHERE id=?", String.class, id))
        .isNull();
    body.put(field, "https://example.invalid/" + "a".repeat(2000));
    mvc.perform(json(put(ROOT + "/members/" + id), body)).andExpect(status().isBadRequest());
    body.remove(field);
    body.put("unitName", "a".repeat(101));
    mvc.perform(json(put(ROOT + "/members/" + id), body)).andExpect(status().isBadRequest());
  }

  @ParameterizedTest
  @ValueSource(strings = {"PRIVATE", "UNAVAILABLE", "EXPIRED", "IGNORED", "DISABLED", "FUTURE"})
  void nonPublicStaleIgnoredAndDisabledCandidatesStayUnregistered(String condition)
      throws Exception {
    UUID song = song(member(), "COVER");
    switch (condition) {
      case "PRIVATE", "UNAVAILABLE" ->
          writer.update("UPDATE app.review_item SET availability=?", condition);
      case "EXPIRED" ->
          writer.execute("UPDATE app.review_item SET source_observed_at=now()-interval '31 days'");
      case "IGNORED" -> writer.execute("UPDATE app.review_item SET review_status='IGNORED'");
      case "DISABLED" -> writer.execute("UPDATE app.channel SET collection_enabled=false");
      case "FUTURE" ->
          writer.execute("UPDATE app.review_item SET source_published_at=now()+interval '1 day'");
    }
    mvc.perform(
            json(
                post(ROOT + "/reviews/" + REVIEW + "/registration"),
                Map.of(
                    "version",
                    0,
                    "songVersion",
                    0,
                    "songId",
                    song,
                    "kind",
                    "OFFICIAL_COVER",
                    "reason",
                    "fixture")))
        .andExpect(status().isConflict());
    assertThat(writer.queryForObject("SELECT count(*) FROM app.video", Long.class)).isZero();
    assertThat(writer.queryForObject("SELECT version FROM app.song_entry", Long.class)).isZero();
    assertThat(
            writer.queryForObject(
                "SELECT count(*) FROM app.catalog_audit WHERE target_type='REVIEWS'", Long.class))
        .isZero();
  }

  @Test
  void duplicateRegistrationAndRegisteredReviewRestoreAreBlocked() throws Exception {
    UUID m = member(), song = song(m, "COVER");
    attach(song, REVIEW, 0, 0, "OFFICIAL_COVER");
    mvc.perform(
            json(
                post(ROOT + "/reviews/" + REVIEW + "/registration"),
                Map.of(
                    "version",
                    1,
                    "songVersion",
                    1,
                    "songId",
                    song,
                    "kind",
                    "OFFICIAL_COVER",
                    "reason",
                    "duplicate")))
        .andExpect(status().isConflict());
    mvc.perform(
            json(
                patch(ROOT + "/reviews/" + REVIEW),
                Map.of("version", 1, "status", "PENDING", "reason", "restore")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REVIEW_ALREADY_REGISTERED"));
    mvc.perform(auth(get(ROOT + "/reviews").param("status", "REGISTERED"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].registeredVideoId").exists());
    assertThat(writer.queryForObject("SELECT count(*) FROM app.video", Long.class)).isEqualTo(1);
  }

  @Test
  void officialMvHasPriorityAndNoAutomaticMergeOrRepresentativeReplacement() throws Exception {
    UUID m = member(), song = song(m, "ORIGINAL"), audio = attach(song, REVIEW, 0, 0, "AUDIO");
    publish(song, m, audio, 1, "ORIGINAL");
    UUID r = UUID.randomUUID();
    seed(r, "12345678901");
    mvc.perform(
            json(
                post(ROOT + "/reviews/" + r + "/registration"),
                Map.of(
                    "version",
                    0,
                    "songVersion",
                    2,
                    "songId",
                    song,
                    "kind",
                    "OFFICIAL_MV",
                    "reason",
                    "same recording")))
        .andExpect(status().isBadRequest());
    assertThat(writer.queryForObject("SELECT count(*) FROM app.video", Long.class)).isEqualTo(1);
    var b = songBody(m, "ORIGINAL");
    b.put("version", 2);
    b.put("visibility", "DRAFT");
    b.put("representativeVideoId", audio);
    mvc.perform(json(put(ROOT + "/songs/" + song), b)).andExpect(status().isOk());
    UUID mv = attach(song, r, 0, 3, "OFFICIAL_MV");
    publish(song, m, mv, 4, "ORIGINAL");
    assertThat(writer.queryForObject("SELECT count(*) FROM app.song_entry", Long.class))
        .isEqualTo(1);
    assertThat(
            writer.queryForObject("SELECT representative_video_id FROM app.song_entry", UUID.class))
        .isEqualTo(mv);
  }

  @Test
  void publicationRequiresMemberAndRepresentativeButOptionalWorkCanRemainMissing()
      throws Exception {
    UUID s = song(null, "COVER"), v = attach(s, REVIEW, 0, 0, "OFFICIAL_COVER");
    var b = songBody(null, "COVER");
    b.put("version", 1);
    b.put("visibility", "PUBLISHED");
    b.put("representativeVideoId", v);
    mvc.perform(json(put(ROOT + "/songs/" + s), b))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SONG_NOT_PUBLISHABLE"));
    assertThat(writer.queryForObject("SELECT version FROM app.song_entry", Long.class))
        .isEqualTo(1);
  }

  @Test
  void urlInputOnlyRegistersObservedCanonicalYoutubeVideos() throws Exception {
    UUID s = song(member(), "COVER");
    create(
        "/songs/" + s + "/videos",
        Map.of(
            "version",
            0,
            "reviewVersion",
            0,
            "videoUrl",
            "https://youtu.be/abcdefghijk?t=5",
            "kind",
            "OFFICIAL_COVER",
            "reason",
            "verified"));
    mvc.perform(
            json(
                post(ROOT + "/songs/" + s + "/videos"),
                Map.of(
                    "version",
                    1,
                    "reviewVersion",
                    0,
                    "videoUrl",
                    "https://youtube.com/watch?v=12345678901",
                    "kind",
                    "OFFICIAL_COVER",
                    "reason",
                    "not discovered")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VIDEO_REQUIRES_OBSERVATION"));
  }

  @Test
  void workArtistExternalParticipantLinksKaraokeAndSearchHelpStaySeparate() throws Exception {
    UUID m = member(),
        artist =
            UUID.fromString(
                create(
                        "/artists",
                        Map.of(
                            "version",
                            0,
                            "name",
                            "External",
                            "aliases",
                            List.of("外部"),
                            "reason",
                            "verified"))
                    .path("item")
                    .path("id")
                    .asText());
    var w = new HashMap<String, Object>();
    w.put("version", 0);
    w.put("title", "Original work");
    w.put("aliases", List.of("原曲"));
    w.put("artistIds", List.of(artist));
    w.put(
        "links",
        List.of(
            Map.of(
                "platform",
                "Spotify",
                "url",
                "https://open.spotify.com/track/test",
                "sourceUrl",
                "https://example.invalid/credits")));
    w.put(
        "karaoke",
        List.of(
            Map.of(
                "provider",
                "TJ",
                "status",
                "REGISTERED",
                "number",
                "00123",
                "sourceUrl",
                "https://example.invalid/tj")));
    w.put("reason", "verified");
    UUID work = UUID.fromString(create("/works", w).path("item").path("id").asText());
    var dup = create("/works", w);
    assertThat(dup.path("possibleDuplicateIds").get(0).asText()).isEqualTo(work.toString());
    UUID s = song(m, "COVER"), v = attach(s, REVIEW, 0, 0, "OFFICIAL_COVER");
    var b = songBody(m, "COVER");
    b.put("version", 1);
    b.put("workId", work);
    b.put("visibility", "PUBLISHED");
    b.put("representativeVideoId", v);
    b.put("externalArtistIds", List.of(artist));
    b.put("isSpecialEvent", true);
    b.put("specialEventLabel", "Anniversary");
    b.put("searchVisibility", "DIFFICULT");
    b.put("recommendedSearchQuery", "Member song");
    b.put(
        "karaoke",
        List.of(
            Map.of(
                "provider",
                "KY",
                "status",
                "NOT_LISTED",
                "sourceUrl",
                "https://example.invalid/ky")));
    mvc.perform(json(put(ROOT + "/songs/" + s), b)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/songs/" + s))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.song.collaboration").value(true))
        .andExpect(jsonPath("$.workResources.karaoke[0].number").value("00123"))
        .andExpect(jsonPath("$.karaoke[0].status").value("UNKNOWN"))
        .andExpect(jsonPath("$.karaoke[1].status").value("NOT_LISTED"))
        .andExpect(jsonPath("$.searchHelp.recommendedQuery").value("Member song"));
  }

  @Test
  void failedAuditRollsBackEntityAndChildChanges() throws Exception {
    UUID m = member();
    var input =
        new MemberInput(
            0L,
            "Must rollback",
            1,
            null,
            null,
            null,
            Activity.ACTIVE,
            null,
            null,
            null,
            null,
            List.of("replaced"),
            "fixture");
    assertThatThrownBy(() -> service.member(m, input, UUID.randomUUID()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(writer.queryForObject("SELECT name FROM app.member", String.class))
        .isEqualTo("Member");
    assertThat(writer.queryForObject("SELECT alias FROM app.member_alias", String.class))
        .isEqualTo("メンバー");
    assertThat(writer.queryForObject("SELECT version FROM app.member", Long.class)).isZero();
  }

  @Test
  void webAndCollectorCannotAssignRolesOrOverwriteSourceAndOperatorIsAudited() throws Exception {
    assertThatThrownBy(
            () -> runtime.update("UPDATE app.app_user SET role='ADMIN' WHERE id=?", USER))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    writer.execute(
        new String(
            getClass().getResourceAsStream("/admin-operator-grants.sql").readAllBytes(),
            StandardCharsets.UTF_8));
    var op =
        new AdminAccountCommand(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_operator", "test-operator"));
    assertThat(op.change(USER, "ADMIN", "operator verified")).isTrue();
    assertThat(op.change(USER, "ADMIN", "repeat")).isFalse();
    mvc.perform(auth(get(ROOT + "/members"), user)).andExpect(status().isOk());
    assertThat(op.change(USER, "USER", "revoke")).isTrue();
    mvc.perform(auth(get(ROOT + "/members"), user)).andExpect(status().isForbidden());
    assertThat(
            writer.queryForObject(
                "SELECT count(*) FROM app.catalog_audit WHERE target_type='ACCOUNT_ROLE'",
                Long.class))
        .isEqualTo(2);
    assertThatThrownBy(() -> runtime.execute("UPDATE app.review_item SET source_title='overwrite'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    UUID s = song(member(), "COVER");
    attach(s, REVIEW, 0, 0, "OFFICIAL_COVER");
    assertThatThrownBy(() -> runtime.execute("UPDATE app.video SET source_title='overwrite'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    var collector =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_collector", "test-collector"));
    assertThatThrownBy(() -> collector.execute("SELECT * FROM app.catalog_audit"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void auditDoesNotRetainYoutubeSourceMetadata() throws Exception {
    UUID s = song(member(), "COVER");
    attach(s, REVIEW, 0, 0, "OFFICIAL_COVER");
    assertThat(
            writer.queryForObject(
                "SELECT string_agg(after_value::text,'') FROM app.catalog_audit", String.class))
        .doesNotContain("source cover")
        .doesNotContain("example.invalid/source");
  }

  @Test
  void invalidBirthdayDuplicateParticipantsSpecialLabelAndLinksDoNotMutate() throws Exception {
    var member = memberBody();
    member.put("birthdayDay", 30);
    mvc.perform(json(post(ROOT + "/members"), member)).andExpect(status().isBadRequest());
    UUID m = member();
    var b = songBody(m, "COVER");
    b.put("memberIds", List.of(m, m));
    mvc.perform(json(post(ROOT + "/songs"), b)).andExpect(status().isBadRequest());
    b.put("memberIds", List.of(m));
    b.put("isSpecialEvent", true);
    b.put("specialEventLabel", " ");
    mvc.perform(json(post(ROOT + "/songs"), b)).andExpect(status().isBadRequest());
    b.put("isSpecialEvent", false);
    b.put(
        "links",
        List.of(
            Map.of(
                "platform",
                "test",
                "url",
                "javascript:alert(1)",
                "sourceUrl",
                "https://example.invalid")));
    mvc.perform(json(post(ROOT + "/songs"), b)).andExpect(status().isBadRequest());
    assertThat(writer.queryForObject("SELECT count(*) FROM app.song_entry", Long.class)).isZero();
  }

  @Test
  void simultaneousEditsCommitExactlyOneVersionAndAudit() throws Exception {
    UUID id = member();
    var barrier = new java.util.concurrent.CyclicBarrier(2);
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (String name : List.of("First", "Second"))
        futures.add(
            pool.submit(
                () -> {
                  try {
                    new TransactionTemplate(transactions)
                        .execute(
                            tx -> {
                              entities.find(com.stelody.admin.domain.ManagedMember.class, id);
                              try {
                                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                              } catch (Exception e) {
                                throw new RuntimeException(e);
                              }
                              service.member(
                                  id,
                                  new MemberInput(
                                      0L,
                                      name,
                                      1,
                                      null,
                                      null,
                                      null,
                                      Activity.ACTIVE,
                                      null,
                                      null,
                                      null,
                                      null,
                                      List.of(name),
                                      "concurrent fixture"),
                                  ADMIN);
                              return null;
                            });
                    return true;
                  } catch (jakarta.persistence.OptimisticLockException
                      | org.springframework.dao.OptimisticLockingFailureException e) {
                    return false;
                  }
                }));
      assertThat(
              List.of(
                  futures.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS),
                  futures.get(1).get(10, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(writer.queryForObject("SELECT version FROM app.member", Long.class)).isEqualTo(1);
    assertThat(writer.queryForObject("SELECT name FROM app.member", String.class))
        .isEqualTo(writer.queryForObject("SELECT alias FROM app.member_alias", String.class));
    assertThat(
            writer.queryForObject(
                "SELECT count(*) FROM app.catalog_audit WHERE target_type='MEMBERS'", Long.class))
        .isEqualTo(2);
  }

  @Test
  void refreshedCandidateConflictsAndRollsBackVideoAndParentVersion() throws Exception {
    UUID s = song(member(), "COVER");
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .execute(
                        tx -> {
                          entities.find(com.stelody.review.domain.ReviewItem.class, REVIEW);
                          writer.execute(
                              "UPDATE app.review_item SET source_title='updated source',version=version+1");
                          service.register(
                              REVIEW,
                              new Registration(
                                  0L, s, 0L, VideoKind.OFFICIAL_COVER, "concurrent source"),
                              ADMIN);
                          return null;
                        }))
        .isInstanceOfAny(
            jakarta.persistence.OptimisticLockException.class,
            org.springframework.dao.OptimisticLockingFailureException.class);
    assertThat(writer.queryForObject("SELECT count(*) FROM app.video", Long.class)).isZero();
    assertThat(writer.queryForObject("SELECT version FROM app.song_entry", Long.class)).isZero();
    assertThat(writer.queryForObject("SELECT review_status FROM app.review_item", String.class))
        .isEqualTo("PENDING");
  }

  @Test
  void collectorRefreshPreservesManualFieldsAndHiddenVisibility() throws Exception {
    UUID m = member(), s = song(m, "COVER"), v = attach(s, REVIEW, 0, 0, "OFFICIAL_COVER");
    var b =
        Map.of(
            "version",
            0,
            "kind",
            "OFFICIAL_COVER",
            "publishedAt",
            "2025-01-01T00:00:00Z",
            "thumbnailUrl",
            "https://example.invalid/manual",
            "reason",
            "manual override");
    mvc.perform(json(put(ROOT + "/songs/" + s + "/videos/" + v).param("songVersion", "1"), b))
        .andExpect(status().isOk());
    var body = songBody(m, "COVER");
    body.put("version", 2);
    body.put("visibility", "HIDDEN");
    body.put("representativeVideoId", v);
    body.put("isSpecialEvent", true);
    body.put("specialEventLabel", "keep label");
    mvc.perform(json(put(ROOT + "/songs/" + s), body)).andExpect(status().isOk());
    writer.execute(
        new String(
            getClass().getResourceAsStream("/collector-grants.sql").readAllBytes(),
            StandardCharsets.UTF_8));
    var collector =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), "stelody_collector", "test-collector"));
    collector.update(
        "UPDATE app.video SET source_title='refreshed source',source_published_at=now(),source_thumbnail_url='https://example.invalid/new',source_observed_at=now(),availability='PUBLIC',embeddable=true WHERE id=?",
        v);
    assertThat(writer.queryForObject("SELECT visibility FROM app.song_entry", String.class))
        .isEqualTo("HIDDEN");
    assertThat(
            writer.queryForObject("SELECT special_event_label FROM app.song_entry", String.class))
        .isEqualTo("keep label");
    assertThat(writer.queryForObject("SELECT thumbnail_url FROM app.video", String.class))
        .isEqualTo("https://example.invalid/manual");
    mvc.perform(get("/api/v1/songs/" + s)).andExpect(status().isNotFound());
  }

  @Test
  void channelsRequireExplicitOfficialAssociationAndImmutableIdentity() throws Exception {
    UUID m = member();
    var body = new HashMap<String, Object>();
    body.put("version", 0);
    body.put("youtubeId", "UCbbbbbbbbbbbbbbbbbbbbbb");
    body.put("name", "Personal");
    body.put("memberId", m);
    body.put("channelType", "MEMBER");
    body.put("collectionEnabled", true);
    body.put("reason", "verified");
    UUID id = UUID.fromString(create("/channels", body).path("id").asText());
    mvc.perform(get("/api/v1/members/" + m))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.channels[0].youtubeId").value("UCbbbbbbbbbbbbbbbbbbbbbb"));
    body.put("collectionEnabled", false);
    mvc.perform(json(put(ROOT + "/channels/" + id), body)).andExpect(status().isOk());
    body.put("version", 1);
    body.put("youtubeId", "UCcccccccccccccccccccccc");
    mvc.perform(json(put(ROOT + "/channels/" + id), body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CHANNEL_IDENTITY_IMMUTABLE"));
    body.put("version", 0);
    body.put("channelType", "EXTERNAL");
    body.put("collectionEnabled", true);
    body.remove("memberId");
    mvc.perform(json(post(ROOT + "/channels"), body)).andExpect(status().isBadRequest());
  }

  @ParameterizedTest
  @ValueSource(strings = {"PRIVATE", "EXPIRED", "OTHER"})
  void publicationRechecksSourceAndRepresentativeKind(String condition) throws Exception {
    UUID m = member(),
        s = song(m, "COVER"),
        v = attach(s, REVIEW, 0, 0, condition.equals("OTHER") ? "OTHER" : "OFFICIAL_COVER");
    if (condition.equals("PRIVATE")) writer.execute("UPDATE app.video SET availability='PRIVATE'");
    if (condition.equals("EXPIRED"))
      writer.execute("UPDATE app.video SET source_observed_at=now()-interval '31 days'");
    var b = songBody(m, "COVER");
    b.put("version", 1);
    b.put("visibility", "PUBLISHED");
    b.put("representativeVideoId", v);
    mvc.perform(json(put(ROOT + "/songs/" + s), b)).andExpect(status().isBadRequest());
    assertThat(writer.queryForObject("SELECT visibility FROM app.song_entry", String.class))
        .isEqualTo("DRAFT");
    assertThat(writer.queryForObject("SELECT version FROM app.song_entry", Long.class))
        .isEqualTo(1);
  }

  @Test
  void managementListsEscapeSearchAndAuditIsPaged() throws Exception {
    UUID m = member();
    mvc.perform(auth(get(ROOT + "/members").param("q", "メンバー"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(m.toString()));
    mvc.perform(auth(get(ROOT + "/members").param("q", "%"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
    mvc.perform(auth(get(ROOT + "/members").param("q", "Member").param("size", "1"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(m.toString()));
    mvc.perform(auth(get(ROOT + "/members/" + m + "/audit"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].actorId").value(ADMIN.toString()))
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  @Test
  void registeredCandidateAuditIsRetrievableWithLinkedVideoAndSong() throws Exception {
    UUID song = song(member(), "COVER");
    UUID video = attach(song, REVIEW, 0, 0, "OFFICIAL_COVER");
    mvc.perform(auth(get(ROOT + "/reviews/" + REVIEW + "/audit"), admin))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.items[0].targetType").value("REVIEWS"))
        .andExpect(jsonPath("$.items[0].targetId").value(REVIEW.toString()))
        .andExpect(jsonPath("$.items[0].actorId").value(ADMIN.toString()))
        .andExpect(jsonPath("$.items[0].action").value("REGISTER"))
        .andExpect(jsonPath("$.items[0].reason").value("same recording confirmed"))
        .andExpect(jsonPath("$.items[0].before.status").value("PENDING"))
        .andExpect(jsonPath("$.items[0].after.status").value("REGISTERED"))
        .andExpect(jsonPath("$.items[0].after.videoId").value(video.toString()))
        .andExpect(jsonPath("$.items[0].after.songId").value(song.toString()))
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  @Test
  void videoAuditExposesManualChangesAndPagesWithoutRetainingSourceMetadata() throws Exception {
    UUID song = song(member(), "COVER");
    UUID video = attach(song, REVIEW, 0, 0, "OFFICIAL_COVER");
    String path = ROOT + "/videos/" + video + "/audit";
    mvc.perform(auth(get(path), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
    var input = new HashMap<String, Object>();
    input.put("version", 0);
    input.put("kind", "OTHER");
    input.put("publishedAt", "2026-01-01T00:00:00Z");
    input.put("thumbnailUrl", "https://example.invalid/manual-thumbnail");
    input.put("reason", "manual details verified");
    mvc.perform(
            json(
                put(ROOT + "/songs/" + song + "/videos/" + video).param("songVersion", "1"), input))
        .andExpect(status().isOk());
    var result =
        mvc.perform(auth(get(path), admin))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.items[0].targetType").value("VIDEOS"))
            .andExpect(jsonPath("$.items[0].targetId").value(video.toString()))
            .andExpect(jsonPath("$.items[0].actorId").value(ADMIN.toString()))
            .andExpect(jsonPath("$.items[0].action").value("UPDATE"))
            .andExpect(jsonPath("$.items[0].reason").value("manual details verified"))
            .andExpect(jsonPath("$.items[0].before.kind").value("OFFICIAL_COVER"))
            .andExpect(jsonPath("$.items[0].after.kind").value("OTHER"))
            .andExpect(jsonPath("$.items[0].after.publishedAt").value("2026-01-01T00:00:00Z"))
            .andExpect(
                jsonPath("$.items[0].after.thumbnailUrl")
                    .value("https://example.invalid/manual-thumbnail"))
            .andReturn();
    var audit = mapper.readTree(result.getResponse().getContentAsString()).path("items").get(0);
    for (String field :
        List.of(
            "sourceTitle",
            "sourcePublishedAt",
            "sourceThumbnailUrl",
            "sourceObservedAt",
            "availability",
            "embeddable")) {
      assertThat(audit.path("before").has(field)).isFalse();
      assertThat(audit.path("after").has(field)).isFalse();
    }
    input.put("version", 1);
    input.put("kind", "OFFICIAL_COVER");
    input.put("publishedAt", null);
    input.put("thumbnailUrl", null);
    mvc.perform(
            json(
                put(ROOT + "/songs/" + song + "/videos/" + video).param("songVersion", "2"), input))
        .andExpect(status().isOk());
    mvc.perform(auth(get(path).param("size", "1"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].after.version").value(2))
        .andExpect(jsonPath("$.hasNext").value(true));
    mvc.perform(auth(get(path).param("size", "1").param("page", "1"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].after.version").value(1))
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  @ParameterizedTest
  @ValueSource(strings = {"videos", "reviews"})
  void videoAndReviewAuditsRequireCurrentAdminAndValidateTargetAndPage(String resource)
      throws Exception {
    UUID song = song(member(), "COVER");
    UUID video = attach(song, REVIEW, 0, 0, "OFFICIAL_COVER");
    String path =
        ROOT + "/" + resource + "/" + (resource.equals("videos") ? video : REVIEW) + "/audit";
    mvc.perform(get(path)).andExpect(status().isUnauthorized());
    mvc.perform(auth(get(path), user)).andExpect(status().isForbidden());
    mvc.perform(auth(get(ROOT + "/" + resource + "/" + UUID.randomUUID() + "/audit"), admin))
        .andExpect(status().isNotFound());
    mvc.perform(auth(get(path).param("page", "-1"), admin)).andExpect(status().isBadRequest());
    mvc.perform(auth(get(path).param("size", "51"), admin)).andExpect(status().isBadRequest());
    writer.update("UPDATE app.app_user SET role='USER' WHERE id=?", ADMIN);
    mvc.perform(auth(get(path), admin)).andExpect(status().isForbidden());
  }

  @Test
  void duplicateChannelIsAConflictAndLeavesNoExtraAudit() throws Exception {
    var body =
        Map.of(
            "version",
            0,
            "youtubeId",
            "UCbbbbbbbbbbbbbbbbbbbbbb",
            "name",
            "Official",
            "channelType",
            "GROUP",
            "collectionEnabled",
            true,
            "reason",
            "verified");
    create("/channels", body);
    mvc.perform(json(post(ROOT + "/channels"), body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CATALOG_DATA_CONFLICT"));
    assertThat(
            writer.queryForObject(
                "SELECT count(*) FROM app.catalog_audit WHERE target_type='CHANNELS'", Long.class))
        .isEqualTo(1);
  }
}
