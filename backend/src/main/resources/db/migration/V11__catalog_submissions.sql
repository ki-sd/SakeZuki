CREATE TABLE CATALOG_SUBMISSION (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    owner_no BIGINT NOT NULL,
    request_kind ENUM('CREATE_SAKE') NOT NULL DEFAULT 'CREATE_SAKE',
    current_revision_no INT NULL,
    status ENUM('PENDING','NEEDS_REVISION','REJECTED','CANCELLED','APPROVED') NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    creation_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    UNIQUE (owner_no, creation_key),
    UNIQUE (id, owner_no),
    INDEX (owner_no, created_at, id),
    INDEX (owner_no, status),
    FOREIGN KEY (owner_no) REFERENCES MEMBER(no)
);

CREATE TABLE SUBMISSION_ATTACHMENT (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    owner_no BIGINT NOT NULL,
    submission_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reserved_bytes INT NOT NULL CHECK (reserved_bytes BETWEEN 0 AND 12582912),
    status ENUM('UPLOADING','RETRYABLE_FAILURE','INVALID','READY') NOT NULL,
    generation BIGINT NOT NULL CHECK (generation > 0),
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    expires_at DATETIME(6) NOT NULL,
    UNIQUE (owner_no, request_key),
    UNIQUE (id, submission_id),
    UNIQUE (asset_id),
    INDEX (owner_no, created_at),
    FOREIGN KEY (owner_no) REFERENCES MEMBER(no),
    FOREIGN KEY (submission_id, owner_no) REFERENCES CATALOG_SUBMISSION(id, owner_no),
    FOREIGN KEY (asset_id) REFERENCES IMAGE_ASSET(id),
    CHECK ((status = 'UPLOADING') = (lease_until IS NOT NULL)),
    CHECK ((status = 'READY' AND asset_id IS NOT NULL AND asset_id = id)
        OR (status <> 'READY' AND asset_id IS NULL)),
    CHECK (submission_id IS NULL OR status = 'READY')
);

CREATE TABLE SUBMISSION_REVISION (
    submission_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_no INT NOT NULL CHECK (revision_no > 0),
    parent_revision_no INT NULL,
    author_no BIGINT NOT NULL,
    author_role ENUM('MEMBER','ADMIN') NOT NULL,
    revision_kind ENUM('INITIAL','MEMBER_REVISION','ADMIN_REVISION') NOT NULL,
    schema_version SMALLINT NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    payload JSON NOT NULL,
    attachment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (submission_id, revision_no),
    FOREIGN KEY (submission_id) REFERENCES CATALOG_SUBMISSION(id),
    FOREIGN KEY (submission_id, parent_revision_no) REFERENCES SUBMISSION_REVISION(submission_id, revision_no),
    FOREIGN KEY (author_no) REFERENCES MEMBER(no),
    FOREIGN KEY (attachment_id, submission_id) REFERENCES SUBMISSION_ATTACHMENT(id, submission_id),
    CHECK ((revision_no = 1 AND parent_revision_no IS NULL AND revision_kind = 'INITIAL')
        OR (revision_no > 1 AND parent_revision_no IS NOT NULL AND parent_revision_no = revision_no - 1
            AND revision_kind <> 'INITIAL'))
);

ALTER TABLE CATALOG_SUBMISSION ADD CONSTRAINT submission_current_revision_fk
    FOREIGN KEY (id, current_revision_no) REFERENCES SUBMISSION_REVISION(submission_id, revision_no);

CREATE TABLE SUBMISSION_EVENT (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    submission_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_no INT NOT NULL,
    actor_no BIGINT NOT NULL,
    actor_role ENUM('MEMBER','ADMIN') NOT NULL,
    previous_status ENUM('PENDING','NEEDS_REVISION','REJECTED','CANCELLED','APPROVED') NULL,
    next_status ENUM('PENDING','NEEDS_REVISION','REJECTED','CANCELLED','APPROVED') NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    INDEX (submission_id, id),
    INDEX (actor_no, created_at),
    FOREIGN KEY (submission_id, revision_no) REFERENCES SUBMISSION_REVISION(submission_id, revision_no),
    FOREIGN KEY (actor_no) REFERENCES MEMBER(no)
);

CREATE VIEW UGC_VERIFIED_STAGING AS
SELECT a.id AS asset_id, o.id AS object_id, o.attempt, o.object_key, a.transformed_mime AS mime,
       a.transformed_bytes AS bytes, a.transformed_width AS width, a.transformed_height AS height,
       a.transformed_sha256 AS sha256
FROM IMAGE_ASSET a
JOIN IMAGE_OBJECT o ON o.asset_id = a.id
WHERE a.source_type = 'USER_SUBMISSION' AND o.storage_area = 'STAGING' AND o.storage_state = 'VERIFIED'
  AND o.stored_bytes = a.transformed_bytes AND o.stored_sha256 = a.transformed_sha256
  AND o.object_version = a.transform_version
  AND o.object_key = CONCAT('asset/', a.id, '/', a.transform_version, '/', a.transformed_sha256,
      IF(a.transformed_mime = 'image/png', '.png', '.jpg'))
  AND NOT EXISTS (SELECT 1 FROM IMAGE_RIGHTS_EVENT r WHERE r.asset_id = a.id
        AND r.id = (SELECT MAX(h.id) FROM IMAGE_RIGHTS_EVENT h WHERE h.asset_id = a.id)
        AND r.decision IN ('DENIED','REVOKED'));
