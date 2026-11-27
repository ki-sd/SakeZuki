-- Source identity is independent of the catalog primary key.
CREATE TABLE SAKE_SOURCE (
    no BIGINT NOT NULL AUTO_INCREMENT,
    sake_no BIGINT NOT NULL,
    source_system ENUM('SAKE_GUIDE','USER_SUBMISSION','ADMIN','BREWERY_OFFICIAL') NOT NULL,
    external_key VARCHAR(255) COLLATE utf8mb4_0900_bin DEFAULT NULL,
    canonical_url VARCHAR(2048) DEFAULT NULL,
    evidence_ref VARCHAR(500) DEFAULT NULL,
    observed_at DATETIME(6) DEFAULT NULL,
    review_status ENUM('UNREVIEWED','VERIFIED','REJECTED') NOT NULL DEFAULT 'UNREVIEWED',
    reviewed_at DATETIME(6) DEFAULT NULL,
    keyless_slot TINYINT GENERATED ALWAYS AS (IF(external_key IS NULL, 1, NULL)) STORED,
    PRIMARY KEY (no),
    UNIQUE KEY uq_sake_source_external (source_system, external_key),
    UNIQUE KEY uq_sake_source_keyless (sake_no, source_system, keyless_slot),
    CONSTRAINT fk_sake_source_catalog FOREIGN KEY (sake_no) REFERENCES SAKE (no),
    CONSTRAINT ck_sake_source_key CHECK (external_key IS NULL OR (
        CHAR_LENGTH(external_key) > 0
        AND NOT REGEXP_LIKE(external_key, '^[[:space:]]|[[:space:]]$|[[:cntrl:]]', 'c')
    )),
    CONSTRAINT ck_sake_source_guide CHECK (source_system <> 'SAKE_GUIDE' OR external_key IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE BREWERY_SOURCE (
    no BIGINT NOT NULL AUTO_INCREMENT,
    brewery_no BIGINT NOT NULL,
    source_system ENUM('SAKE_GUIDE','USER_SUBMISSION','ADMIN','BREWERY_OFFICIAL') NOT NULL,
    external_key VARCHAR(255) COLLATE utf8mb4_0900_bin DEFAULT NULL,
    canonical_url VARCHAR(2048) DEFAULT NULL,
    evidence_ref VARCHAR(500) DEFAULT NULL,
    observed_at DATETIME(6) DEFAULT NULL,
    review_status ENUM('UNREVIEWED','VERIFIED','REJECTED') NOT NULL DEFAULT 'UNREVIEWED',
    reviewed_at DATETIME(6) DEFAULT NULL,
    keyless_slot TINYINT GENERATED ALWAYS AS (IF(external_key IS NULL, 1, NULL)) STORED,
    legacy_key VARCHAR(255) COLLATE utf8mb4_0900_ai_ci
        GENERATED ALWAYS AS (external_key) VIRTUAL,
    PRIMARY KEY (no),
    UNIQUE KEY uq_brewery_source_external (source_system, external_key),
    KEY ix_brewery_source_legacy_key (source_system, legacy_key),
    UNIQUE KEY uq_brewery_source_keyless (brewery_no, source_system, keyless_slot),
    CONSTRAINT fk_brewery_source_catalog FOREIGN KEY (brewery_no) REFERENCES BREWERY (no),
    CONSTRAINT ck_brewery_source_key CHECK (external_key IS NULL OR (
        CHAR_LENGTH(external_key) > 0
        AND NOT REGEXP_LIKE(external_key, '^[[:space:]]|[[:space:]]$|[[:cntrl:]]', 'c')
    )),
    CONSTRAINT ck_brewery_source_guide CHECK (source_system <> 'SAKE_GUIDE' OR external_key IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE BRAND_SOURCE (
    no BIGINT NOT NULL AUTO_INCREMENT,
    brand_no BIGINT NOT NULL,
    source_system ENUM('SAKE_GUIDE','USER_SUBMISSION','ADMIN','BREWERY_OFFICIAL') NOT NULL,
    external_key VARCHAR(255) COLLATE utf8mb4_0900_bin DEFAULT NULL,
    canonical_url VARCHAR(2048) DEFAULT NULL,
    evidence_ref VARCHAR(500) DEFAULT NULL,
    observed_at DATETIME(6) DEFAULT NULL,
    review_status ENUM('UNREVIEWED','VERIFIED','REJECTED') NOT NULL DEFAULT 'UNREVIEWED',
    reviewed_at DATETIME(6) DEFAULT NULL,
    keyless_slot TINYINT GENERATED ALWAYS AS (IF(external_key IS NULL, 1, NULL)) STORED,
    legacy_key VARCHAR(255) COLLATE utf8mb4_0900_ai_ci
        GENERATED ALWAYS AS (external_key) VIRTUAL,
    PRIMARY KEY (no),
    UNIQUE KEY uq_brand_source_external (source_system, external_key),
    KEY ix_brand_source_legacy_key (source_system, legacy_key),
    UNIQUE KEY uq_brand_source_keyless (brand_no, source_system, keyless_slot),
    CONSTRAINT fk_brand_source_catalog FOREIGN KEY (brand_no) REFERENCES BRAND (no),
    CONSTRAINT ck_brand_source_key CHECK (external_key IS NULL OR (
        CHAR_LENGTH(external_key) > 0
        AND NOT REGEXP_LIKE(external_key, '^[[:space:]]|[[:space:]]$|[[:cntrl:]]', 'c')
    )),
    CONSTRAINT ck_brand_source_guide CHECK (source_system <> 'SAKE_GUIDE' OR external_key IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
