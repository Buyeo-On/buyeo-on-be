ALTER TABLE members
    ADD COLUMN app_review_mode boolean NOT NULL DEFAULT false;

UPDATE members
SET app_review_mode = true
WHERE id = '15d96eac-79ef-4a42-b4f1-5f5b41027483';
