package com.stelody.auth.service;

import com.stelody.auth.domain.SessionUser;
import com.stelody.auth.repository.PersistentLoginRepository;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.stereotype.Service;

@Service
public class PersistentLoginService {
  public static final String COOKIE = "STELODY_LOGIN";
  private final PersistentLoginRepository store;
  private final SecureRandom random = new SecureRandom();
  private final HttpSessionCsrfTokenRepository csrf = new HttpSessionCsrfTokenRepository();

  record Credential(UUID id, String secret) {}

  public PersistentLoginService(PersistentLoginRepository store) {
    this.store = store;
  }

  private String secret() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(token.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Optional<Credential> credential(HttpServletRequest request) {
    if (request.getCookies() == null) return Optional.empty();
    var matches =
        Arrays.stream(request.getCookies()).filter(c -> COOKIE.equals(c.getName())).toList();
    if (matches.size() != 1) return Optional.empty();
    String value = matches.getFirst().getValue();
    if (value == null
        || !value.matches(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[A-Za-z0-9_-]{43}"))
      return Optional.empty();
    return Optional.of(
        new Credential(UUID.fromString(value.substring(0, 36)), value.substring(37)));
  }

  public SessionUser issue(UUID user, HttpServletRequest request, HttpServletResponse response) {
    revoke(request, response);
    Instant now = Instant.now();
    UUID id = UUID.randomUUID();
    String token = secret();
    var csrfToken = csrf.generateToken(request);
    var grant = store.issue(user, id, hash(token), csrfToken.getToken(), now);
    if (grant.isEmpty()) return new SessionUser(user, now);
    csrf.saveToken(csrfToken, request, response);
    cookie(response, id + "." + token, grant.get().expiresAt());
    return new SessionUser(user, now, id, grant.get().expiresAt());
  }

  public boolean restore(
      HttpServletRequest request, HttpServletResponse response, UUID expectedUser) {
    var input = credential(request);
    if (input.isEmpty()) {
      if (hasCookie(request)) clear(response);
      return false;
    }
    var c = input.get();
    String next = secret();
    var grant = store.restore(c.id(), hash(c.secret()), hash(next), expectedUser);
    if (grant.isEmpty()) {
      clear(response);
      return false;
    }
    var g = grant.get();
    if (request.getSession(false) != null) request.changeSessionId();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new SessionUser(g.user(), Instant.now(), g.id(), g.expiresAt()),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    SecurityContextHolder.setContext(context);
    // Keep the device's synchronizer CSRF secret across short sessions. The client still has to
    // submit a valid header; this never exempts a mutation from normal CSRF verification.
    csrf.saveToken(new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", g.csrf()), request, response);
    new HttpSessionSecurityContextRepository().saveContext(context, request, response);
    if (g.rotated()) cookie(response, g.id() + "." + next, g.expiresAt());
    response.setHeader("Cache-Control", "no-store");
    return true;
  }

  public void reauthenticated(
      SessionUser user, HttpServletRequest request, HttpServletResponse response) {
    if (user.loginId() == null) return;
    var token = csrf.generateToken(request);
    store.updateCsrf(user.loginId(), user.id(), token.getToken());
    csrf.saveToken(token, request, response);
  }

  public boolean valid(SessionUser user) {
    return user.loginId() == null
        || (user.loginExpiresAt() != null
            && Instant.now().isBefore(user.loginExpiresAt())
            && store.valid(user));
  }

  public void revoke(HttpServletRequest request, HttpServletResponse response) {
    UUID loginId = null;
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof SessionUser u) loginId = u.loginId();
    var c = credential(request);
    store.revoke(
        loginId, c.map(Credential::id).orElse(null), c.map(x -> hash(x.secret())).orElse(null));
    clear(response);
  }

  public void revokeUser(UUID user, HttpServletResponse response) {
    store.revokeUser(user);
    clear(response);
  }

  private boolean hasCookie(HttpServletRequest r) {
    return r.getCookies() != null
        && Arrays.stream(r.getCookies()).anyMatch(c -> COOKIE.equals(c.getName()));
  }

  public void clear(HttpServletResponse response) {
    cookie(response, "", Instant.EPOCH);
  }

  private void cookie(HttpServletResponse response, String value, Instant expiry) {
    long seconds = Math.max(0, Duration.between(Instant.now(), expiry).getSeconds());
    response.addHeader(
        "Set-Cookie",
        ResponseCookie.from(COOKIE, value)
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path("/")
            .maxAge(seconds)
            .build()
            .toString());
  }
}
