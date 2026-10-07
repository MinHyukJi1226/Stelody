package com.stelody.user.service.ledger;

import com.stelody.user.web.AccountException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import tools.jackson.databind.ObjectMapper;

public final class B2WithdrawalJournal implements WithdrawalJournal {
  public static final String PREFIX = "stelody/withdrawals/records/";
  public static final String COVERAGE = "stelody/withdrawals/coverage.json";
  private final S3Client client;
  private final ObjectMapper mapper;
  private final String bucket;
  private final UUID ledgerId;
  private final AgeEncryption encryption;
  private final Clock clock;

  public B2WithdrawalJournal(
      S3Client client,
      ObjectMapper mapper,
      String bucket,
      UUID ledgerId,
      AgeEncryption encryption,
      Clock clock) {
    this.client = client;
    this.mapper = mapper;
    this.bucket = bucket;
    this.ledgerId = ledgerId;
    this.encryption = encryption;
    this.clock = clock;
  }

  @Override
  public void record(UUID user) {
    try {
      privateStorage();
      Instant requested = clock.instant();
      try (var response =
          client.getObject(GetObjectRequest.builder().bucket(bucket).key(COVERAGE).build())) {
        byte[] body = response.readNBytes(4097);
        if (body.length > 4096) throw new IllegalStateException();
        var coverage = mapper.readTree(body);
        if (coverage.path("format").asInt() != 1
            || !ledgerId.toString().equals(coverage.path("ledgerId").asText())
            || Instant.parse(coverage.path("recordingSince").asText()).isAfter(requested))
          throw new IllegalStateException();
      }
      UUID record = UUID.randomUUID();
      byte[] plain =
          mapper.writeValueAsBytes(
              Map.of(
                  "format",
                  1,
                  "ledgerId",
                  ledgerId.toString(),
                  "recordId",
                  record.toString(),
                  "userId",
                  user.toString(),
                  "requestedAt",
                  requested.toString()));
      byte[] cipher = encryption.encrypt(plain);
      String key = PREFIX + record + ".age";
      var metadata =
          Map.of(
              "stelody-withdrawal-format",
              "1",
              "expires-at",
              requested.plus(Duration.ofDays(8)).toString());
      var uploaded =
          client.putObject(
              PutObjectRequest.builder()
                  .bucket(bucket)
                  .key(key)
                  .contentType("application/octet-stream")
                  .metadata(metadata)
                  .build(),
              RequestBody.fromBytes(cipher));
      if (uploaded.versionId() == null
          || uploaded.versionId().isBlank()
          || uploaded.versionId().equals("null")) throw new IllegalStateException();
      try (var response =
          client.getObject(
              GetObjectRequest.builder()
                  .bucket(bucket)
                  .key(key)
                  .versionId(uploaded.versionId())
                  .build())) {
        if (!metadata.equals(response.response().metadata())
            || !MessageDigest.isEqual(cipher, response.readNBytes(8193)))
          throw new IllegalStateException();
      }
    } catch (Exception e) {
      // No provider errors, UUIDs, secrets or encryption input in public responses/logs.
      throw new AccountException(
          503, "WITHDRAWAL_JOURNAL_UNAVAILABLE", "탈퇴 처리를 완료하지 못했습니다. 잠시 후 다시 시도해 주세요");
    }
  }

  private void privateStorage() {
    var acl = client.getBucketAcl(GetBucketAclRequest.builder().bucket(bucket).build());
    if (acl.grants().stream().anyMatch(g -> g.grantee().uri() != null))
      throw new IllegalStateException();
    var rules =
        client
            .getBucketLifecycleConfiguration(
                GetBucketLifecycleConfigurationRequest.builder().bucket(bucket).build())
            .rules();
    boolean hides = false, deletes = false;
    for (var rule : rules) {
      if (rule.status() != ExpirationStatus.ENABLED) continue;
      String prefix = rule.filter() == null ? rule.prefix() : rule.filter().prefix();
      // Refuse an unknown/broader deletion rule that could erase still-needed records.
      if (prefix == null) throw new IllegalStateException();
      if (!PREFIX.startsWith(prefix) && !prefix.startsWith(PREFIX)) continue;
      if (!PREFIX.equals(prefix)) throw new IllegalStateException();
      // B2 may return hide/delete/orphan-marker cleanup as separate S3 rules.
      if (rule.expiration() != null) {
        if (rule.expiration().days() != null) {
          if (rule.expiration().days() != 8) throw new IllegalStateException();
          hides = true;
        } else if (!Boolean.TRUE.equals(rule.expiration().expiredObjectDeleteMarker())) {
          throw new IllegalStateException();
        }
      }
      if (rule.noncurrentVersionExpiration() != null) {
        if (!Integer.valueOf(1).equals(rule.noncurrentVersionExpiration().noncurrentDays()))
          throw new IllegalStateException();
        deletes = true;
      }
    }
    if (!hides || !deletes) throw new IllegalStateException();
  }
}
