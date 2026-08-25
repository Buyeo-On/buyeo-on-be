WITH normalized_descriptions AS (
    SELECT id,
           trim(regexp_replace(regexp_replace(description, '<[^>]*>', ' ', 'g'), '\s+', ' ', 'g')) AS text
    FROM places
    WHERE source_name = 'TOUR_API'
      AND description IS NOT NULL
), first_sentences AS (
    SELECT id,
           trim(regexp_replace(text, '[.!?。].*$', '')) AS text
    FROM normalized_descriptions
)
UPDATE places place
SET summary = left(first_sentences.text, 36)
FROM first_sentences
WHERE place.id = first_sentences.id
  AND first_sentences.text <> '';
