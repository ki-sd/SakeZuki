CREATE TABLE IMAGE_ASSET (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    source_type ENUM('SAKE_GUIDE','USER_SUBMISSION','ADMIN','BREWERY_OFFICIAL') NOT NULL,
    evidence_ref VARCHAR(500) NOT NULL,
    original_mime ENUM('image/jpeg','image/png') NOT NULL,
    original_bytes INT NOT NULL CHECK (original_bytes BETWEEN 1 AND 12582912),
    original_width INT NOT NULL CHECK (original_width BETWEEN 1 AND 8192),
    original_height INT NOT NULL CHECK (original_height BETWEEN 1 AND 8192),
    original_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transformed_mime ENUM('image/jpeg','image/png') NOT NULL,
    transformed_bytes INT NOT NULL CHECK (transformed_bytes BETWEEN 1 AND 12582912),
    transformed_width INT NOT NULL CHECK (transformed_width BETWEEN 1 AND 1200),
    transformed_height INT NOT NULL CHECK (transformed_height BETWEEN 1 AND 1200),
    transformed_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transform_version VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    processing_state ENUM('TRANSFORMED') NOT NULL DEFAULT 'TRANSFORMED',
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    CHECK (original_width * original_height <= 16000000),
    CHECK (REGEXP_LIKE(original_sha256, '^[a-f0-9]{64}$', 'c')),
    CHECK (REGEXP_LIKE(transformed_sha256, '^[a-f0-9]{64}$', 'c')),
    CHECK (REGEXP_LIKE(transform_version, '^image-v[1-9][0-9]{0,8}$', 'c'))
);

CREATE TABLE IMAGE_OBJECT (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    storage_area ENUM('STAGING','DELIVERY') NOT NULL,
    object_key VARCHAR(140) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_version VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stored_bytes INT NULL CHECK (stored_bytes BETWEEN 1 AND 12582912),
    stored_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    storage_state ENUM('PENDING','RETRYABLE_FAILURE','PERMANENT_FAILURE','VERIFIED') NOT NULL DEFAULT 'PENDING',
    attempt BIGINT NOT NULL DEFAULT 0,
    failure_code VARCHAR(40) NULL,
    verified_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    UNIQUE (storage_area, object_key),
    UNIQUE (asset_id, storage_area),
    UNIQUE (asset_id, id),
    FOREIGN KEY (asset_id) REFERENCES IMAGE_ASSET(id),
    CHECK (REGEXP_LIKE(object_key, '^asset/[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}/image-v[1-9][0-9]{0,8}/[a-f0-9]{64}[.](jpg|png)$', 'c')),
    CHECK (REGEXP_LIKE(object_version, '^image-v[1-9][0-9]{0,8}$', 'c')),
    CHECK ((storage_state = 'VERIFIED' AND verified_at IS NOT NULL AND stored_bytes IS NOT NULL
            AND stored_sha256 IS NOT NULL AND REGEXP_LIKE(stored_sha256, '^[a-f0-9]{64}$', 'c') AND failure_code IS NULL)
        OR (storage_state <> 'VERIFIED' AND verified_at IS NULL AND stored_bytes IS NULL AND stored_sha256 IS NULL
            AND ((storage_state = 'PENDING' AND failure_code IS NULL)
              OR (storage_state IN ('RETRYABLE_FAILURE','PERMANENT_FAILURE') AND failure_code IS NOT NULL))))
);

CREATE TABLE IMAGE_RIGHTS_EVENT (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision ENUM('GRANTED','DENIED','REVOKED') NOT NULL,
    scope ENUM('PUBLIC_WEB') NOT NULL,
    evidence_ref VARCHAR(500) NOT NULL,
    decided_by VARCHAR(200) NOT NULL,
    decided_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    valid_from DATETIME(6) NULL,
    valid_until DATETIME(6) NULL,
    FOREIGN KEY (asset_id) REFERENCES IMAGE_ASSET(id),
    UNIQUE (asset_id, id),
    CHECK ((decision = 'GRANTED' AND valid_from IS NOT NULL AND (valid_until IS NULL OR valid_until > valid_from))
        OR (decision <> 'GRANTED' AND valid_from IS NULL AND valid_until IS NULL))
);

CREATE TABLE SAKE_IMAGE (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sake_no BIGINT NOT NULL,
    asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_id BIGINT NOT NULL,
    rights_event_id BIGINT NOT NULL,
    is_primary BOOLEAN NOT NULL,
    linked_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    withdrawn_at DATETIME(6) NULL,
    active_primary_sake BIGINT GENERATED ALWAYS AS
        (CASE WHEN is_primary = TRUE AND withdrawn_at IS NULL THEN sake_no ELSE NULL END) STORED,
    UNIQUE (active_primary_sake),
    INDEX (sake_no, is_primary),
    FOREIGN KEY (sake_no) REFERENCES SAKE(no),
    FOREIGN KEY (asset_id, object_id) REFERENCES IMAGE_OBJECT(asset_id, id),
    FOREIGN KEY (asset_id, rights_event_id) REFERENCES IMAGE_RIGHTS_EVENT(asset_id, id)
);

-- Current DB time, current grant and byte metadata govern publication and HTTP delivery.
CREATE VIEW IMAGE_CURRENT_PUBLIC_GRANT AS
SELECT r.id, r.asset_id
FROM IMAGE_RIGHTS_EVENT r
WHERE r.id = (SELECT MAX(history.id) FROM IMAGE_RIGHTS_EVENT history WHERE history.asset_id = r.asset_id)
  AND r.decision = 'GRANTED' AND r.scope = 'PUBLIC_WEB'
  AND r.valid_from <= UTC_TIMESTAMP(6) AND (r.valid_until IS NULL OR r.valid_until > UTC_TIMESTAMP(6));

CREATE VIEW IMAGE_DELIVERY_CANDIDATE AS
SELECT a.id AS asset_id, o.id AS object_id, r.id AS rights_event_id, o.object_key, o.attempt,
       a.transformed_mime AS mime, a.transformed_bytes AS byte_length,
       a.transformed_sha256 AS sha256, a.transformed_width AS width, a.transformed_height AS height
FROM IMAGE_ASSET a
JOIN IMAGE_OBJECT o ON o.asset_id = a.id
JOIN IMAGE_CURRENT_PUBLIC_GRANT r ON r.asset_id = a.id
WHERE o.storage_area = 'DELIVERY' AND o.storage_state = 'VERIFIED'
  AND o.stored_bytes = a.transformed_bytes AND o.stored_sha256 = a.transformed_sha256
  AND o.object_version = a.transform_version
  AND o.object_key = CONCAT('asset/', a.id, '/', a.transform_version, '/', a.transformed_sha256,
      IF(a.transformed_mime = 'image/png', '.png', '.jpg'));

CREATE VIEW PUBLIC_SAKE_IMAGE AS
SELECT si.id AS link_id, si.active_primary_sake, si.sake_no, si.asset_id,
       d.object_id, d.attempt, d.object_key, d.mime, d.byte_length, d.sha256, d.width, d.height,
       CONCAT('/api/images/sakes/', si.sake_no, '/', si.asset_id) AS image_url
FROM SAKE_IMAGE si
JOIN IMAGE_DELIVERY_CANDIDATE d ON si.asset_id = d.asset_id AND si.object_id = d.object_id
  AND si.rights_event_id = d.rights_event_id
WHERE si.active_primary_sake IS NOT NULL;
