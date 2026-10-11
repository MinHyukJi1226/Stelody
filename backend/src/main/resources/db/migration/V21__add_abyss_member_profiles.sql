-- Operator-supplied profiles; channel IDs verified with YouTube channels.list.
-- Existing profiles change only by stable ID. Images remain untouched.
WITH dates(id, debut_date, birthday_month, birthday_day) AS (
    VALUES
        ('808c2423-aff5-5311-930a-ffcb2c7ff69d'::uuid, DATE '2022-01-08', 5::smallint, 21::smallint),
        ('ae936a73-eda5-5c3c-a59d-33ee34d7576d'::uuid, DATE '2025-09-20', 7::smallint, 7::smallint),
        ('87a9538a-dade-571b-b249-ce12099fd04b'::uuid, DATE '2023-06-10', 1::smallint, 5::smallint),
        ('4360bf14-2f89-536b-bae3-e8a66b2a58b9'::uuid, DATE '2023-06-10', 2::smallint, 22::smallint),
        ('e2d35b29-639f-5509-a0fb-515c5da88900'::uuid, DATE '2023-06-11', 10::smallint, 1::smallint),
        ('c115d377-9edf-5b22-a6b6-58f2e589826b'::uuid, DATE '2023-06-11', 9::smallint, 7::smallint),
        ('a75974b0-e1de-50ae-a76e-c5eaf209c3f1'::uuid, DATE '2024-05-18', 3::smallint, 21::smallint),
        ('1cbb407e-162d-40fa-8091-08e0a9582faf'::uuid, DATE '2024-05-18', 5::smallint, 3::smallint),
        ('bd27574c-1aea-5517-a014-e854a8eb15b8'::uuid, DATE '2024-05-19', 8::smallint, 7::smallint),
        ('52e2098e-5593-4d53-b599-2ed7063a22a0'::uuid, DATE '2024-05-19', 4::smallint, 13::smallint),
        ('15b1cac9-a33a-509e-beed-1935c567659d'::uuid, DATE '2023-01-07', 12::smallint, 2::smallint)
)
UPDATE app.member m
SET debut_date = d.debut_date, birthday_month = d.birthday_month,
    birthday_day = d.birthday_day, version = m.version + 1, edited_at = CURRENT_TIMESTAMP
FROM dates d
WHERE m.id = d.id
  AND (m.debut_date, m.birthday_month, m.birthday_day)
      IS DISTINCT FROM (d.debut_date, d.birthday_month, d.birthday_day);

-- Stop rather than create duplicate profiles if these members were added manually.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM app.member WHERE search_name IN
        ('시즈키 토아', '코가네 랑', '하나요 히요리', '토키노 메리')) THEN
        RAISE EXCEPTION 'ABYSS profiles already exist; reconcile catalog before applying V21';
    END IF;
END $$;

INSERT INTO app.member(id, name, search_name, generation, activity_status,
    unit_name, chzzk_url, x_url, debut_date, birthday_month, birthday_day, edited_at)
VALUES
    ('e45fafbd-eda4-5d6c-b67b-b41999b2ad03', '시즈키 토아', '시즈키 토아', 4, 'ACTIVE', 'ABYSS', 'https://chzzk.naver.com/2299fad4bebb504b0978e1ec08b2f7d3', 'https://x.com/ShizukiToa_', DATE '2026-10-17', 11, 22, CURRENT_TIMESTAMP),
    ('b715a5e0-b10f-5a79-8405-9703e0543716', '코가네 랑', '코가네 랑', 4, 'ACTIVE', 'ABYSS', 'https://chzzk.naver.com/c657aa3592260a2b59669a72ec151936', 'https://x.com/CoganeRang', DATE '2026-10-17', 8, 22, CURRENT_TIMESTAMP),
    ('4fdb54c3-bfd5-5ef8-9b04-2e016b33d475', '하나요 히요리', '하나요 히요리', 4, 'ACTIVE', 'ABYSS', 'https://chzzk.naver.com/49d2c3cf8f0b2843d934ec15e82bb54c', 'https://x.com/HanayoHiyori', DATE '2026-10-18', 3, 31, CURRENT_TIMESTAMP),
    ('6ac3f9a8-90d3-5f90-91fb-37ccf645a8b9', '토키노 메리', '토키노 메리', 4, 'ACTIVE', 'ABYSS', 'https://chzzk.naver.com/792acf9ca28b42918710327e14b06b68', 'https://x.com/TokinoMary', DATE '2026-10-18', 6, 13, CURRENT_TIMESTAMP);

-- These official MEMBER channels join the existing discovery schedule.
-- A conflicting YouTube ID fails the transaction instead of replacing its owner.
INSERT INTO app.channel(id, youtube_id, name, member_id, channel_type, collection_enabled)
VALUES
    ('eaf4a97c-cdb6-525c-888c-8080a4610ac9', 'UClcSEuDF_g_6g4snw_nAavw', '시즈키 토아', 'e45fafbd-eda4-5d6c-b67b-b41999b2ad03', 'MEMBER', true),
    ('2c9301cb-6dd0-5e57-a8a4-13874c9f94d9', 'UCQ6LlsI7Bbj2yhSqLMI1gGA', '코가네 랑', 'b715a5e0-b10f-5a79-8405-9703e0543716', 'MEMBER', true),
    ('2d255d16-213c-5696-87a8-c6c3c20f25ad', 'UCHAr9pWGS30akL4MQCQT4uQ', '하나요 히요리', '4fdb54c3-bfd5-5ef8-9b04-2e016b33d475', 'MEMBER', true),
    ('da0f59c4-75ad-5df2-9006-5269b02f8d96', 'UCGSt1yNG5l-jpYRLpJ5oNtA', '토키노 메리', '6ac3f9a8-90d3-5f90-91fb-37ccf645a8b9', 'MEMBER', true);
