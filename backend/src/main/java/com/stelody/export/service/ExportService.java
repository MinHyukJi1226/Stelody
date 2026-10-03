package com.stelody.export.service;

import com.stelody.export.dto.ExportDtos;
import com.stelody.export.repository.ExportRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ExportService {
  private final ExportRepository store;
  private final YouTubeConnectionService connections;

  public ExportService(ExportRepository store, YouTubeConnectionService connections) {
    this.store = store;
    this.connections = connections;
  }

  public ExportDtos.Job create(UUID user, UUID playlist, ExportDtos.Create request) {
    connections.enabled();
    return store.tx(() -> store.create(user, playlist, request.requestId(), request.version()));
  }

  public ExportDtos.Job job(UUID user, UUID id) {
    return store.job(user, id);
  }

  public ExportDtos.Job retry(UUID user, UUID id) {
    connections.enabled();
    return store.tx(() -> store.retry(user, id));
  }

  public ExportDtos.Job cancel(UUID user, UUID id) {
    return store.tx(() -> store.cancel(user, id));
  }
}
