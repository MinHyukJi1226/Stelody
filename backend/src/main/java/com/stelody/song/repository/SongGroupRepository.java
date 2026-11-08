package com.stelody.song.repository;

import com.stelody.catalog.repository.PublicCatalogSql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SongGroupRepository {
  static final String ARTIST_COUNTS =
      """
      , artist_counts AS (
        SELECT wa.artist_id,count(DISTINCT s.id) AS total
        FROM public_songs s JOIN app.work_artist wa ON wa.work_id=s.work_id
        GROUP BY wa.artist_id
      )
      """;
  static final String OTHER_ARTISTS =
      """
      (NOT EXISTS (SELECT 1 FROM app.work_artist wa WHERE wa.work_id=s.work_id)
       OR EXISTS (SELECT 1 FROM app.work_artist wa JOIN artist_counts ac ON ac.artist_id=wa.artist_id
                  WHERE wa.work_id=s.work_id AND ac.total<2))
      """;

  public record Position(
      int section,
      int activity,
      int generation,
      int display,
      LocalDate debut,
      String name,
      String id) {}

  public record Group(String id, String name, Position position) {}

  private final JdbcClient jdbc;

  public SongGroupRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<Group> list(String groupBy, int size, Position cursor) {
    String source =
        switch (groupBy) {
          case "TYPE" ->
              """
          SELECT DISTINCT s.song_type AS group_id,
            CASE s.song_type WHEN 'ORIGINAL' THEN '오리지널' ELSE '커버' END AS name,
            CASE s.song_type WHEN 'ORIGINAL' THEN 0 ELSE 1 END AS section,
            0 AS activity,0 AS generation,0 AS display,DATE '0001-01-01' AS debut,'' AS sort_name
          FROM public_songs s
          """;
          case "MEMBER" ->
              """
          SELECT m.id::text AS group_id,m.name,0 AS section,
            CASE WHEN m.activity_status='ACTIVE' THEN 1 ELSE 2 END AS activity,
            coalesce(m.generation,32768) AS generation,coalesce(m.display_order,2147483647) AS display,
            coalesce(m.debut_date,DATE '9999-12-31') AS debut,m.search_name AS sort_name
          FROM app.member m WHERE EXISTS (
            SELECT 1 FROM public_songs s JOIN app.song_member sm ON sm.song_id=s.id
            WHERE sm.confirmed AND sm.member_id=m.id)
          """;
          case "ORIGINAL_ARTIST" ->
              """
          SELECT a.id::text AS group_id,a.name,0 AS section,
            0 AS activity,0 AS generation,0 AS display,DATE '0001-01-01' AS debut,a.search_name AS sort_name
          FROM app.artist a JOIN artist_counts ac ON ac.artist_id=a.id WHERE ac.total>=2
          UNION ALL
          SELECT 'OTHERS','기타',1,0,0,0,DATE '0001-01-01',''
          WHERE EXISTS (SELECT 1 FROM public_songs s WHERE
          """
                  + OTHER_ARTISTS
                  + ")";
          default -> throw new IllegalArgumentException("Invalid grouping");
        };
    var sql = new StringBuilder(PublicCatalogSql.SONGS);
    if (groupBy.equals("ORIGINAL_ARTIST")) sql.append(ARTIST_COUNTS);
    sql.append(", groups AS (").append(source).append(") SELECT * FROM groups g WHERE true");
    var params = new HashMap<String, Object>();
    if (cursor != null) {
      sql.append(
          """
           AND (g.section,g.activity,g.generation,g.display,g.debut,g.sort_name COLLATE "C",g.group_id COLLATE "C")
             > (:section,:activity,:generation,:display,CAST(:debut AS date),:name COLLATE "C",:id COLLATE "C")
          """);
      params.put("section", cursor.section());
      params.put("activity", cursor.activity());
      params.put("generation", cursor.generation());
      params.put("display", cursor.display());
      params.put("debut", cursor.debut().toString());
      params.put("name", cursor.name());
      params.put("id", cursor.id());
    }
    sql.append(
        """
         ORDER BY g.section,g.activity,g.generation,g.display,g.debut,g.sort_name COLLATE "C",g.group_id COLLATE "C"
         LIMIT :limit
        """);
    params.put("limit", size + 1);
    return jdbc.sql(sql.toString()).params(params).query(this::map).list();
  }

  private Group map(ResultSet r, int index) throws SQLException {
    String id = r.getString("group_id");
    return new Group(
        id,
        r.getString("name"),
        new Position(
            r.getInt("section"),
            r.getInt("activity"),
            r.getInt("generation"),
            r.getInt("display"),
            r.getObject("debut", LocalDate.class),
            r.getString("sort_name"),
            id));
  }
}
