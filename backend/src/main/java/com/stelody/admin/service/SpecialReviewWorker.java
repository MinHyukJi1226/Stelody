package com.stelody.admin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SpecialReviewWorker {
  private static final Logger log = LoggerFactory.getLogger(SpecialReviewWorker.class);
  private final SpecialReviewService service;
  private final boolean enabled;

  public SpecialReviewWorker(
      SpecialReviewService service,
      @Value("${stelody.special-review.enabled:false}") boolean enabled) {
    this.service = service;
    this.enabled = enabled;
  }

  @Scheduled(
      fixedDelayString = "${stelody.special-review.scan-delay-ms:60000}",
      initialDelayString = "${stelody.special-review.scan-delay-ms:60000}")
  public void tick() {
    try {
      // Expiration runs even when automatic candidate creation is disabled.
      service.expire();
      if (enabled) service.scan();
    } catch (RuntimeException error) {
      log.warn("Special review scan failed; the next invocation will retry");
    }
  }
}
