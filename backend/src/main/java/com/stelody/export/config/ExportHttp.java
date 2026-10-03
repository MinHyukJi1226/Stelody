package com.stelody.export.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public final class ExportHttp {
  private ExportHttp() {}

  public static RestClient.Builder builder() {
    var client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(Duration.ofSeconds(5));
    return RestClient.builder().requestFactory(factory);
  }
}
