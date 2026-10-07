package com.stelody.user.service.ledger;

import java.util.UUID;

public interface WithdrawalJournal {
  // Called after the guarded DELETE, before the database transaction commits.
  void record(UUID user);
}
