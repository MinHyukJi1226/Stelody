package com.stelody.admin.service;

import com.stelody.admin.dto.InboxDtos.Overview;
import com.stelody.admin.repository.InboxQueries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InboxService {
  private final InboxQueries queries;

  public InboxService(InboxQueries queries) {
    this.queries = queries;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Overview overview() {
    return queries.overview();
  }
}
