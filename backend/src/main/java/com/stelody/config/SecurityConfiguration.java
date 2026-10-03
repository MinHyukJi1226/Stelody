package com.stelody.config;

import com.stelody.auth.service.GoogleOidcUserService;
import com.stelody.auth.web.AccountSessionFilter;
import com.stelody.auth.web.ApiProblems;
import com.stelody.auth.web.LoginHandlers;
import com.stelody.user.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.session.FindByIndexNameSessionRepository;

@Configuration
public class SecurityConfiguration {
  @Bean
  UserDetailsService userDetailsService() {
    return username -> {
      throw new UsernameNotFoundException("Password login is disabled");
    };
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ApiProblems problems,
      AccountService accounts,
      LoginHandlers handlers,
      GoogleOidcUserService googleUsers,
      ObjectProvider<ClientRegistrationRepository> clients,
      ObjectProvider<FindByIndexNameSessionRepository<?>> sessions)
      throws Exception {
    http.authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(
                        HttpMethod.GET,
                        "/actuator/health",
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/google",
                        "/api/v1/auth/authorize/google",
                        "/api/v1/auth/callback/google",
                        "/api/v1/songs",
                        "/api/v1/songs/trending",
                        "/api/v1/songs/{id}",
                        "/api/v1/songs/{id}/views",
                        "/api/v1/members",
                        "/api/v1/members/{id}",
                        "/api/v1/members/{id}/songs")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/me",
                        "/api/v1/me/favorites",
                        "/api/v1/me/favorites/{songId}")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.GET, "/api/v1/admin/reviews", "/api/v1/admin/reviews/{id}")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/reviews/{id}")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/admin/members",
                        "/api/v1/admin/members/{id}",
                        "/api/v1/admin/members/{id}/audit",
                        "/api/v1/admin/artists",
                        "/api/v1/admin/artists/{id}",
                        "/api/v1/admin/artists/{id}/audit",
                        "/api/v1/admin/works",
                        "/api/v1/admin/works/{id}",
                        "/api/v1/admin/works/{id}/audit",
                        "/api/v1/admin/songs",
                        "/api/v1/admin/songs/{id}",
                        "/api/v1/admin/songs/{id}/audit",
                        "/api/v1/admin/channels",
                        "/api/v1/admin/channels/{id}",
                        "/api/v1/admin/channels/{id}/audit",
                        "/api/v1/admin/videos/{id}/audit",
                        "/api/v1/admin/reviews/{id}/audit")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/admin/members",
                        "/api/v1/admin/artists",
                        "/api/v1/admin/works",
                        "/api/v1/admin/channels",
                        "/api/v1/admin/songs",
                        "/api/v1/admin/songs/{id}/videos",
                        "/api/v1/admin/reviews/{id}/registration")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        HttpMethod.PUT,
                        "/api/v1/admin/members/{id}",
                        "/api/v1/admin/artists/{id}",
                        "/api/v1/admin/works/{id}",
                        "/api/v1/admin/channels/{id}",
                        "/api/v1/admin/songs/{id}",
                        "/api/v1/admin/songs/{id}/videos/{videoId}")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/api/v1/me/favorites/{songId}")
                    .authenticated()
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/me/favorites/{songId}")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/me/playlists",
                        "/api/v1/me/playlists/{id}",
                        "/api/v1/me/playlists/{id}/items")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/me/playlists", "/api/v1/me/playlists/{id}/items")
                    .authenticated()
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/me/playlists/{id}")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.DELETE,
                        "/api/v1/me/playlists/{id}",
                        "/api/v1/me/playlists/{id}/items/{itemId}")
                    .authenticated()
                    .requestMatchers(HttpMethod.PUT, "/api/v1/me/playlists/{id}/order")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/me/youtube/connection",
                        "/api/v1/me/youtube/callback",
                        "/api/v1/me/youtube-exports/{id}")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/me/youtube/authorizations",
                        "/api/v1/me/playlists/{id}/youtube-exports",
                        "/api/v1/me/youtube-exports/{id}/retry",
                        "/api/v1/me/youtube-exports/{id}/cancel")
                    .authenticated()
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/me/youtube/connection")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .requestCache(cache -> cache.requestCache(new NullRequestCache()))
        .addFilterAfter(
            new AccountSessionFilter(accounts, problems, sessions),
            SecurityContextHolderFilter.class)
        .logout(
            logout ->
                logout
                    .logoutUrl("/api/v1/auth/logout")
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies("SESSION")
                    .logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            problems.write(
                                request, response, 401, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다"))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            problems.write(
                                request, response, 403, "ACCESS_DENIED", "요청을 허용할 수 없습니다")));
    if (clients.getIfAvailable() != null) {
      var resolver =
          new DefaultOAuth2AuthorizationRequestResolver(
              clients.getObject(), "/api/v1/auth/authorize");
      resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
      http.oauth2Login(
          oauth ->
              oauth
                  .clientRegistrationRepository(clients.getObject())
                  .authorizedClientRepository(new DiscardAuthorizedClients())
                  .loginPage("/api/v1/auth/google")
                  .authorizationEndpoint(
                      endpoint -> endpoint.authorizationRequestResolver(resolver))
                  .redirectionEndpoint(endpoint -> endpoint.baseUri("/api/v1/auth/callback/*"))
                  .userInfoEndpoint(endpoint -> endpoint.oidcUserService(googleUsers))
                  .successHandler(handlers::success)
                  .failureHandler(handlers::failure));
    }
    return http.build();
  }

  // Login needs no long-lived Google access. YouTube authorization will have its own storage.
  private static class DiscardAuthorizedClients implements OAuth2AuthorizedClientRepository {
    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
        String id, Authentication auth, HttpServletRequest request) {
      return null;
    }

    @Override
    public void saveAuthorizedClient(
        OAuth2AuthorizedClient client,
        Authentication auth,
        HttpServletRequest request,
        HttpServletResponse response) {}

    @Override
    public void removeAuthorizedClient(
        String id, Authentication auth, HttpServletRequest request, HttpServletResponse response) {}
  }
}
