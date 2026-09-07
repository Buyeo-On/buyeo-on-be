UPDATE members
SET app_review_mode = (
    id = 'e203c0e2-f395-40c0-910d-79c66453a2a0'
    AND status = 'ACTIVE'
)
WHERE app_review_mode = true
   OR id = 'e203c0e2-f395-40c0-910d-79c66453a2a0';
