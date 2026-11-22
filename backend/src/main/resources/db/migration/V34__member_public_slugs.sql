ALTER TABLE app.member ADD COLUMN slug VARCHAR(40);
ALTER TABLE app.member ADD CONSTRAINT member_slug_format CHECK (slug ~ '^[a-z][a-z0-9-]{0,39}$');
CREATE UNIQUE INDEX member_slug_unique_idx ON app.member(slug);
-- Explicit public addresses; future members may retain null until assigned.
UPDATE app.member m SET slug = initial.slug FROM (VALUES
    ('808c2423-aff5-5311-930a-ffcb2c7ff69d'::uuid, 'yuni'),
    ('ae936a73-eda5-5c3c-a59d-33ee34d7576d'::uuid, 'huya'),
    ('87a9538a-dade-571b-b249-ce12099fd04b'::uuid, 'hina'),
    ('4360bf14-2f89-536b-bae3-e8a66b2a58b9'::uuid, 'mashiro'),
    ('e2d35b29-639f-5509-a0fb-515c5da88900'::uuid, 'lize'),
    ('c115d377-9edf-5b22-a6b6-58f2e589826b'::uuid, 'tabi'),
    ('a75974b0-e1de-50ae-a76e-c5eaf209c3f1'::uuid, 'shibuki'),
    ('1cbb407e-162d-40fa-8091-08e0a9582faf'::uuid, 'rin'),
    ('bd27574c-1aea-5517-a014-e854a8eb15b8'::uuid, 'nana'),
    ('52e2098e-5593-4d53-b599-2ed7063a22a0'::uuid, 'riko'),
    ('e45fafbd-eda4-5d6c-b67b-b41999b2ad03'::uuid, 'toa'),
    ('b715a5e0-b10f-5a79-8405-9703e0543716'::uuid, 'rang'),
    ('4fdb54c3-bfd5-5ef8-9b04-2e016b33d475'::uuid, 'hiyori'),
    ('6ac3f9a8-90d3-5f90-91fb-37ccf645a8b9'::uuid, 'mary'),
    ('15b1cac9-a33a-509e-beed-1935c567659d'::uuid, 'kanna')
) initial(id,slug) WHERE m.id=initial.id;
