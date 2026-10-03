package com.stelody.collector.repository;

import com.stelody.collector.domain.DiscoveryRules;
import com.stelody.collector.domain.RuleConfiguration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CollectionRuleStore {
  public static final UUID ID = new UUID(0, 1);

  public record Snapshot(long version, RuleConfiguration configuration) {
    public DiscoveryRules rules() {
      return new DiscoveryRules(configuration, "title-v2:" + version);
    }
  }

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;

  public CollectionRuleStore(JdbcClient jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public Snapshot current() {
    return jdbc.sql("SELECT version, configuration::text FROM app.collection_rule WHERE id=:id")
        .param("id", ID)
        .query(
            (r, n) ->
                new Snapshot(
                    r.getLong(1),
                    mapper.readValue(r.getString(2), RuleConfiguration.class).validated()))
        .single();
  }

  public boolean update(long version, RuleConfiguration configuration) {
    return jdbc.sql(
                "UPDATE app.collection_rule SET configuration=CAST(:configuration AS jsonb), version=version+1, edited_at=now() WHERE id=:id AND version=:version")
            .param("configuration", mapper.writeValueAsString(configuration))
            .param("id", ID)
            .param("version", version)
            .update()
        == 1;
  }
}
