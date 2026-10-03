package com.stelody.user.service;

import com.stelody.export.repository.ExportRepository;
import com.stelody.export.service.YouTubeConnectionService;
import com.stelody.user.repository.WithdrawalRepository;
import com.stelody.user.web.AccountException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WithdrawalService {
  private final WithdrawalRepository store;
  private final ExportRepository exports;
  private final YouTubeConnectionService connections;
  private final TransactionTemplate transaction;

  public WithdrawalService(
      WithdrawalRepository store,
      ExportRepository exports,
      YouTubeConnectionService connections,
      PlatformTransactionManager manager) {
    this.store = store;
    this.exports = exports;
    this.connections = connections;
    transaction = new TransactionTemplate(manager);
    transaction.setTimeout(10);
  }

  public void withdraw(UUID user, String session) {
    String hash = YouTubeConnectionService.hash(session);
    boolean deleted =
        Boolean.TRUE.equals(
            transaction.execute(
                tx -> {
                  // The export worker holds this same lock across external writes.
                  if (!exports.tryProcessingLock())
                    throw new AccountException(
                        409, "YOUTUBE_EXPORT_BUSY", "YouTube 작업이 진행 중입니다. 잠시 후 다시 시도해 주세요");
                  store.lockActive(user);
                  if (store.confirmation(user, hash).isEmpty())
                    throw AccountException.reauthenticationRequired();
                  connections.disconnect(user);
                  var connection = exports.connection(user);
                  if (connection.isPresent() && !connection.get().status().equals("DISCONNECTED"))
                    return false;
                  if (!store.delete(user, hash)) throw AccountException.reauthenticationRequired();
                  return true;
                }));
    // Commit REVOKING and cancellation even if Google is unavailable; the worker can retry.
    if (!deleted)
      throw new AccountException(
          409, "YOUTUBE_REVOCATION_PENDING", "YouTube 연결 철회가 완료되지 않았습니다. 잠시 후 다시 시도해 주세요");
  }
}
