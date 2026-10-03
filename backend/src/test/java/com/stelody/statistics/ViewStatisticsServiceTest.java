package com.stelody.statistics;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.stelody.song.repository.SongRepository;
import com.stelody.statistics.repository.StatisticsQueries;
import com.stelody.statistics.service.ViewStatisticsService;
import java.time.Clock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ViewStatisticsServiceTest {
  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "false,true"})
  void rankingNeedsBothOperationalEnableAndPolicyConfirmation(boolean enabled, boolean allowed) {
    var songs = mock(SongRepository.class);
    var queries = mock(StatisticsQueries.class);
    var service = new ViewStatisticsService(songs, queries, Clock.systemUTC(), enabled, allowed);
    var response = service.trending(20);
    assertThat(response.status()).isEqualTo("DISABLED");
    assertThat(response.items()).isEmpty();
    assertThat(response.referenceAt()).isNull();
    verifyNoInteractions(songs, queries);
  }
}
