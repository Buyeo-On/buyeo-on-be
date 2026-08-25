UPDATE places
SET always_open = true,
    opens_at = null,
    closes_at = null
WHERE source_name = 'TOUR_API'
  AND operating_hours_raw ~ '(상시\s*개방|연중\s*무휴)';
