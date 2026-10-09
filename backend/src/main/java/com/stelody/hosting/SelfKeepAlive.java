package com.stelody.hosting;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Best-effort public HTTP traffic while this process is running; cannot wake a stopped process. */
public final class SelfKeepAlive implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(SelfKeepAlive.class);
  private final HttpClient client;
  private final HttpRequest request;

  public SelfKeepAlive(String renderUrl) {
    this(healthUri(renderUrl), newClient());
  }

  static HttpClient newClient() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  SelfKeepAlive(URI target, HttpClient client) {
    this.client = client;
    request =
        HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(10))
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "Stelody-self-keepalive/1.0")
            .GET()
            .build();
  }

  public static URI healthUri(String renderUrl) {
    try {
      var base = URI.create(renderUrl);
      if (!"https".equals(base.getScheme())
          || base.getHost() == null
          || !base.getHost().endsWith(".onrender.com")
          || base.getUserInfo() != null
          || base.getRawQuery() != null
          || base.getRawFragment() != null
          || (base.getPort() != -1 && base.getPort() != 443)
          || !(base.getPath().isEmpty() || base.getPath().equals("/"))) {
        throw new IllegalArgumentException();
      }
      return base.resolve("/actuator/health");
    } catch (RuntimeException error) {
      // Do not include raw configuration or its cause in startup errors.
      throw new IllegalArgumentException("SELF_KEEP_ALIVE_RENDER_URL_INVALID");
    }
  }

  @Scheduled(scheduler = "selfKeepAliveTaskScheduler", fixedDelay = 300000, initialDelay = 60000)
  public void tick() {
    try {
      var response = client.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() == 200) {
        LOG.info("selfKeepAlive status=SUCCEEDED httpStatus=200");
      } else {
        LOG.warn(
            "selfKeepAlive status=FAILED code=HTTP_STATUS httpStatus={}", response.statusCode());
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      LOG.warn("selfKeepAlive status=FAILED code=INTERRUPTED");
    } catch (IOException | RuntimeException error) {
      LOG.warn("selfKeepAlive status=FAILED code=REQUEST_FAILED");
    }
  }

  @Override
  public void close() {
    client.close();
  }
}
