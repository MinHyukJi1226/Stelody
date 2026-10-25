package com.stelody.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest
@Import({
  SecurityConfiguration.class,
  com.stelody.auth.web.GoogleLoginClients.class,
  com.stelody.auth.web.ReauthenticationClients.class,
  com.stelody.auth.web.ReauthenticationRedirects.class,
  com.stelody.auth.web.ApiProblems.class,
  com.stelody.auth.web.LoginHandlers.class,
  com.stelody.auth.service.GoogleOidcUserService.class
})
class SecurityConfigurationTest {
  @Autowired MockMvc mvc;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.auth.service.PersistentLoginService persistent;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.user.service.AccountService accounts;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.song.service.SongService songs;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.member.service.MemberService members;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.favorite.service.FavoriteService favorites;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.playlist.service.PlaylistService playlists;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.playlist.service.PlaylistItemService playlistItems;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.review.service.ReviewService reviews;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.review.service.ReviewBatchService reviewBatches;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.admin.service.CatalogManagementService catalogManagement;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.admin.service.InboxService inbox;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.statistics.service.ViewStatisticsService statistics;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.export.service.ExportService exports;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.export.service.YouTubeConnectionService youtubeConnections;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.export.web.YouTubeOAuthReturn youtubeReturns;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.collection.service.CollectionOperations collectionOperations;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.admin.service.CollectionRuleService collectionRules;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.admin.service.CoverPublicationService coverPublication;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.admin.service.SpecialReviewService specialReviews;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.auth.service.ReauthenticationService reauthentication;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.auth.web.ReauthenticationRequests reauthenticationRequests;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.user.service.WithdrawalService withdrawals;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.stelody.user.service.WithdrawalResults withdrawalResults;

  @Test
  void missingGoogleConfigurationIsReportedWithoutFakeLogin() throws Exception {
    mvc.perform(get("/api/v1/auth/google"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("GOOGLE_LOGIN_UNAVAILABLE"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  void localLoginIsRejectedWhenNotConfigured() throws Exception {
    for (String path : java.util.List.of("/api/v1/auth/google", "/api/v1/auth/authorize/google")) {
      var response =
          mvc.perform(get(path).servletPath(path).param("loginClient", "local"))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.code").value("INVALID_LOGIN_CLIENT"))
              .andReturn();
      org.assertj.core.api.Assertions.assertThat(response.getRequest().getSession(false)).isNull();
    }
  }

  @Test
  void csrfCanBeFetchedAnonymouslyAndCannotBeCached() throws Exception {
    mvc.perform(get("/api/v1/auth/csrf"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
        .andExpect(jsonPath("$.token").isNotEmpty())
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
  }

  @Test
  void anonymousApiRequestsAreUnauthorized() throws Exception {
    mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void unimplementedRoutesRemainClosedEvenForAuthenticatedUsers() throws Exception {
    mvc.perform(get("/api/v1/admin/unimplemented").with(user("admin").roles("ADMIN")))
        .andExpect(status().isForbidden());
  }

  @Test
  void mutationWithoutCsrfIsRejected() throws Exception {
    mvc.perform(post("/api/v1/example").with(user("user"))).andExpect(status().isForbidden());
  }

  @Test
  void actuatorDetailsAreNotPublic() throws Exception {
    mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
  }
}
