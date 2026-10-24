package com.stelody.user.service;

import com.stelody.export.service.YouTubeConnectionService;
import com.stelody.user.repository.WithdrawalResultRepository;
import com.stelody.user.web.AccountException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WithdrawalResults {
  public static final String TOKEN_HEADER = "X-Withdrawal-Token";
  public static final String REQUEST_HEADER = "Withdrawal-Request-Id";
  private final WithdrawalResultRepository store;
  private final TransactionTemplate independent;
  private final SecureRandom random = new SecureRandom();

  public WithdrawalResults(WithdrawalResultRepository store, PlatformTransactionManager manager) {
    this.store = store;
    independent = new TransactionTemplate(manager);
    independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    independent.setTimeout(10);
  }

  public enum Status {
    COMPLETED,
    PROCESSING,
    NOT_COMPLETED,
    UNKNOWN
  }

  @Schema(requiredProperties = {"id", "queryToken", "expiresAt"})
  public record Prepared(
      UUID id,
      @Schema(
              description = "조회 전용 비밀 토큰. URL·로그에 포함하지 않고 같은 탭 sessionStorage에만 보관",
              minLength = 43,
              maxLength = 43,
              pattern = "[A-Za-z0-9_-]{43}")
          String queryToken,
      Instant expiresAt) {}

  @Schema(
      requiredProperties = {"id", "status", "expiresAt", "completedAt", "code", "pollAfterSeconds"})
  public record Result(
      UUID id,
      Status status,
      Instant expiresAt,
      @Schema(nullable = true) Instant completedAt,
      @Schema(nullable = true) String code,
      @Schema(nullable = true) Integer pollAfterSeconds) {}

  public Prepared prepare(UUID user) {
    UUID id = UUID.randomUUID();
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    return new Prepared(id, token, independent.execute(tx -> store.create(id, user, hash(token))));
  }

  public Result get(String rawId, String token) {
    UUID id = parseId(rawId);
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
      throw new AccountException(400, "INVALID_WITHDRAWAL_TOKEN", "조회 토큰 형식을 확인해 주세요");
    var receipt = store.find(id).orElseThrow(WithdrawalResults::notFound);
    if (!MessageDigest.isEqual(
        hash(token).getBytes(StandardCharsets.US_ASCII),
        receipt.tokenHash().getBytes(StandardCharsets.US_ASCII))) throw notFound();
    if (receipt.expired())
      throw new AccountException(410, "WITHDRAWAL_RESULT_EXPIRED", "결과 조회 기간이 만료되었습니다");
    Status status =
        switch (receipt.status()) {
          case "COMPLETED" -> Status.COMPLETED;
          case "NOT_COMPLETED" -> Status.NOT_COMPLETED;
          case "PREPARED" -> Status.UNKNOWN;
          case "PROCESSING" -> receipt.stalled() ? Status.UNKNOWN : Status.PROCESSING;
          default -> Status.UNKNOWN;
        };
    String code =
        receipt.status().equals("PREPARED")
            ? "WITHDRAWAL_NOT_SUBMITTED"
            : status == Status.UNKNOWN ? "WITHDRAWAL_OUTCOME_UNKNOWN" : receipt.errorCode();
    return new Result(
        id,
        status,
        receipt.expiresAt(),
        receipt.completedAt(),
        code,
        status == Status.PROCESSING ? 3 : null);
  }

  public UUID claim(UUID user, String rawId) {
    if (rawId == null) return null; // Legacy clients retain their existing DELETE contract.
    UUID id = parseId(rawId);
    independent.executeWithoutResult(
        tx -> {
          if (!store.claim(id, user))
            throw new AccountException(
                409,
                "WITHDRAWAL_REQUEST_UNAVAILABLE",
                "탈퇴 요청이 없거나 만료되었거나 이미 사용되었습니다. 기존 결과를 확인해 주세요");
        });
    return id;
  }

  public void completed(UUID id) {
    if (id != null) store.completed(id);
  }

  public void failed(UUID id, RuntimeException error) {
    if (id == null) return;
    try {
      independent.executeWithoutResult(
          tx ->
              store.failed(
                  id,
                  error instanceof AccountException ? "NOT_COMPLETED" : "UNKNOWN",
                  error instanceof AccountException e ? e.code() : "WITHDRAWAL_OUTCOME_UNKNOWN"));
    } catch (RuntimeException unavailable) {
      // Preserve PROCESSING. A stalled attempt becomes UNKNOWN on read, never success.
      error.addSuppressed(unavailable);
    }
  }

  public int cleanup() {
    return store.cleanup();
  }

  private static String hash(String token) {
    return YouTubeConnectionService.hash(token);
  }

  private static UUID parseId(String raw) {
    try {
      UUID id = UUID.fromString(raw);
      if (!id.toString().equals(raw)) throw new IllegalArgumentException();
      return id;
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new AccountException(400, "INVALID_WITHDRAWAL_REQUEST_ID", "탈퇴 요청 ID 형식을 확인해 주세요");
    }
  }

  private static AccountException notFound() {
    return new AccountException(404, "WITHDRAWAL_RESULT_NOT_FOUND", "해당 조회 권한으로 결과를 확인할 수 없습니다");
  }
}
