package com.stelody.user.service.ledger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

class WithdrawalJournalConfigurationTest {
  final WithdrawalJournalConfiguration configuration = new WithdrawalJournalConfiguration();

  @Test
  void disabledJournalRequiresNoCredentialsOrNetwork() {
    assertThatCode(() -> configuration.disabledWithdrawalJournal().record(UUID.randomUUID()))
        .doesNotThrowAnyException();
  }

  @Test
  void invalidEndpointsAndMissingKeysAreRejectedBeforeClientCreation() {
    for (String endpoint :
        new String[] {
          "http://s3.us-west-004.backblazeb2.com",
          "https://untrusted.example",
          "https://s3.us-west-004.backblazeb2.com/path"
        }) {
      assertThatThrownBy(() -> configuration.withdrawalJournalClient(endpoint, "key", "secret"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining("secret");
    }
    assertThatThrownBy(
            () ->
                configuration.withdrawalJournalClient(
                    "https://s3.us-west-004.backblazeb2.com", "", "secret"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                configuration.withdrawalJournalClient(
                    "https://s3.us-west-004.backblazeb2.com", "key", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void malformedRecipientLedgerOrRelativeExecutableCannotEnableRecording() {
    S3Client client = mock(S3Client.class);
    String recipient = "age1" + "a".repeat(58), ledger = UUID.randomUUID().toString();
    assertThatThrownBy(
            () ->
                configuration.withdrawalJournal(
                    client,
                    new ObjectMapper(),
                    "private-bucket",
                    "private-key",
                    ledger,
                    "/usr/bin/age"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                configuration.withdrawalJournal(
                    client,
                    new ObjectMapper(),
                    "private-bucket",
                    recipient,
                    "invalid",
                    "/usr/bin/age"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                configuration.withdrawalJournal(
                    client, new ObjectMapper(), "private-bucket", recipient, ledger, "age"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(client);
  }
}
