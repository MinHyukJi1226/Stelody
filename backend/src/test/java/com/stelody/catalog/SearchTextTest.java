package com.stelody.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.stelody.catalog.domain.SearchText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SearchTextTest {
  @ParameterizedTest
  @CsvSource({
    "'  ＳＫＹ  ',sky",
    "'Ａ　Ｂ',a b",
    "'A  B',a b",
    "'ﾉﾝﾌﾞﾚｽ',ノンブレス",
    "'논브레스   오블리주',논브레스 오블리주"
  })
  void normalizesWidthCaseAndWhitespace(String input, String expected) {
    assertThat(SearchText.normalize(input)).isEqualTo(expected);
  }

  @Test
  void escapesLiteralWildcardsAndEscapeCharacter() {
    assertThat(SearchText.likePattern("100%_!")).isEqualTo("%100!%!_!!%");
  }
}
