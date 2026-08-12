ALTER TABLE terms
    ADD CONSTRAINT terms_type_effective_at_unique UNIQUE (type, effective_at);
