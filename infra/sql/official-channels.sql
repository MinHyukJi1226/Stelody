-- User-supplied official channel handles, verified with channels.list on 2026-10-01.
-- Apply manually as schema owner. Keeps existing channel names, membership and disabled settings.
-- Member-channel ownership does not establish song participants or member profile metadata.
INSERT INTO app.channel(id,youtube_id,name,channel_type,collection_enabled) VALUES
    ('3604e713-5ddd-5ee0-9968-f1512fb22feb', 'UC2b4WRE5BZ6SIUWBeJU8rwg', 'STELLIVE 공식 채널', 'GROUP', true),
    ('452e97eb-5b90-5c2f-ad4e-f1e667fffb3a', 'UClbYIn9LDbbFZ9w2shX3K0g', '아야츠노 유니', 'MEMBER', true),
    ('72f9efed-301e-509c-96b0-45d1455fd120', 'UC0YQnenKBCu5sGb7H61n6HA', '사키하네 후야', 'MEMBER', true),
    ('8acb7a0f-0265-5bb0-b1bc-827d60f1d4d0', 'UC1afpiIuBDcjYlmruAa0HiA', '시라유키 히나', 'MEMBER', true),
    ('a9d73714-52ce-5f7c-9637-ee2056ef80e7', 'UC_eeSpMBz8PG4ssdBPnP07g', '네네코 마시로', 'MEMBER', true),
    ('06c49831-2365-5887-97c9-16f5ea6abc22', 'UC7-m6jQLinZQWIbwm9W-1iw', '아카네 리제', 'MEMBER', true),
    ('20f0204f-1d41-5006-8f0e-1dd0f78f2393', 'UCAHVQ44O81aehLWfy9O6Elw', '아라하시 타비', 'MEMBER', true),
    ('8a5a1c9c-c143-5319-8e83-b7b5724eeb6b', 'UCYxLMfeX1CbMBll9MsGlzmw', '텐코 시부키', 'MEMBER', true),
    ('303ee376-b7a6-5cfa-ad94-e393b70fbc9d', 'UCQmcltnre6aG9SkDRYZqFIg', '아오쿠모 린', 'MEMBER', true),
    ('98b5611c-41b3-520b-95c7-21025be3559a', 'UCcA21_PzN1EhNe7xS4MJGsQ', '하나코 나나', 'MEMBER', true),
    ('ebe5305b-8b80-5b9d-b215-35d5ac2567f5', 'UCj0c1jUr91dTetIQP2pFeLA', '유즈하 리코', 'MEMBER', true)
ON CONFLICT (youtube_id) DO NOTHING;
