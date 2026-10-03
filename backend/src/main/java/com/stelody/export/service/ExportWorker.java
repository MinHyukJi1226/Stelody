package com.stelody.export.service;

import com.stelody.export.config.ExportSettings;
import com.stelody.export.repository.ExportRepository;
import com.stelody.export.repository.ExportRepository.*;
import com.stelody.export.web.ExportException;
import com.stelody.export.youtube.YouTubePlaylistClient;
import java.util.*;
import javax.sql.DataSource;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExportWorker {
  private static final long LOCK = 2026100301L;
  private static final Logger LOG = LoggerFactory.getLogger(ExportWorker.class);
  private final DataSource source;
  private final ExportRepository store;
  private final ExportSettings settings;
  private final YouTubeConnectionService connections;
  private final YouTubePlaylistClient youtube;

  public ExportWorker(
      DataSource source,
      ExportRepository store,
      ExportSettings settings,
      YouTubeConnectionService connections,
      YouTubePlaylistClient youtube) {
    this.source = source;
    this.store = store;
    this.settings = settings;
    this.connections = connections;
    this.youtube = youtube;
  }

  @Scheduled(fixedDelayString = "${stelody.export.worker-delay-ms:2000}")
  public void scheduled() {
    if (settings.workerEnabled() && (settings.enabled() || !settings.key().isBlank())) tick();
  }

  public void tick() {
    if (!settings.enabled() && settings.key().isBlank()) return;
    try (var lockConnection = source.getConnection();
        var statement = lockConnection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
      statement.setLong(1, LOCK);
      try (var result = statement.executeQuery()) {
        result.next();
        if (!result.getBoolean(1)) return;
      }
      try {
        store.tx(
            () -> {
              store.cleanup();
              return null;
            });
        store.revoking().forEach(connections::revoke);
        if (!settings.enabled()) return;
        var next = store.next();
        if (next.isEmpty()) return;
        Task task = next.get();
        try {
          process(task, lockConnection);
        } catch (YouTubePlaylistClient.Failure e) {
          boolean inFlight = store.inFlight(task.id());
          boolean uncertain = inFlight && !e.rejected();
          store.tx(
              () -> {
                if (e.code().equals("YOUTUBE_RECONNECT_REQUIRED")) store.invalidate(task.userId());
                store.finish(task, uncertain ? "UNCERTAIN" : "FAILED", e.code(), !uncertain);
                return null;
              });
        } catch (ExportException e) {
          boolean uncertain = store.inFlight(task.id());
          store.tx(
              () -> {
                if (e.code().equals("YOUTUBE_RECONNECT_REQUIRED")) store.invalidate(task.userId());
                store.finish(task, uncertain ? "UNCERTAIN" : "FAILED", e.code(), !uncertain);
                return null;
              });
        } catch (RuntimeException e) {
          boolean uncertain = store.inFlight(task.id());
          store.tx(
              () -> {
                store.finish(
                    task,
                    uncertain ? "UNCERTAIN" : "FAILED",
                    "EXPORT_PROCESSING_FAILED",
                    !uncertain);
                return null;
              });
        }
      } finally {
        try (var unlock = lockConnection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
          unlock.setLong(1, LOCK);
          unlock.execute();
        }
      }
    } catch (Exception e) {
      // Never log provider responses, credentials, SQL parameters or playlist titles.
      LOG.error("youtubeExport code=EXPORT_WORKER_UNAVAILABLE");
    }
  }

  private void process(Task original, java.sql.Connection lockConnection) throws Exception {
    if (!store.tx(() -> store.start(original))) return;
    Task task = original;
    String token = connections.access(task.userId());
    if (task.inFlight()) task = reconcile(task, token);
    if (task.playlistId() == null) {
      Task current = task;
      assertLock(lockConnection);
      if (!store.tx(() -> store.beginWrite(current, null))) return;
      String external = youtube.create(token, task.name(), task.id());
      store.tx(
          () -> {
            store.created(current, external);
            return null;
          });
      task = new Task(task.id(), task.userId(), task.name(), "RUNNING", external, false, null);
    }
    List<String> expected = copied(task);
    if (!youtube.videos(token, task.playlistId()).equals(expected))
      throw new YouTubePlaylistClient.Failure("REMOTE_PLAYLIST_CHANGED", false);
    var pending =
        store.items(task.id()).stream()
            .filter(i -> i.status().equals("PENDING"))
            .limit(10)
            .toList();
    var publicVideos = youtube.publicVideos(token, pending.stream().map(Item::youtubeId).toList());
    int copied = expected.size();
    for (Item item : pending) {
      if (!item.status().equals("PENDING")) continue;
      Task current = task;
      if (!store.tx(() -> store.start(current))) return;
      if (!store.available(item) || !publicVideos.contains(item.youtubeId())) {
        store.tx(
            () -> {
              store.itemDone(current, item.position(), "SKIPPED");
              return null;
            });
        continue;
      }
      token = connections.access(task.userId());
      assertLock(lockConnection);
      if (!store.tx(() -> store.beginWrite(current, item.position()))) return;
      try {
        youtube.append(token, task.playlistId(), item.youtubeId(), copied);
        store.tx(
            () -> {
              store.itemDone(current, item.position(), "COPIED");
              return null;
            });
        copied++;
      } catch (YouTubePlaylistClient.Failure e) {
        if (e.rejected() && e.code().equals("YOUTUBE_VIDEO_UNAVAILABLE")) {
          store.tx(
              () -> {
                store.itemDone(current, item.position(), "SKIPPED");
                return null;
              });
        } else throw e;
      }
    }
    if (store.items(task.id()).stream().anyMatch(i -> i.status().equals("PENDING"))) return;
    Task current = task;
    store.tx(
        () -> {
          store.finish(current, "SUCCEEDED", null, true);
          return null;
        });
  }

  private Task reconcile(Task task, String token) {
    if (task.playlistId() == null) {
      String external =
          youtube
              .findCreated(token, task.id())
              .orElseThrow(
                  () -> new YouTubePlaylistClient.Failure("YOUTUBE_RESULT_UNCONFIRMED", false));
      store.tx(
          () -> {
            store.created(task, external);
            return null;
          });
      return new Task(task.id(), task.userId(), task.name(), "RUNNING", external, false, null);
    }
    var expected = new ArrayList<>(copied(task));
    Item item =
        store.items(task.id()).stream()
            .filter(
                i ->
                    Objects.equals(i.position(), task.inFlightPosition())
                        && i.status().equals("PENDING"))
            .findFirst()
            .orElseThrow(
                () -> new YouTubePlaylistClient.Failure("YOUTUBE_RESULT_UNCONFIRMED", false));
    var remote = youtube.videos(token, task.playlistId());
    if (remote.equals(expected))
      throw new YouTubePlaylistClient.Failure("YOUTUBE_RESULT_UNCONFIRMED", false);
    expected.add(item.youtubeId());
    if (!remote.equals(expected))
      throw new YouTubePlaylistClient.Failure("REMOTE_PLAYLIST_CHANGED", false);
    store.tx(
        () -> {
          store.itemDone(task, item.position(), "COPIED");
          return null;
        });
    return new Task(
        task.id(), task.userId(), task.name(), "RUNNING", task.playlistId(), false, null);
  }

  private List<String> copied(Task task) {
    return store.items(task.id()).stream()
        .filter(i -> i.status().equals("COPIED"))
        .map(Item::youtubeId)
        .toList();
  }

  private void assertLock(java.sql.Connection connection) throws java.sql.SQLException {
    // Check the held session before every mutation. A failed DB write leaves in_flight for
    // reconciliation.
    try (var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT 1")) {
      if (!result.next()) throw new java.sql.SQLException("Export lock connection unavailable");
    }
  }
}
