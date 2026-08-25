UPDATE places
SET always_open = true,
    opens_at = null,
    closes_at = null
WHERE source_name = 'TOUR_API'
  AND operating_hours_raw ~ '(상시\\s*개방|연중\\s*무휴)';

WITH parsed_hours AS (
    SELECT id, regexp_match(operating_hours_raw, '(\d{1,2}:\d{2})\s*[~-]\s*(\d{1,2}:\d{2})') AS matches
    FROM places
    WHERE source_name = 'TOUR_API'
      AND operating_hours_raw IS NOT NULL
)
UPDATE places place
SET always_open = false,
    opens_at = parsed_hours.matches[1]::time,
    closes_at = parsed_hours.matches[2]::time
FROM parsed_hours
WHERE place.id = parsed_hours.id
  AND parsed_hours.matches IS NOT NULL;
