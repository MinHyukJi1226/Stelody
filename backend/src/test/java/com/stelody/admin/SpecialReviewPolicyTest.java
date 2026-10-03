package com.stelody.admin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.stelody.admin.repository.*;
import com.stelody.admin.service.*;
import com.stelody.admin.web.AdminCatalogException;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpecialReviewPolicyTest {
  @Test
  void manualGenerationRequiresPolicyPermissionBeforeAnyDatabaseAccess() {
    var queries = mock(SpecialReviewQueries.class);
    var service =
        new SpecialReviewService(
            queries, mock(CatalogAdminQueries.class), mock(EntityManager.class), false);
    assertThatThrownBy(() -> service.refresh(UUID.randomUUID()))
        .isInstanceOf(AdminCatalogException.class)
        .satisfies(error -> assertThat(((AdminCatalogException) error).status()).isEqualTo(503));
    verifyNoInteractions(queries);
  }

  @Test
  void automaticCreationRequiresPolicyEvenWhenScheduled() {
    var queries = mock(SpecialReviewQueries.class);
    when(queries.scanLock()).thenReturn(true);
    var service =
        new SpecialReviewService(
            queries, mock(CatalogAdminQueries.class), mock(EntityManager.class), false);
    service.scan();
    verify(queries, never()).nextBatch();
  }

  @Test
  void disablingCreationStillPurgesExpiredSourceEvidence() {
    var service = mock(SpecialReviewService.class);
    new SpecialReviewWorker(service, false).tick();
    verify(service).expire();
    verify(service, never()).scan();
  }
}
