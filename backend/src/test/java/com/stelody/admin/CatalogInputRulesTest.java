package com.stelody.admin;

import static org.assertj.core.api.Assertions.*;

import com.stelody.admin.service.CatalogInputRules;
import com.stelody.admin.web.AdminCatalogException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogInputRulesTest {
  final CatalogInputRules rules =
      new CatalogInputRules(Validation.buildDefaultValidatorFactory().getValidator());

  @ParameterizedTest
  @ValueSource(
      strings = {
        "javascript:alert(1)",
        "http://example.com/a",
        "https://user:pass@example.com/a",
        "https://localhost/x",
        "https://127.0.0.1/a",
        "https://example.com:8443/a",
        "https://example.com/x#fragment"
      })
  void rejectsUnsafeLinks(String value) {
    assertThatThrownBy(() -> rules.url(value)).isInstanceOf(AdminCatalogException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "abcdefghijk",
        "https://youtu.be/abcdefghijk?t=10",
        "https://www.youtube.com/watch?v=abcdefghijk&list=test",
        "https://youtube.com/embed/abcdefghijk"
      })
  void normalizesYoutubeVideoIds(String value) {
    assertThat(rules.youtubeId(value)).isEqualTo("abcdefghijk");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://youtube.com.evil.example/watch?v=abcdefghijk",
        "http://youtube.com/watch?v=abcdefghijk",
        "https://youtube.com/watch?v=abcdefghijk&v=12345678901",
        "https://youtube.com/shorts/abcdefghijk",
        "https://youtu.be/not-an-id",
        "https://user@youtube.com/watch?v=abcdefghijk"
      })
  void rejectsNonCanonicalVideoLinks(String value) {
    assertThatThrownBy(() -> rules.youtubeId(value)).isInstanceOf(AdminCatalogException.class);
  }

  @Test
  void permitsLeapDayButRejectsPartialOrInvalidDates() {
    rules.birthday(2, 29);
    assertThatThrownBy(() -> rules.birthday(2, 30)).isInstanceOf(AdminCatalogException.class);
    assertThatThrownBy(() -> rules.birthday(null, 2)).isInstanceOf(AdminCatalogException.class);
  }

  @Test
  void aliasesAreUniqueAfterNormalization() {
    assertThatThrownBy(() -> rules.aliases(java.util.List.of("Cover", "Ｃｏｖｅｒ")))
        .isInstanceOf(AdminCatalogException.class);
  }

  record Nested(@jakarta.validation.constraints.NotBlank String url) {}

  record Inputs(
      @jakarta.validation.Valid java.util.List<Nested> links,
      java.util.List<@jakarta.validation.constraints.NotBlank String> aliases,
      @jakarta.validation.constraints.Size(max = 2, message = "unsafe ${validatedValue}")
          String token) {}

  @Test
  void directServiceValidationUsesIndexedPathsAndStaticMessages() {
    var error =
        catchThrowableOfType(
            () ->
                rules.validate(
                    new Inputs(
                        java.util.List.of(new Nested(" ")),
                        java.util.List.of("valid", " "),
                        "private-token")),
            AdminCatalogException.class);
    assertThat(error.fieldErrors())
        .extracting(com.stelody.auth.web.ApiFieldError::field)
        .containsExactly("aliases[1]", "links[0].url", "token");
    assertThat(error.fieldErrors())
        .extracting(com.stelody.auth.web.ApiFieldError::code)
        .containsExactly("REQUIRED", "REQUIRED", "INVALID_SIZE");
    assertThat(error.fieldErrors().toString()).doesNotContain("private-token", "unsafe");
  }

  @Test
  void duplicateAliasesIdentifyTheSecondNormalizedElement() {
    var error =
        catchThrowableOfType(
            () -> rules.aliases(java.util.List.of("Cover", "Ｃｏｖｅｒ")), AdminCatalogException.class);
    assertThat(error.fieldErrors())
        .containsExactly(com.stelody.auth.web.InputErrors.field("aliases[1]", "DUPLICATE"));
  }
}
