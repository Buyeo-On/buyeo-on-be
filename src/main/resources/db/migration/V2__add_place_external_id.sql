ALTER TABLE places
    ADD COLUMN external_id text;

ALTER TABLE places
    ADD CONSTRAINT places_external_id_requires_source_name_ck
    CHECK (external_id IS NULL OR source_name IS NOT NULL);

CREATE UNIQUE INDEX places_source_external_id_uq
    ON places (source_name, external_id)
    WHERE external_id IS NOT NULL;
