package com.stelody.admin.service;

import com.stelody.admin.dto.ArtistRegistrationDtos.*;
import com.stelody.admin.dto.CatalogAdminDtos.*;
import com.stelody.admin.repository.ArtistRegistrationRepository;
import com.stelody.admin.repository.ArtistRegistrationRepository.Receipt;
import com.stelody.admin.repository.CatalogAdminQueries;
import com.stelody.admin.repository.CatalogAdminQueries.Resource;
import com.stelody.admin.web.AdminCatalogException;
import com.stelody.catalog.domain.SearchText;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class ArtistRegistrationService {
  public static final String HEADER = "Idempotency-Key";
  public static final String REPLAY_HEADER = "Idempotency-Replayed";

  public record Submission(Saved<Artist> saved, boolean replayed) {}

  private final ArtistRegistrationRepository store;
  private final CatalogManagementService catalog;
  private final CatalogAdminQueries queries;
  private final ObjectMapper mapper;
  private final TransactionTemplate independent;
  private final TransactionTemplate creation;

  public ArtistRegistrationService(
      ArtistRegistrationRepository store,
      CatalogManagementService catalog,
      CatalogAdminQueries queries,
      ObjectMapper mapper,
      PlatformTransactionManager transactions) {
    this.store = store;
    this.catalog = catalog;
    this.queries = queries;
    this.mapper = mapper;
    independent = transaction(transactions, 10);
    creation = transaction(transactions, 30);
  }

  private static TransactionTemplate transaction(PlatformTransactionManager manager, int seconds) {
    var tx = new TransactionTemplate(manager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setTimeout(seconds);
    return tx;
  }

  public Submission create(UUID actor, String rawId, ArtistInput body) {
    UUID id = parse(rawId, HEADER);
    String hash;
    try {
      hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    try {
      independent.executeWithoutResult(
          tx -> {
            if (store.find(id).isEmpty()) store.accept(id, actor, hash);
            validate(store.find(id).orElseThrow(ArtistRegistrationService::notFound), actor, hash);
          });
    } catch (AdminCatalogException error) {
      throw error;
    } catch (RuntimeException unavailable) {
      throw unavailable();
    }

    try {
      return creation.execute(
          tx -> {
            var receipt =
                store
                    .lock(id)
                    .orElseThrow(
                        () ->
                            new AdminCatalogException(
                                409,
                                "ARTIST_REGISTRATION_PROCESSING",
                                "동일한 요청을 처리 중입니다. 처리 결과를 조회해 주세요"));
            validate(receipt, actor, hash);
            if (receipt.status().equals("COMPLETED")) {
              var artist = queries.artist(receipt.artistId());
              return new Submission(
                  new Saved<>(
                      artist,
                      queries.duplicates(
                          Resource.ARTISTS, artist.id(), SearchText.normalize(artist.name()))),
                  true);
            }
            if (receipt.status().equals("FAILED"))
              throw new AdminCatalogException(
                  receipt.errorStatus(), receipt.errorCode(), "이 등록 요청은 실패했습니다. 처리 결과를 확인해 주세요");
            var saved = catalog.artist(null, body, actor);
            store.complete(id, saved.item().id());
            return new Submission(saved, false);
          });
    } catch (AdminCatalogException error) {
      if (!Set.of(
              "ARTIST_REGISTRATION_PROCESSING",
              "ARTIST_REGISTRATION_REQUEST_CONFLICT",
              "ARTIST_REGISTRATION_RESULT_EXPIRED",
              "ARTIST_REGISTRATION_NOT_FOUND")
          .contains(error.code())) failed(id, actor, hash, error);
      throw error;
    } catch (RuntimeException error) {
      var normalized =
          error instanceof DataIntegrityViolationException
              ? new AdminCatalogException(409, "CATALOG_DATA_CONFLICT", "중복 또는 연결된 항목을 확인해 주세요")
              : unavailable();
      failed(id, actor, hash, normalized);
      throw normalized;
    }
  }

  public Result get(UUID actor, String rawId) {
    UUID id = parse(rawId, "requestId");
    try {
      var receipt = store.find(id).filter(r -> actor.equals(r.actor()));
      if (receipt.isEmpty())
        return new Result(id, Status.NOT_PROCESSED, null, null, null, null, null, null);
      var r = receipt.get();
      if (r.expired()) throw expired();
      return new Result(
          id,
          Status.valueOf(r.status()),
          r.artistId(),
          r.errorCode(),
          r.createdAt(),
          r.expiresAt(),
          r.finishedAt(),
          r.status().equals("PROCESSING") ? 3 : null);
    } catch (AdminCatalogException error) {
      throw error;
    } catch (RuntimeException unavailable) {
      throw unavailable();
    }
  }

  private void failed(UUID id, UUID actor, String hash, AdminCatalogException error) {
    try {
      independent.executeWithoutResult(
          tx -> store.fail(id, actor, hash, error.code(), error.status()));
    } catch (RuntimeException unavailable) {
      // Leave PROCESSING. Maintenance fails only unlocked abandoned attempts, never a committed
      // creation.
    }
  }

  public int maintain() {
    return Objects.requireNonNull(independent.execute(tx -> store.maintain()));
  }

  private static void validate(Receipt receipt, UUID actor, String hash) {
    if (!actor.equals(receipt.actor())) throw notFound();
    if (receipt.expired()) throw expired();
    if (!receipt.hash().equals(hash))
      throw new AdminCatalogException(
          409, "ARTIST_REGISTRATION_REQUEST_CONFLICT", "같은 요청 ID의 본문이 다릅니다. 기존 결과를 확인해 주세요");
  }

  private static UUID parse(String raw, String field) {
    try {
      if (raw == null
          || !raw.matches(
              "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
        throw new IllegalArgumentException();
      return UUID.fromString(raw);
    } catch (IllegalArgumentException invalid) {
      throw AdminCatalogException.invalid(field, "INVALID_FORMAT");
    }
  }

  private static AdminCatalogException expired() {
    return new AdminCatalogException(
        410,
        "ARTIST_REGISTRATION_RESULT_EXPIRED",
        "아티스트 등록 결과 조회 기간이 만료되었습니다. 같은 요청 ID로 다시 등록할 수 없습니다");
  }

  private static AdminCatalogException notFound() {
    return new AdminCatalogException(404, "ARTIST_REGISTRATION_NOT_FOUND", "해당 요청을 사용할 수 없습니다");
  }

  private static AdminCatalogException unavailable() {
    return new AdminCatalogException(
        503,
        "ARTIST_REGISTRATION_STORAGE_UNAVAILABLE",
        "등록 처리 결과를 확인할 수 없습니다. 같은 요청 ID와 본문을 보관하고 다시 확인해 주세요");
  }
}
