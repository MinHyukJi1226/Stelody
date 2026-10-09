package com.stelody.hosting;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stelody.collector.config.CollectorSchedulingConfiguration;
import com.stelody.export.config.ExportScheduling;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SelfKeepAliveTest {
  @Test
  void realHttpTransportSendsPublicRequestAndDoesNotFollowRedirects() {
    var server = new WireMockServer(0);
    server.start();
    try (var client = SelfKeepAlive.newClient()) {
      assertThat(client.connectTimeout()).contains(Duration.ofSeconds(5));
      assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
      var task = new SelfKeepAlive(URI.create(server.baseUrl() + "/actuator/health"), client);
      server.stubFor(get(urlEqualTo("/actuator/health")).willReturn(okJson("{\"status\":\"UP\"}")));
      task.tick();
      server.verify(
          1,
          getRequestedFor(urlEqualTo("/actuator/health"))
              .withoutHeader("Authorization")
              .withoutHeader("Cookie"));
      server.stubFor(get(urlEqualTo("/actuator/health")).willReturn(temporaryRedirect("/other")));
      task.tick();
      server.verify(0, getRequestedFor(urlEqualTo("/other")));
      server.verify(2, getRequestedFor(urlEqualTo("/actuator/health")));
    } finally {
      server.stop();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void publicRequestContainsNoCredentialsAndRecoversAfterFailure() throws Exception {
    var client = mock(HttpClient.class);
    var response = (HttpResponse<Void>) mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(503, 200);
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new IOException("private URL / response must not be logged"))
        .thenReturn(response);
    var target = SelfKeepAlive.healthUri("https://stelody-backend.onrender.com/");
    var task = new SelfKeepAlive(target, client);
    task.tick();
    task.tick();
    task.tick();
    var requests = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client, times(3)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
    var request = requests.getValue();
    assertThat(request.uri())
        .isEqualTo(URI.create("https://stelody-backend.onrender.com/actuator/health"));
    assertThat(request.method()).isEqualTo("GET");
    assertThat(request.timeout()).contains(Duration.ofSeconds(10));
    assertThat(request.headers().firstValue("Authorization")).isEmpty();
    assertThat(request.headers().firstValue("Cookie")).isEmpty();
    assertThat(request.bodyPublisher()).isEmpty();
    task.close();
    verify(client).close();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "http://stelody-backend.onrender.com",
        "https://localhost",
        "https://stelody-backend.onrender.com.example.com",
        "https://u:secret@stelody-backend.onrender.com",
        "https://stelody-backend.onrender.com?token=secret",
        "https://stelody-backend.onrender.com#secret",
        "https://stelody-backend.onrender.com:8080",
        "https://stelody-backend.onrender.com/api",
        "not a URL"
      })
  void rejectsUnsafeTargetsWithoutExposingConfiguration(String url) {
    assertThatThrownBy(() -> SelfKeepAlive.healthUri(url))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("SELF_KEEP_ALIVE_RENDER_URL_INVALID")
        .hasNoCause();
  }

  @Test
  void disabledOutsideRenderAndInCollectorProfile() {
    var runner =
        new ApplicationContextRunner().withUserConfiguration(SelfKeepAliveConfiguration.class);
    runner
        .withPropertyValues("spring.profiles.active=render")
        .run(context -> assertThat(context).doesNotHaveBean(SelfKeepAlive.class));
    runner
        .withPropertyValues("stelody.hosting.self-keep-alive-enabled=true")
        .run(context -> assertThat(context).doesNotHaveBean(SelfKeepAlive.class));
    runner
        .withPropertyValues(
            "spring.profiles.active=render,collector",
            "stelody.hosting.self-keep-alive-enabled=true")
        .run(context -> assertThat(context).doesNotHaveBean(SelfKeepAlive.class));
  }

  @Test
  void hasOwnSchedulerAlongsideCollectionAndDefaultWebTasks() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
        .withUserConfiguration(
            SelfKeepAliveConfiguration.class,
            CollectorSchedulingConfiguration.class,
            ExportScheduling.class)
        .withPropertyValues(
            "spring.profiles.active=render",
            "stelody.hosting.self-keep-alive-enabled=true",
            "RENDER_EXTERNAL_URL=https://stelody-backend.onrender.com",
            "stelody.collector.enabled=true",
            "stelody.collector.scheduled-enabled=true",
            "stelody.collector.db-url=jdbc:postgresql://localhost:1/test",
            "stelody.collector.db-username=collector",
            "stelody.collector.db-password=test",
            "stelody.collector.api-key=test")
        .run(
            context -> {
              assertThat(context).hasSingleBean(SelfKeepAlive.class);
              assertThat(context.getBean("selfKeepAliveTaskScheduler"))
                  .isNotSameAs(context.getBean("collectorTaskScheduler"));
              assertThat(context.getBean("taskScheduler"))
                  .isNotSameAs(context.getBean("selfKeepAliveTaskScheduler"));
            });
  }
}
