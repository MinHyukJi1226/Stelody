package com.stelody.collector.youtube;

import com.stelody.collector.config.CollectorSettings;
import com.stelody.collector.domain.CollectionBudget;
import com.stelody.collector.domain.CollectionFailure;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Bounded requests with one retry policy for video and uploads discovery APIs. */
public final class YouTubeApiClient {
  private static final int MAX_BODY = 2 * 1024 * 1024;
  private final CollectorSettings settings;
  private final CollectionBudget.Sleeper sleeper;
  private final ObjectMapper mapper = new ObjectMapper();

  public YouTubeApiClient(CollectorSettings settings, CollectionBudget.Sleeper sleeper) {
    this.settings = settings;
    this.sleeper = sleeper;
  }

  public JsonNode get(String resource, Map<String, String> parameters, CollectionBudget budget) {
    for (int attempt = 1; ; attempt++) {
      try {
        return request(resource, parameters, budget);
      } catch (CollectionFailure failure) {
        if (!failure.retryable() || attempt >= settings.maxAttempts()) throw failure;
        budget.pause(settings.retryDelay().multipliedBy(1L << (attempt - 1)), sleeper);
      }
    }
  }

  private JsonNode request(
      String resource, Map<String, String> parameters, CollectionBudget budget) {
    Duration timeout =
        settings.readTimeout().compareTo(budget.remaining()) < 0
            ? settings.readTimeout()
            : budget.remaining();
    var http =
        HttpClient.newBuilder()
            .connectTimeout(
                settings.connectTimeout().compareTo(timeout) < 0
                    ? settings.connectTimeout()
                    : timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(timeout);
    try (http) {
      return RestClient.builder()
          .baseUrl(settings.apiBase().toString())
          .requestFactory(factory)
          .build()
          .get()
          .uri(
              builder -> {
                builder.path("/" + resource);
                parameters.forEach((name, value) -> builder.queryParam(name, "{" + name + "}"));
                return builder.build(parameters);
              })
          .header("X-Goog-Api-Key", settings.apiKey())
          .exchange(
              (request, response) -> {
                int status = response.getStatusCode().value();
                byte[] bytes = response.getBody().readNBytes(MAX_BODY + 1);
                budget.remaining();
                if (bytes.length > MAX_BODY) throw new CollectionFailure("INVALID_RESPONSE", false);
                if (status != 200) throw httpFailure(status, bytes);
                var root = mapper.readTree(bytes);
                if (!root.isObject() || root.has("error"))
                  throw new CollectionFailure("INVALID_RESPONSE", false);
                return root;
              });
    } catch (CollectionFailure failure) {
      throw failure;
    } catch (RestClientException exception) {
      throw new CollectionFailure("API_IO_ERROR", true);
    } catch (RuntimeException exception) {
      throw new CollectionFailure("INVALID_RESPONSE", false);
    }
  }

  private CollectionFailure httpFailure(int status, byte[] body) {
    try {
      for (var error : mapper.readTree(body).path("error").path("errors")) {
        String reason = error.path("reason").asText();
        if (reason.equals("quotaExceeded") || reason.equals("dailyLimitExceeded"))
          return new CollectionFailure("QUOTA_EXHAUSTED", false);
        if (reason.equals("rateLimitExceeded") || reason.equals("userRateLimitExceeded"))
          return new CollectionFailure("API_RATE_LIMIT", true);
        if (reason.equals("invalidPageToken"))
          return new CollectionFailure("INVALID_PAGE_TOKEN", false);
      }
    } catch (RuntimeException ignored) {
      /* Retain only normalized status. */
    }
    return new CollectionFailure("API_HTTP_" + status, status == 429 || status >= 500);
  }
}
