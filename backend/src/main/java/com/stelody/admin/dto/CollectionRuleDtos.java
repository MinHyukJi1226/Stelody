package com.stelody.admin.dto;

import com.stelody.collector.domain.DiscoveryRules.Decision;
import com.stelody.collector.domain.RuleConfiguration;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class CollectionRuleDtos {
  private CollectionRuleDtos() {}

  public record Change(
      @NotNull @PositiveOrZero Long version,
      @NotNull RuleConfiguration configuration,
      @NotBlank @Size(max = 500) String reason) {}

  public record Sample(
      @NotBlank @Size(max = 500) String title,
      @NotBlank @Pattern(regexp = "PUBLIC|UNLISTED|PRIVATE|DELETED|UNAVAILABLE")
          String availability) {}

  public record Preview(
      @NotNull @PositiveOrZero Long version,
      @NotNull RuleConfiguration configuration,
      @NotEmpty @Size(max = 20) List<@NotNull @Valid Sample> samples) {}

  @Schema(requiredProperties = {"baseVersion", "decisions"})
  public record PreviewResult(long baseVersion, List<Decision> decisions) {}
}
