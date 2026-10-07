package com.stelody.user.service.ledger;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class WithdrawalJournalConfiguration {
  @Bean
  @ConditionalOnProperty(
      name = "stelody.withdrawal-journal.enabled",
      havingValue = "false",
      matchIfMissing = true)
  WithdrawalJournal disabledWithdrawalJournal() {
    return user -> {};
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = "stelody.withdrawal-journal.enabled", havingValue = "true")
  S3Client withdrawalJournalClient(
      @Value("${stelody.withdrawal-journal.endpoint}") String endpoint,
      @Value("${stelody.withdrawal-journal.access-key-id}") String access,
      @Value("${stelody.withdrawal-journal.secret-access-key}") String secret) {
    var match =
        Pattern.compile("https://s3\\.([a-z]{2}-[a-z]+-[0-9]{3})\\.backblazeb2\\.com")
            .matcher(endpoint);
    if (!match.matches() || access.isBlank() || secret.isBlank())
      throw new IllegalArgumentException(
          "Configure a dedicated Backblaze withdrawal key and HTTPS endpoint");
    return S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.of(match.group(1)))
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
        .forcePathStyle(true)
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .httpClientBuilder(
            UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(2))
                .socketTimeout(Duration.ofSeconds(2)))
        .overrideConfiguration(
            c ->
                c.apiCallTimeout(Duration.ofSeconds(3))
                    .apiCallAttemptTimeout(Duration.ofSeconds(3)))
        .build();
  }

  @Bean
  @ConditionalOnProperty(name = "stelody.withdrawal-journal.enabled", havingValue = "true")
  WithdrawalJournal withdrawalJournal(
      S3Client client,
      ObjectMapper mapper,
      @Value("${stelody.withdrawal-journal.bucket}") String bucket,
      @Value("${stelody.withdrawal-journal.recipient}") String recipient,
      @Value("${stelody.withdrawal-journal.ledger-id}") String ledgerId,
      @Value("${stelody.withdrawal-journal.age-binary}") String binary) {
    if (!bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")
        || !recipient.matches("age1[0-9a-z]{58}")
        || !binary.startsWith("/"))
      throw new IllegalArgumentException("Invalid withdrawal journal configuration");
    return new B2WithdrawalJournal(
        client,
        mapper,
        bucket,
        UUID.fromString(ledgerId),
        new AgeEncryption(binary, recipient),
        Clock.systemUTC());
  }
}
