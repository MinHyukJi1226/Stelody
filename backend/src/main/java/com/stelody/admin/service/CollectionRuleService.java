package com.stelody.admin.service;

import com.stelody.admin.dto.CatalogAdminDtos.AuditPage;
import com.stelody.admin.dto.CollectionRuleDtos.*;
import com.stelody.admin.repository.CatalogAdminQueries;
import com.stelody.admin.web.AdminCatalogException;
import com.stelody.collector.domain.DiscoveryRules;
import com.stelody.collector.domain.RuleConfiguration;
import com.stelody.collector.domain.VideoObservation;
import com.stelody.collector.repository.CollectionRuleStore;
import com.stelody.collector.repository.CollectionRuleStore.Snapshot;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollectionRuleService {
  private final CollectionRuleStore store;
  private final CatalogAdminQueries audit;

  public CollectionRuleService(CollectionRuleStore store, CatalogAdminQueries audit) {
    this.store = store;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public Snapshot current() {
    return store.current();
  }

  private RuleConfiguration validate(RuleConfiguration input) {
    try {
      return input.validated();
    } catch (IllegalArgumentException e) {
      throw AdminCatalogException.invalid();
    }
  }

  @Transactional
  public Snapshot change(Change input, UUID actor) {
    var configuration = validate(input.configuration());
    var before = store.current();
    if (before.version() != input.version() || !store.update(input.version(), configuration))
      throw AdminCatalogException.conflict();
    var after = store.current();
    audit.audit(
        "COLLECTION_RULES",
        CollectionRuleStore.ID,
        actor,
        "UPDATE",
        before,
        after,
        input.reason().strip());
    return after;
  }

  @Transactional(readOnly = true)
  public PreviewResult preview(Preview input) {
    var current = store.current();
    if (current.version() != input.version()) throw AdminCatalogException.conflict();
    var rules = new DiscoveryRules(validate(input.configuration()), "preview:" + current.version());
    return new PreviewResult(
        current.version(),
        input.samples().stream()
            .map(
                s ->
                    rules.decide(
                        new VideoObservation(
                            "", null, s.availability(), s.title(), null, null, null, false, null),
                        true))
            .toList());
  }

  @Transactional(readOnly = true)
  public AuditPage audits(int page, int size) {
    if (page < 0 || page > 10000 || size < 1 || size > 50) throw AdminCatalogException.invalid();
    var rows = audit.audits("COLLECTION_RULES", CollectionRuleStore.ID, page, size);
    return new AuditPage(
        rows.subList(0, Math.min(size, rows.size())), page, size, rows.size() > size);
  }
}
