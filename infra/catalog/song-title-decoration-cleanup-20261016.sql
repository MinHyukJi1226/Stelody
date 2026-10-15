-- Remove decorative characters from the two reviewed display titles only.
BEGIN;
SET LOCAL lock_timeout='5s';
CREATE TEMP TABLE title_cleanup(id uuid,old_title text,new_title text,new_search text,expected_version bigint) ON COMMIT DROP;
INSERT INTO title_cleanup VALUES
('2bd06841-2164-56f0-9a5d-81d83a6bd1c1','「Orange','Orange','orange',2),
('9029de9e-a17e-5c64-82fd-c798e7427d7d','✧Alice in Musicland✧','Alice in Musicland','alice in musicland',2);
SELECT s.id FROM app.song_entry s JOIN title_cleanup t ON t.id=s.id ORDER BY s.id FOR UPDATE;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM title_cleanup t LEFT JOIN app.song_entry s ON s.id=t.id WHERE s.id IS NULL OR (s.title<>t.new_title AND (s.title<>t.old_title OR s.version<>t.expected_version))) THEN
 RAISE EXCEPTION 'Title cleanup conflict'; END IF;
END $$;
CREATE TEMP TABLE title_cleanup_before ON COMMIT DROP AS
SELECT s.id,jsonb_build_object('id',s.id,'title',s.title,'version',s.version,'aliases',COALESCE((SELECT jsonb_agg(alias ORDER BY search_alias) FROM app.song_alias WHERE song_id=s.id),'[]'::jsonb)) AS before_value
FROM app.song_entry s JOIN title_cleanup t ON t.id=s.id WHERE s.title<>t.new_title OR EXISTS (SELECT 1 FROM app.song_alias a WHERE a.song_id=s.id AND a.alias=t.old_title);
-- Decorative strings are not meaningful search aliases; remove only the exact two values.
DELETE FROM app.song_alias a USING title_cleanup t
WHERE a.song_id=t.id AND a.alias=t.old_title;
UPDATE app.song_entry s SET title=t.new_title,search_title=t.new_search,version=version+1,edited_at=CURRENT_TIMESTAMP
FROM title_cleanup t JOIN title_cleanup_before b ON b.id=t.id WHERE s.id=t.id;
INSERT INTO app.catalog_audit(id,target_type,target_id,action,before_value,after_value,reason)
SELECT gen_random_uuid(),'SONGS',s.id,'UPDATE',b.before_value,jsonb_build_object('id',s.id,'title',s.title,'version',s.version,'aliases',(SELECT jsonb_agg(alias ORDER BY search_alias) FROM app.song_alias WHERE song_id=s.id)),
'사용자 요청: Orange 및 Alice in Musicland 제목 장식 문자 제거; 장식 문자 별칭도 제거'
FROM app.song_entry s JOIN title_cleanup_before b ON b.id=s.id;
SELECT (SELECT count(*) FROM title_cleanup_before) AS changed_songs, (SELECT count(*) FROM title_cleanup_before) AS renamed_songs, (SELECT count(*) FROM title_cleanup_before) AS cleaned_decorative_aliases;
COMMIT;
