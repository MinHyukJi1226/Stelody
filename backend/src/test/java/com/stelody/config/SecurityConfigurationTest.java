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
  com.stelody.auth.web.ApiProblems.class,
  com.stelody.auth.web.LoginHandlers.class,
  com.stelody.auth.service.GoogleOidcUserService.class
})
class SecurityConfigurationTest {
  @Autowired MockMvc mvc;

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

  @Test
  void missingGoogleConfigurationIsReportedWithoutFakeLogin() throws Exception {
    mvc.perform(get("/api/v1/auth/google"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("GOOGLE_LOGIN_UNAVAILABLE"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
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
    mvc.perform(get("/api/v1/admin/songs").with(user("admin").roles("ADMIN")))
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
