-- User-requested display title: keep only the English name. Aliases are unchanged.
BEGIN;
SET LOCAL lock_timeout='5s';
SELECT id FROM app.song_entry WHERE id='8548105e-deec-5177-bcae-125838a26321' FOR UPDATE;
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM app.song_entry WHERE id='8548105e-deec-5177-bcae-125838a26321' AND (title='Rumor' OR (title='Rumor ルーマー' AND version=2))) THEN
 RAISE EXCEPTION 'Rumor title/version conflict'; END IF;
END $$;
CREATE TEMP TABLE rumor_title_before ON COMMIT DROP AS
SELECT id,jsonb_build_object('id',id,'title',title,'version',version) AS before_value
FROM app.song_entry WHERE id='8548105e-deec-5177-bcae-125838a26321' AND title<>'Rumor';
UPDATE app.song_entry SET title='Rumor',search_title='rumor',version=version+1,edited_at=CURRENT_TIMESTAMP
WHERE id IN (SELECT id FROM rumor_title_before);
INSERT INTO app.catalog_audit(id,target_type,target_id,action,before_value,after_value,reason)
SELECT gen_random_uuid(),'SONGS',s.id,'UPDATE',b.before_value,jsonb_build_object('id',s.id,'title',s.title,'version',s.version),
'사용자 요청: Rumor 제목은 영문만 유지; 별칭은 변경하지 않음'
FROM app.song_entry s JOIN rumor_title_before b ON b.id=s.id;
SELECT count(*) AS changed_songs,count(*) AS renamed_songs,0 AS aliases_changed FROM rumor_title_before;
COMMIT;
