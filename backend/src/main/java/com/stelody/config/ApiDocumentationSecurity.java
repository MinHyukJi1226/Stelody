package com.stelody.config;

import com.stelody.auth.web.ApiProblems;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration
public class ApiDocumentationSecurity {
  @Bean
  @Order(0)
  SecurityFilterChain documentationSecurity(
      HttpSecurity http, Environment env, ApiProblems problems) throws Exception {
    boolean documentationProfile = env.acceptsProfiles(Profiles.of("local | test | prod"));
    boolean specification =
        documentationProfile && env.getProperty("springdoc.api-docs.enabled", Boolean.class, false);
    boolean ui =
        specification && env.getProperty("springdoc.swagger-ui.enabled", Boolean.class, false);
    http.securityMatcher(
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml",
            "/swagger-ui.html",
            "/swagger-ui/**")
        .authorizeHttpRequests(
            auth -> {
              if (specification)
                auth.requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs.yaml")
                    .permitAll();
              if (ui)
                auth.requestMatchers(
                        HttpMethod.GET,
                        "/v3/api-docs/swagger-config",
                        "/swagger-ui.html",
                        "/swagger-ui/**")
                    .permitAll();
              auth.anyRequest().denyAll();
            })
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(cache -> cache.requestCache(new NullRequestCache()))
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
    return http.build();
  }
}
