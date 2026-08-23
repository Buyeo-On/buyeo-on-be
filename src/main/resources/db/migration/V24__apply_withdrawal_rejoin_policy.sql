DELETE FROM social_accounts
WHERE member_id IN (
    SELECT id
    FROM members
    WHERE status = 'WITHDRAWN'
);

DELETE FROM members
WHERE status = 'WITHDRAWN'
  AND purged_at IS NOT NULL;
