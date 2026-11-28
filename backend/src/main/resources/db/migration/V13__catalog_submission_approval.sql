ALTER TABLE BREWERY
    ADD COLUMN approval_name_key BINARY(32) NULL,
    ADD UNIQUE KEY uq_brewery_approval_name (approval_name_key);

ALTER TABLE BRAND
    ADD COLUMN approval_name_key BINARY(32) NULL,
    ADD UNIQUE KEY uq_brand_approval_name (brewery_no, approval_name_key);

ALTER TABLE SAKE
    ADD COLUMN approval_name_key BINARY(32) NULL,
    ADD UNIQUE KEY uq_sake_approval_name (brewery_no, approval_name_key),
    ADD COLUMN capacity_ml INT NULL CHECK (capacity_ml BETWEEN 1 AND 100000),
    ADD COLUMN variant VARCHAR(500) NULL;

ALTER TABLE SAKE_RECOMMEND_PROFILE
    MODIFY sake_meter_min DECIMAL(8,3) NULL,
    MODIFY sake_meter_max DECIMAL(8,3) NULL,
    MODIFY acidity_min DECIMAL(7,3) NULL,
    MODIFY acidity_max DECIMAL(7,3) NULL,
    MODIFY polishing_ratio_min DECIMAL(7,3) NULL,
    MODIFY polishing_ratio_max DECIMAL(7,3) NULL;

CREATE TABLE SUBMISSION_APPROVAL (
    submission_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    revision_no INT NOT NULL,
    sake_no BIGINT NOT NULL UNIQUE,
    actor_no BIGINT NOT NULL,
    expected_version BIGINT NOT NULL CHECK (expected_version >= 0),
    result_version BIGINT NOT NULL,
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    UNIQUE (actor_no, request_key),
    CHECK (result_version = expected_version + 1),
    FOREIGN KEY (submission_id, revision_no) REFERENCES SUBMISSION_REVISION(submission_id, revision_no),
    FOREIGN KEY (sake_no) REFERENCES SAKE(no),
    FOREIGN KEY (actor_no) REFERENCES MEMBER(no)
) ENGINE=InnoDB;
