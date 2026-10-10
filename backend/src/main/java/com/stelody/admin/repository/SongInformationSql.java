package com.stelody.admin.repository;

/** Supplemental metadata shared with the automatic registration queue; song alias must be s. */
public final class SongInformationSql {
  private SongInformationSql() {}

  public static final String INCOMPLETE_AUTO_REGISTRATION =
      """
      s.work_id IS NULL OR s.search_visibility='UNCHECKED'
      OR NOT EXISTS(SELECT 1 FROM app.work_artist w WHERE w.work_id=s.work_id)
      OR NOT EXISTS(SELECT 1 FROM app.karaoke_entry k WHERE k.provider='TJ' AND k.status<>'UNKNOWN' AND (k.song_id=s.id OR k.work_id=s.work_id))
      OR NOT EXISTS(SELECT 1 FROM app.karaoke_entry k WHERE k.provider='KY' AND k.status<>'UNKNOWN' AND (k.song_id=s.id OR k.work_id=s.work_id))
      """;

  public static final String SUPPLEMENTAL_MISSING_FIELDS =
      """
    array_remove(ARRAY[
      CASE WHEN s.work_id IS NULL THEN 'work' END,
      CASE WHEN NOT EXISTS(SELECT 1 FROM app.work_artist w WHERE w.work_id=s.work_id) THEN 'originalArtists' END,
      CASE WHEN NOT EXISTS(SELECT 1 FROM app.song_alias x WHERE x.song_id=s.id) THEN 'aliases' END,
      CASE WHEN s.search_visibility='UNCHECKED' THEN 'searchCheck' END,
      CASE WHEN NOT EXISTS(SELECT 1 FROM app.karaoke_entry k WHERE k.provider='TJ' AND k.status<>'UNKNOWN' AND (k.song_id=s.id OR k.work_id=s.work_id)) THEN 'TJ' END,
      CASE WHEN NOT EXISTS(SELECT 1 FROM app.karaoke_entry k WHERE k.provider='KY' AND k.status<>'UNKNOWN' AND (k.song_id=s.id OR k.work_id=s.work_id)) THEN 'KY' END
    ],NULL)
    """;
}
