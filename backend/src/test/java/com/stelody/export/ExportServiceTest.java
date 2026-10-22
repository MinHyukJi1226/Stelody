package com.stelody.export;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.stelody.export.repository.ExportRepository;
import com.stelody.export.service.ExportService;
import com.stelody.export.service.YouTubeConnectionService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExportServiceTest {
  @Test
  void activeLookupDoesNotRequireEnabledExportsOrCallTheConnectionProvider() {
    var store = mock(ExportRepository.class);
    var connections = mock(YouTubeConnectionService.class);
    UUID user = UUID.randomUUID();
    when(store.activeJob(user)).thenReturn(Optional.empty());
    assertThat(new ExportService(store, connections).activeJob(user).job()).isNull();
    verify(store).activeJob(user);
    verifyNoMoreInteractions(store);
    verifyNoInteractions(connections);
  }
}
