package com.stelody.user.service.ledger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.stelody.user.web.AccountException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import tools.jackson.databind.ObjectMapper;

class B2WithdrawalJournalTest {
  final S3Client client = mock(S3Client.class);
  final AgeEncryption encryption = mock(AgeEncryption.class);
  final ObjectMapper mapper = new ObjectMapper();
  final UUID user = new UUID(0, 1), ledger = new UUID(0, 2);
  final Instant time = Instant.parse("2026-10-07T12:00:00Z");
  final byte[] cipher = "encrypted withdrawal".getBytes(StandardCharsets.UTF_8);
  final B2WithdrawalJournal journal =
      new B2WithdrawalJournal(
          client,
          mapper,
          "stelody-withdrawals",
          ledger,
          encryption,
          Clock.fixed(time, ZoneOffset.UTC));
  Map<String, String> metadata;

  ResponseInputStream<GetObjectResponse> response(byte[] body, Map<String, String> meta) {
    return new ResponseInputStream<>(
        GetObjectResponse.builder().metadata(meta).build(),
        AbortableInputStream.create(new ByteArrayInputStream(body)));
  }

  BucketLifecycleConfiguration rule(String prefix, int days) {
    return BucketLifecycleConfiguration.builder()
        .rules(
            LifecycleRule.builder()
                .id("ledger")
                .status(ExpirationStatus.ENABLED)
                .filter(LifecycleRuleFilter.builder().prefix(prefix).build())
                .expiration(LifecycleExpiration.builder().days(days).build())
                .noncurrentVersionExpiration(
                    NoncurrentVersionExpiration.builder().noncurrentDays(1).build())
                .build())
        .build();
  }

