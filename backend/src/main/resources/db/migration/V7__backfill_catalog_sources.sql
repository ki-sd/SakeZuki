-- Pause catalog writers through V8. A duplicate is left untouched so validation
-- detects conflicting ownership and a repaired V7 can be replayed.
INSERT INTO SAKE_SOURCE (sake_no, source_system, external_key)
SELECT no, 'SAKE_GUIDE', CAST(source_id AS CHAR CHARACTER SET utf8mb4)
FROM SAKE
WHERE source_id IS NOT NULL
ON DUPLICATE KEY UPDATE sake_no = SAKE_SOURCE.sake_no;

INSERT INTO BREWERY_SOURCE (brewery_no, source_system, external_key)
SELECT no, 'SAKE_GUIDE', CAST(source_id AS CHAR CHARACTER SET utf8mb4)
FROM BREWERY
WHERE source_id IS NOT NULL
ON DUPLICATE KEY UPDATE brewery_no = BREWERY_SOURCE.brewery_no;

INSERT INTO BRAND_SOURCE (brand_no, source_system, external_key)
SELECT no, 'SAKE_GUIDE', CAST(source_id AS CHAR CHARACTER SET utf8mb4)
FROM BRAND
WHERE source_id IS NOT NULL
ON DUPLICATE KEY UPDATE brand_no = BRAND_SOURCE.brand_no;

CREATE TEMPORARY TABLE catalog_source_backfill_check (
    entity VARCHAR(10) NOT NULL PRIMARY KEY,
    legacy_count BIGINT NOT NULL,
    guide_count BIGINT NOT NULL,
    matched_count BIGINT NOT NULL,
    CHECK (legacy_count = guide_count AND guide_count = matched_count)
);

INSERT INTO catalog_source_backfill_check
SELECT 'SAKE',
       (SELECT COUNT(*) FROM SAKE WHERE source_id IS NOT NULL),
       (SELECT COUNT(*) FROM SAKE_SOURCE WHERE source_system = 'SAKE_GUIDE'),
       (SELECT COUNT(*) FROM SAKE c JOIN SAKE_SOURCE s ON s.sake_no = c.no
        WHERE s.source_system = 'SAKE_GUIDE'
          AND s.external_key = CAST(c.source_id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_bin);

INSERT INTO catalog_source_backfill_check
SELECT 'BREWERY',
       (SELECT COUNT(*) FROM BREWERY WHERE source_id IS NOT NULL),
       (SELECT COUNT(*) FROM BREWERY_SOURCE WHERE source_system = 'SAKE_GUIDE'),
       (SELECT COUNT(*) FROM BREWERY c JOIN BREWERY_SOURCE s ON s.brewery_no = c.no
        WHERE s.source_system = 'SAKE_GUIDE'
          AND s.external_key = CAST(c.source_id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_bin);

INSERT INTO catalog_source_backfill_check
SELECT 'BRAND',
       (SELECT COUNT(*) FROM BRAND WHERE source_id IS NOT NULL),
       (SELECT COUNT(*) FROM BRAND_SOURCE WHERE source_system = 'SAKE_GUIDE'),
       (SELECT COUNT(*) FROM BRAND c JOIN BRAND_SOURCE s ON s.brand_no = c.no
        WHERE s.source_system = 'SAKE_GUIDE'
          AND s.external_key = CAST(c.source_id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_bin);

DROP TEMPORARY TABLE catalog_source_backfill_check;
