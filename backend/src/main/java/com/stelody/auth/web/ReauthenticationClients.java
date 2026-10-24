package com.stelody.auth.web;

import com.stelody.auth.domain.LoginReturn;
import com.stelody.auth.domain.ReauthenticationReturn;
import com.stelody.user.web.AccountException;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReauthenticationClients {
  private final String callback, localCallback, frontendOrigin;

  public ReauthenticationClients(
      @Value("${stelody.auth.reauthentication.redirect-uri:}") String callback,
      @Value("${stelody.auth.reauthentication.local-redirect-uri:}") String localCallback,
      @Value("${stelody.auth.reauthentication.frontend-origin:}") String frontendOrigin) {
    validate(callback, ReauthenticationReturn.CALLBACK, false);
    validate(localCallback, ReauthenticationReturn.LOCAL_CALLBACK, true);
    if (!frontendOrigin.isEmpty()) {
      URI u = URI.create(frontendOrigin);
      if (!valid(u, false)
          || !"".equals(u.getRawPath())
          || !u.toASCIIString().equals(frontendOrigin))
        throw new IllegalArgumentException("Invalid reauthentication frontend origin");
    }
    this.callback = callback;
    this.localCallback = localCallback;
    this.frontendOrigin = frontendOrigin;
  }

  private static boolean valid(URI u, boolean local) {
    boolean loopback = "localhost".equals(u.getHost()) || "127.0.0.1".equals(u.getHost());
    return u.getHost() != null
        && u.getUserInfo() == null
        && u.getRawQuery() == null
        && u.getRawFragment() == null
        && u.getPort() != 0
        && u.getPort() <= 65535
        && ("https".equals(u.getScheme()) || (loopback && "http".equals(u.getScheme())))
        && (!local || loopback);
  }

  private static void validate(String value, String path, boolean local) {
    if (value.isEmpty()) return;
    URI u = URI.create(value);
    if (!valid(u, local) || !path.equals(u.getRawPath()) || !u.toASCIIString().equals(value))
      throw new IllegalArgumentException("Invalid reauthentication callback URI");
  }

  public ReauthenticationReturn select(String path, String client) {
    if (client == null) client = "default";
    if (!client.equals("default") && !client.equals("local"))
      throw new AccountException(400, "INVALID_REAUTHENTICATION_CLIENT", "재인증 연결 설정이 올바르지 않습니다");
    if (path != null) {
      try {
        path = LoginReturn.validate(path);
      } catch (IllegalArgumentException e) {
        throw new AccountException(400, "INVALID_REAUTHENTICATION_RETURN", "재인증 복귀 경로가 올바르지 않습니다");
      }
    }
    // Existing clients keep their registered Google callback and confirmation JSON.
    if (path == null && client.equals("default")) return null;
    String uri = uri(client);
    if (uri.isEmpty())
      throw new AccountException(
          client.equals("local") ? 400 : 503,
          client.equals("local")
              ? "INVALID_REAUTHENTICATION_CLIENT"
              : "GOOGLE_REAUTHENTICATION_UNAVAILABLE",
          "재인증 콜백 설정이 필요합니다");
    return new ReauthenticationReturn(path, client, uri);
  }

  public String uri(String client) {
    return client.equals("local") ? localCallback : callback;
  }

  public String origin(String client) {
    if (!client.equals("local")) return frontendOrigin;
    URI u = URI.create(localCallback);
    return u.getScheme() + "://" + u.getRawAuthority();
  }
}