  @BeforeEach
  void setup() {
    when(client.getBucketAcl(any(GetBucketAclRequest.class)))
        .thenReturn(GetBucketAclResponse.builder().build());
    when(client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
        .thenReturn(
            GetBucketLifecycleConfigurationResponse.builder()
                .rules(rule(B2WithdrawalJournal.PREFIX, 8).rules())
                .build());
    when(encryption.encrypt(any())).thenReturn(cipher);
    when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenAnswer(
            invocation -> {
              metadata = invocation.<PutObjectRequest>getArgument(0).metadata();
              return PutObjectResponse.builder().versionId("immutable-version").build();
            });
    when(client.getObject(any(GetObjectRequest.class)))
        .thenAnswer(
            invocation -> {
              GetObjectRequest request = invocation.getArgument(0);
              if (request.key().equals(B2WithdrawalJournal.COVERAGE))
                return response(
                    mapper.writeValueAsBytes(
                        Map.of(
                            "format",
                            1,
                            "ledgerId",
                            ledger.toString(),
                            "recordingSince",
                            time.minusSeconds(60).toString())),
                    Map.of());
              assertThat(request.versionId()).isEqualTo("immutable-version");
              return response(cipher, metadata);
            });
  }

  @Test
  void recordsOnlyUuidAndTimeEncryptedAndVerifiesTheExactUploadedVersion() throws Exception {
    journal.record(user);
    var input = ArgumentCaptor.forClass(byte[].class);
    verify(encryption).encrypt(input.capture());
    var plain = mapper.readTree(input.getValue());
    assertThat(plain.properties()).hasSize(5);
    assertThat(plain.path("userId").asText()).isEqualTo(user.toString());
    assertThat(plain.path("ledgerId").asText()).isEqualTo(ledger.toString());
    var request = ArgumentCaptor.forClass(PutObjectRequest.class);
    var body = ArgumentCaptor.forClass(RequestBody.class);
    verify(client).putObject(request.capture(), body.capture());
    assertThat(request.getValue().key())
        .isEqualTo(B2WithdrawalJournal.PREFIX + plain.path("recordId").asText() + ".age")
        .doesNotContain(user.toString());
    assertThat(request.getValue().metadata())
        .containsEntry("expires-at", time.plus(Duration.ofDays(8)).toString());
    assertThat(body.getValue().contentStreamProvider().newStream().readAllBytes())
        .containsExactly(cipher);
    verify(client, times(2)).getObject(any(GetObjectRequest.class));
  }

  @Test
  void acceptsSeparateB2HideDeleteAndOrphanMarkerRulesButRequiresBothRetentionStages() {
    var hide =
        LifecycleRule.builder()
            .status(ExpirationStatus.ENABLED)
            .filter(LifecycleRuleFilter.builder().prefix(B2WithdrawalJournal.PREFIX).build())
            .expiration(LifecycleExpiration.builder().days(8).build())
            .build();
    var orphan =
        hide.toBuilder()
            .expiration(LifecycleExpiration.builder().expiredObjectDeleteMarker(true).build())
            .build();
    var delete =
        hide.toBuilder()
            .expiration((LifecycleExpiration) null)
            .noncurrentVersionExpiration(
                NoncurrentVersionExpiration.builder().noncurrentDays(1).build())
            .build();
    when(client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
        .thenReturn(
            GetBucketLifecycleConfigurationResponse.builder().rules(hide, orphan, delete).build());
    journal.record(user);
    when(client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
        .thenReturn(GetBucketLifecycleConfigurationResponse.builder().rules(hide, orphan).build());
    assertUnavailable();
    verify(client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void publicBucketNeverReceivesAnUpload() {
    when(client.getBucketAcl(any(GetBucketAclRequest.class)))
        .thenReturn(
            GetBucketAclResponse.builder()
                .grants(
                    Grant.builder()
                        .grantee(
                            Grantee.builder()
                                .uri("http://acs.amazonaws.com/groups/global/AllUsers")
                                .build())
                        .build())
                .build());
    assertUnavailable();
    verify(client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    verifyNoInteractions(encryption);
  }

  @Test
  void shortOrBroaderLifecycleNeverReceivesAnUpload() {
    for (var rule :
        List.of(
            rule(B2WithdrawalJournal.PREFIX, 1),
            rule("", 8),
            rule(B2WithdrawalJournal.PREFIX + "0", 8))) {
      when(client.getBucketLifecycleConfiguration(
              any(GetBucketLifecycleConfigurationRequest.class)))
          .thenReturn(
              GetBucketLifecycleConfigurationResponse.builder().rules(rule.rules()).build());
      assertUnavailable();
    }
    verify(client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void missingOrWrongCoverageNeverReceivesAnUpload() {
    when(client.getObject(any(GetObjectRequest.class)))
        .thenReturn(response("{}".getBytes(), Map.of()));
    assertUnavailable();
    verify(client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void corruptReadBackOrMissingVersionCannotSucceed() {
    when(client.getObject(any(GetObjectRequest.class)))
        .thenAnswer(
            invocation -> {
              GetObjectRequest request = invocation.getArgument(0);
              return request.key().equals(B2WithdrawalJournal.COVERAGE)
                  ? response(
                      mapper.writeValueAsBytes(
                          Map.of(
                              "format",
                              1,
                              "ledgerId",
                              ledger.toString(),
                              "recordingSince",
                              time.toString())),
                      Map.of())
                  : response("tampered".getBytes(), metadata);
            });
    assertUnavailable();
    when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().build());
    assertUnavailable();
  }

  @Test
  void providerErrorsAreSanitized() {
    when(client.getBucketAcl(any(GetBucketAclRequest.class)))
        .thenThrow(
            S3Exception.builder()
                .message("private-secret-provider-detail")
                .statusCode(503)
                .build());
    assertThatThrownBy(() -> journal.record(user))
        .isInstanceOf(AccountException.class)
        .hasNoCause()
        .hasMessage("탈퇴 처리를 완료하지 못했습니다. 잠시 후 다시 시도해 주세요");
  }

  void assertUnavailable() {
    assertThatThrownBy(() -> journal.record(user))
        .isInstanceOfSatisfying(
            AccountException.class,
            e -> {
              assertThat(e.status()).isEqualTo(503);
              assertThat(e.code()).isEqualTo("WITHDRAWAL_JOURNAL_UNAVAILABLE");
            });
  }
}
