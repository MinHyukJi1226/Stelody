package com.stelody.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest
@Import(SecurityConfiguration.class)
class SecurityConfigurationTest {
  @Autowired MockMvc mvc;

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
