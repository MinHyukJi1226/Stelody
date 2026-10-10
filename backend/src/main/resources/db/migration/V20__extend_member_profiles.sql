ALTER TABLE app.member
    ADD COLUMN unit_name VARCHAR(100),
    ADD COLUMN chzzk_url TEXT,
    ADD COLUMN x_url TEXT,
    ADD CONSTRAINT member_unit_name_check CHECK (unit_name IS NULL OR btrim(unit_name) <> ''),
    ADD CONSTRAINT member_chzzk_url_check CHECK (chzzk_url IS NULL OR btrim(chzzk_url) <> ''),
    ADD CONSTRAINT member_x_url_check CHECK (x_url IS NULL OR btrim(x_url) <> '');

-- Existing table-level runtime grants cover the new editable profile columns.
-- Collection queries and their permissions do not change.

-- Profile values supplied by the operator on 2026-10-10.
-- Update only existing catalog members by stable ID; never create members or change
-- generation, activity status, aliases, channels, or song relationships.
WITH profiles(id, unit_name, chzzk_url, x_url) AS (
    VALUES
        -- 아야츠노 유니
        ('808c2423-aff5-5311-930a-ffcb2c7ff69d'::uuid, 'Everys', 'https://chzzk.naver.com/45e71a76e949e16a34764deb962f9d9f', 'https://x.com/AyatsunoYuni'),
        -- 사키하네 후야
        ('ae936a73-eda5-5c3c-a59d-33ee34d7576d'::uuid, 'Everys', 'https://chzzk.naver.com/36ddb9bb4f17593b60f1b63cec86611d', 'https://x.com/SakihaneHuya'),
        -- 시라유키 히나
        ('87a9538a-dade-571b-b249-ce12099fd04b'::uuid, 'Universe', 'https://chzzk.naver.com/b044e3a3b9259246bc92e863e7d3f3b8', 'https://x.com/Shirayukihina_'),
        -- 네네코 마시로
        ('4360bf14-2f89-536b-bae3-e8a66b2a58b9'::uuid, 'Universe', 'https://chzzk.naver.com/4515b179f86b67b4981e16190817c580', 'https://x.com/NenekoMashiro'),
        -- 아카네 리제
        ('e2d35b29-639f-5509-a0fb-515c5da88900'::uuid, 'Universe', 'https://chzzk.naver.com/4325b1d5bbc321fad3042306646e2e50', 'https://x.com/AkaneLize'),
        -- 아라하시 타비
        ('c115d377-9edf-5b22-a6b6-58f2e589826b'::uuid, 'Universe', 'https://chzzk.naver.com/a6c4ddb09cdb160478996007bff35296', 'https://x.com/ArahashiTabi'),
        -- 텐코 시부키
        ('a75974b0-e1de-50ae-a76e-c5eaf209c3f1'::uuid, 'Cliché', 'https://chzzk.naver.com/64d76089fba26b180d9c9e48a32600d9', 'https://x.com/TenkoShibuki'),
        -- 아오쿠모 린
        ('1cbb407e-162d-40fa-8091-08e0a9582faf'::uuid, 'Cliché', 'https://chzzk.naver.com/516937b5f85cbf2249ce31b0ad046b0f', 'https://x.com/AokumoRin'),
        -- 하나코 나나
        ('bd27574c-1aea-5517-a014-e854a8eb15b8'::uuid, 'Cliché', 'https://chzzk.naver.com/4d812b586ff63f8a2946e64fa860bbf5', 'https://x.com/HanakoNana_'),
        -- 유즈하 리코
        ('52e2098e-5593-4d53-b599-2ed7063a22a0'::uuid, 'Cliché', 'https://chzzk.naver.com/8fd39bb8de623317de90654718638b10', 'https://x.com/YuzuhaRiko'),
        -- 아이리 칸나
        ('15b1cac9-a33a-509e-beed-1935c567659d'::uuid, 'Mystic', NULL, NULL)
)
UPDATE app.member m
SET unit_name = p.unit_name, chzzk_url = p.chzzk_url, x_url = p.x_url,
    version = m.version + 1, edited_at = CURRENT_TIMESTAMP
FROM profiles p
WHERE m.id = p.id;
