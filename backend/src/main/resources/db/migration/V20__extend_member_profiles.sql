ALTER TABLE app.member
    ADD COLUMN unit_name VARCHAR(100),
    ADD COLUMN chzzk_url TEXT,
    ADD COLUMN x_url TEXT,
    ADD CONSTRAINT member_unit_name_check CHECK (unit_name IS NULL OR btrim(unit_name) <> ''),
    ADD CONSTRAINT member_chzzk_url_check CHECK (chzzk_url IS NULL OR btrim(chzzk_url) <> ''),
    ADD CONSTRAINT member_x_url_check CHECK (x_url IS NULL OR btrim(x_url) <> '');

-- Existing table-level runtime grants cover the new editable profile columns.
-- Collection queries and their permissions do not change.
