package com.stelody.admin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ArtistRegistrationMaintenance {
  private static final Logger log = LoggerFactory.getLogger(ArtistRegistrationMaintenance.class);
  private final ArtistRegistrationService service;

  public ArtistRegistrationMaintenance(ArtistRegistrationService service) {
    this.service = service;
  }

  @Scheduled(
      fixedDelayString = "${stelody.artist-registration.cleanup-delay-ms:60000}",
      initialDelayString = "${stelody.artist-registration.cleanup-delay-ms:60000}")
  public void tick() {
    try {
      service.maintain();
    } catch (RuntimeException unavailable) {
      log.warn("Artist registration result maintenance will retry");
    }
  }
}
