ALTER TABLE app.member ADD COLUMN display_order INTEGER
    CHECK (display_order > 0 AND display_order < 2147483647);

-- Operator-defined order within each generation; existing identities and links stay intact.
WITH positions(id, display_order) AS (VALUES
    ('808c2423-aff5-5311-930a-ffcb2c7ff69d'::uuid, 1),
    ('ae936a73-eda5-5c3c-a59d-33ee34d7576d'::uuid, 2),
    ('4360bf14-2f89-536b-bae3-e8a66b2a58b9'::uuid, 2),
    ('87a9538a-dade-571b-b249-ce12099fd04b'::uuid, 1),
    ('e2d35b29-639f-5509-a0fb-515c5da88900'::uuid, 3),
    ('c115d377-9edf-5b22-a6b6-58f2e589826b'::uuid, 4),
    ('a75974b0-e1de-50ae-a76e-c5eaf209c3f1'::uuid, 1),
    ('1cbb407e-162d-40fa-8091-08e0a9582faf'::uuid, 2),
    ('bd27574c-1aea-5517-a014-e854a8eb15b8'::uuid, 3),
    ('52e2098e-5593-4d53-b599-2ed7063a22a0'::uuid, 4),
    ('e45fafbd-eda4-5d6c-b67b-b41999b2ad03'::uuid, 1),
    ('b715a5e0-b10f-5a79-8405-9703e0543716'::uuid, 2),
    ('4fdb54c3-bfd5-5ef8-9b04-2e016b33d475'::uuid, 3),
    ('6ac3f9a8-90d3-5f90-91fb-37ccf645a8b9'::uuid, 4)
)
UPDATE app.member m SET display_order=p.display_order,
    version=m.version+1, edited_at=CURRENT_TIMESTAMP
FROM positions p WHERE m.id=p.id;
