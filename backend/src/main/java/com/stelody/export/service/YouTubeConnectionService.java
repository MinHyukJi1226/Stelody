package com.stelody.export.service;

import com.stelody.export.config.*;
import com.stelody.export.dto.ExportDtos;
import com.stelody.export.repository.ExportRepository;
import com.stelody.export.web.ExportException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.*;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.*;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class YouTubeConnectionService {
  private final tools.jackson.databind.ObjectMapper mapper;
  private final ExportSettings settings;
  private final ExportRepository store;
  private final TokenCipher cipher;
  private final ObjectProvider<ClientRegistrationRepository> clients;
  private final RestClientAuthorizationCodeTokenResponseClient codes =
      new RestClientAuthorizationCodeTokenResponseClient();
  private final RestClientRefreshTokenTokenResponseClient refreshes =
      new RestClientRefreshTokenTokenResponseClient();
  private final OidcIdTokenDecoderFactory decoders = new OidcIdTokenDecoderFactory();
  private final SecureRandom random = new SecureRandom();

  public YouTubeConnectionService(
      ExportSettings settings,
      ExportRepository store,
      TokenCipher cipher,
      ObjectProvider<ClientRegistrationRepository> clients,
      tools.jackson.databind.ObjectMapper mapper) {
    this.mapper = mapper;
    this.settings = settings;
    this.store = store;
    this.cipher = cipher;
    this.clients = clients;
    var http =
        ExportHttp.builder()
            .configureMessageConverters(
                c ->
                    c.disableDefaults()
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
            .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
            .build();
    codes.setRestClient(http);
    refreshes.setRestClient(http);
  }

  public void enabled() {
    if (!settings.enabled()) throw new ExportException(503, "YOUTUBE_EXPORT_DISABLED");
  }

  private ClientRegistration registration() {
    enabled();
    var repository = clients.getIfAvailable();
    var base = repository == null ? null : repository.findByRegistrationId("google");
    if (base == null) throw new ExportException(503, "YOUTUBE_OAUTH_UNAVAILABLE");
    return ClientRegistration.withClientRegistration(base)
        .registrationId("youtube-export")
        .redirectUri(settings.redirectUri())
        .scope("openid", "email", ExportSettings.SCOPE)
        .build();
  }

  public ExportDtos.Connection status(UUID user) {
    return new ExportDtos.Connection(
        store
            .connection(user)
            .map(ExportRepository.Connection::status)
            .orElse(settings.enabled() ? "DISCONNECTED" : "DISABLED"));
  }

  public ExportDtos.Authorization authorize(UUID user, String session) {
    var registration = registration();
    String state = nonce(), verifier = nonce(), nonce = nonce();
    Instant expires = Instant.now().plusSeconds(300);
    String subject =
        store.tx(
            () -> {
              String sub = store.lockActive(user);
              if (store.connection(user).filter(c -> c.status().equals("REVOKING")).isPresent())
                throw new ExportException(409, "YOUTUBE_REVOCATION_PENDING");
              store.authorization(
                  user,
                  UUID.randomUUID(),
                  hash(state),
                  hash(session),
                  cipher.encrypt(user, "verifier", verifier),
                  nonce,
                  expires);
              return sub;
            });
    String url =
        UriComponentsBuilder.fromUriString(registration.getProviderDetails().getAuthorizationUri())
            .queryParam("response_type", "code")
            .queryParam("client_id", registration.getClientId())
            .queryParam("redirect_uri", settings.redirectUri())
            .queryParam("scope", String.join(" ", registration.getScopes()))
            .queryParam("state", state)
            .queryParam("nonce", nonce)
            .queryParam("code_challenge", challenge(verifier))
            .queryParam("code_challenge_method", "S256")
            .queryParam("access_type", "offline")
            .queryParam("include_granted_scopes", "true")
            .queryParam("prompt", "consent")
            .queryParam("login_hint", subject)
            .build()
            .encode()
            .toUriString();
    return new ExportDtos.Authorization(url, expires);
  }

  public ExportDtos.Connection callback(
      UUID user, String session, String state, String code, String error) {
    var registration = registration();
    if (state == null || state.length() > 128 || code != null && code.length() > 2048)
      throw ExportException.invalid();
    var authorization = store.tx(() -> store.consume(user, hash(state), hash(session)));
    if (error != null) throw new ExportException(400, "YOUTUBE_CONSENT_DENIED");
    if (code == null || code.isBlank()) throw ExportException.invalid();
    try {
      var request =
          OAuth2AuthorizationRequest.authorizationCode()
              .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
              .clientId(registration.getClientId())
              .redirectUri(settings.redirectUri())
              .scopes(registration.getScopes())
              .state(state)
              .attributes(
                  a ->
                      a.put(
                          PkceParameterNames.CODE_VERIFIER,
                          cipher.decrypt(user, "verifier", authorization.verifier())))
              .build();
      var response =
          OAuth2AuthorizationResponse.success(code)
              .state(state)
              .redirectUri(settings.redirectUri())
              .build();
      var tokens =
          codes.getTokenResponse(
              new OAuth2AuthorizationCodeGrantRequest(
                  registration, new OAuth2AuthorizationExchange(request, response)));
      if (!tokens.getAccessToken().getScopes().contains(ExportSettings.SCOPE))
        throw new ExportException(400, "YOUTUBE_SCOPE_REQUIRED");
      Object raw = tokens.getAdditionalParameters().get("id_token");
      if (!(raw instanceof String idToken))
        throw new ExportException(400, "INVALID_YOUTUBE_IDENTITY");
      var identity = decoders.createDecoder(registration).decode(idToken);
      if (!constant(authorization.nonce(), identity.getClaimAsString("nonce")))
        throw new ExportException(400, "INVALID_YOUTUBE_IDENTITY");
      if (tokens.getRefreshToken() == null)
        throw new ExportException(400, "YOUTUBE_OFFLINE_ACCESS_REQUIRED");
      store.tx(
          () -> {
            if (!store.lockActive(user).equals(identity.getSubject()))
              throw new ExportException(400, "YOUTUBE_ACCOUNT_MISMATCH");
            if (!store.connect(
                user,
                authorization.generation(),
                cipher.encrypt(user, "access", tokens.getAccessToken().getTokenValue()),
                cipher.encrypt(user, "refresh", tokens.getRefreshToken().getTokenValue()),
                tokens.getAccessToken().getExpiresAt()))
              throw new ExportException(409, "YOUTUBE_AUTHORIZATION_CHANGED");
            return null;
          });
      return status(user);
    } catch (ExportException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ExportException(502, "YOUTUBE_AUTHORIZATION_FAILED");
    }
  }

  private record TokenResult(String token, String error) {}

  public String access(UUID user) {
    var registration = registration();
    var result =
        store.tx(
            () -> {
              store.lockActive(user);
              var connection =
                  store
                      .connection(user)
                      .filter(c -> c.status().equals("CONNECTED"))
                      .orElseThrow(() -> new ExportException(409, "YOUTUBE_CONNECTION_REQUIRED"));
              String token = cipher.decrypt(user, "access", connection.access());
              if (connection.expiresAt().isAfter(Instant.now().plusSeconds(60)))
                return new TokenResult(token, null);
              try {
                var oldAccess =
                    new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        token,
                        Instant.EPOCH,
                        connection.expiresAt(),
                        Set.of(ExportSettings.SCOPE));
                var oldRefresh =
                    new OAuth2RefreshToken(
                        cipher.decrypt(user, "refresh", connection.refresh()), Instant.EPOCH);
                var refreshed =
                    refreshes.getTokenResponse(
                        new OAuth2RefreshTokenGrantRequest(registration, oldAccess, oldRefresh));
                if (!refreshed.getAccessToken().getScopes().contains(ExportSettings.SCOPE)) {
                  store.invalidate(user);
                  return new TokenResult(null, "YOUTUBE_RECONNECT_REQUIRED");
                }
                String refresh =
                    refreshed.getRefreshToken() == null
                        ? connection.refresh()
                        : cipher.encrypt(
                            user, "refresh", refreshed.getRefreshToken().getTokenValue());
                store.connect(
                    user,
                    connection.generation(),
                    cipher.encrypt(user, "access", refreshed.getAccessToken().getTokenValue()),
                    refresh,
                    refreshed.getAccessToken().getExpiresAt());
                return new TokenResult(refreshed.getAccessToken().getTokenValue(), null);
              } catch (OAuth2AuthorizationException e) {
                if ("invalid_grant".equals(e.getError().getErrorCode())) {
                  store.invalidate(user);
                  return new TokenResult(null, "YOUTUBE_RECONNECT_REQUIRED");
                }
                return new TokenResult(null, "YOUTUBE_TOKEN_UNAVAILABLE");
              } catch (RuntimeException e) {
                return new TokenResult(null, "YOUTUBE_TOKEN_UNAVAILABLE");
              }
            });
    if (result.error() != null) throw new ExportException(409, result.error());
    return result.token();
  }

  public ExportDtos.Connection disconnect(UUID user) {
    var value = store.tx(() -> store.disconnect(user));
    if (value != null && value.status().equals("REVOKING")) revoke(value);
    return status(user);
  }

  public void revoke(ExportRepository.Connection value) {
    try {
      if (!store.tx(() -> store.revocationAttempt(value))) return;
      var form = new LinkedMultiValueMap<String, String>();
      form.add("token", cipher.decrypt(value.userId(), "refresh", value.refresh()));
      try {
        ExportHttp.builder()
            .build()
            .post()
            .uri(settings.revokeUri())
            .body(form)
            .retrieve()
            .toBodilessEntity();
      } catch (RestClientResponseException e) {
        if (e.getStatusCode().value() != 400
            || !"invalid_token"
                .equals(mapper.readTree(e.getResponseBodyAsString()).path("error").asText()))
          return;
      }
      store.tx(
          () -> {
            store.revoked(value);
            return null;
          });
    } catch (RuntimeException e) {
      /* Keep REVOKING; the worker retries without enabling writes. */
    }
  }

  private String nonce() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException();
    }
  }

  private static String challenge(String text) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(HexFormat.of().parseHex(hash(text)));
  }

  private static boolean constant(String expected, String actual) {
    return actual != null
        && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }
}
