package com.stelody.auth.service;

import com.stelody.user.repository.WithdrawalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReauthenticationCleanup {
  private static final Logger log = LoggerFactory.getLogger(ReauthenticationCleanup.class);
  private final WithdrawalRepository store;

  public ReauthenticationCleanup(WithdrawalRepository store) {
    this.store = store;
  }

  @Scheduled(
      fixedDelayString = "${stelody.reauthentication.cleanup-delay-ms:60000}",
      initialDelayString = "${stelody.reauthentication.cleanup-delay-ms:60000}")
  public void tick() {
    try {
      store.expire();
    } catch (RuntimeException error) {
      log.warn("Reauthentication cleanup failed; the next invocation will retry");
    }
  }
}
