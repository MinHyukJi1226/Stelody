package com.stelody.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
  @Bean
  UserDetailsService userDetailsService() {
    // Replace with verified-account lookup when the authentication feature is implemented.
    // Avoid Boot's generated development user and password in every profile.
    return username -> {
      throw new UsernameNotFoundException("Account authentication is not configured");
    };
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    // Open additional routes explicitly as each feature is implemented.
    return http.authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(HttpMethod.GET, "/actuator/health")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, exception) -> response.setStatus(401))
                    .accessDeniedHandler((request, response, exception) -> response.setStatus(403)))
        .build();
  }
}
