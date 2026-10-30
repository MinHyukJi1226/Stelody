package com.stelody.collector.push;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@Hidden
@RestController
@RequestMapping(YouTubePushSettings.PATH)
public class YouTubePushController {
  private final ObjectProvider<YouTubePushEndpoint> endpoint;

  public YouTubePushController(ObjectProvider<YouTubePushEndpoint> endpoint) {
    this.endpoint = endpoint;
  }

  @GetMapping
  public ResponseEntity<String> verify(
      @PathVariable String channel,
      @PathVariable String token,
      @RequestParam(name = "hub.mode", required = false) String mode,
      @RequestParam(name = "hub.topic", required = false) String topic,
      @RequestParam(name = "hub.challenge", required = false) String challenge,
      @RequestParam(name = "hub.lease_seconds", required = false) Long seconds) {
    try {
      var value = endpoint.getIfAvailable();
      if (value == null || !value.subscriptions().allowed(channel, token))
        return response(404, null);
      if ("denied".equals(mode)) {
        value.subscriptions().denied(channel, topic);
        return response(204, null);
      }
      return response(
          200, value.subscriptions().verify(channel, token, mode, topic, challenge, seconds));
    } catch (IllegalArgumentException e) {
      return response(404, null);
    } catch (RuntimeException e) {
      return response(503, null);
    }
  }

  @PostMapping
  public ResponseEntity<String> notify(
      @PathVariable String channel, @PathVariable String token, HttpServletRequest request) {
    try {
      var value = endpoint.getIfAvailable();
      if (value == null || !value.subscriptions().allowed(channel, token))
        return response(404, null);
      if (request.getContentLengthLong() > YouTubePushFeed.MAX_BYTES) return response(413, null);
      var body = request.getInputStream().readNBytes(YouTubePushFeed.MAX_BYTES + 1);
      if (body.length > YouTubePushFeed.MAX_BYTES) return response(413, null);
      var signatures = java.util.Collections.list(request.getHeaders("X-Hub-Signature"));
      if (signatures.size() != 1 || !value.signature(channel, body, signatures.getFirst()))
        return response(403, null);
      value.processor().receive(channel, YouTubePushFeed.parse(body, channel));
      return response(204, null);
    } catch (IllegalArgumentException e) {
      return response(400, null);
    } catch (RuntimeException | IOException e) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("youtube push delivery status=FAILED code=PROCESSING_UNAVAILABLE");
      return response(503, null);
    }
  }

  private ResponseEntity<String> response(int status, String body) {
    var result =
        ResponseEntity.status(status)
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.TEXT_PLAIN);
    if (status == 503) result.header("Retry-After", "60");
    return result.body(body);
  }
}
