ALTER TABLE SUBMISSION_REVISION
    ADD UNIQUE KEY uq_revision_attachment (submission_id, revision_no, attachment_id);
ALTER TABLE SUBMISSION_ATTACHMENT
    ADD UNIQUE KEY uq_attachment_submission_asset (id, submission_id, asset_id);
ALTER TABLE SUBMISSION_APPROVAL
    ADD UNIQUE KEY uq_approval_submission_sake (submission_id, sake_no);

CREATE TABLE SUBMISSION_IMAGE_APPROVAL_JOB (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    submission_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_no INT NOT NULL,
    expected_version BIGINT NOT NULL CHECK (expected_version >= 0),
    actor_no BIGINT NOT NULL,
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(500) NOT NULL,
    attachment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rights_event_id BIGINT NOT NULL UNIQUE,
    staging_object_id BIGINT NOT NULL,
    staging_attempt BIGINT NOT NULL CHECK (staging_attempt > 0),
    delivery_object_id BIGINT NULL,
    state ENUM('PENDING','PROCESSING','RETRYABLE_FAILURE','REQUIRES_REVIEW','COMPLETED','CANCELLED') NOT NULL DEFAULT 'PENDING',
    generation BIGINT NOT NULL DEFAULT 0 CHECK (generation >= 0),
    attempts INT NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 4),
    due_at DATETIME(6) NULL DEFAULT (UTC_TIMESTAMP(6)),
    failure_code VARCHAR(48) CHARACTER SET ascii NULL,
    last_attempt_at DATETIME(6) NULL,
    result_sake_no BIGINT NULL,
    cancelled_by BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    active_submission CHAR(36) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS
        (CASE WHEN state IN ('PENDING','PROCESSING','RETRYABLE_FAILURE') THEN submission_id ELSE NULL END) STORED,
    UNIQUE (active_submission),
    UNIQUE (actor_no, request_key),
    INDEX idx_image_approval_poll (state, due_at, id),
    INDEX idx_image_approval_submission (submission_id, id),
    FOREIGN KEY (submission_id, revision_no, attachment_id)
        REFERENCES SUBMISSION_REVISION(submission_id, revision_no, attachment_id),
    FOREIGN KEY (attachment_id, submission_id, asset_id)
        REFERENCES SUBMISSION_ATTACHMENT(id, submission_id, asset_id),
    FOREIGN KEY (actor_no) REFERENCES MEMBER(no),
    FOREIGN KEY (cancelled_by) REFERENCES MEMBER(no),
    FOREIGN KEY (asset_id, rights_event_id) REFERENCES IMAGE_RIGHTS_EVENT(asset_id, id),
    FOREIGN KEY (asset_id, staging_object_id) REFERENCES IMAGE_OBJECT(asset_id, id),
    FOREIGN KEY (asset_id, delivery_object_id) REFERENCES IMAGE_OBJECT(asset_id, id),
    FOREIGN KEY (submission_id, result_sake_no) REFERENCES SUBMISSION_APPROVAL(submission_id, sake_no),
    CHECK ((state IN ('PENDING','PROCESSING','RETRYABLE_FAILURE')) = (due_at IS NOT NULL)),
    CHECK ((state = 'COMPLETED') = (result_sake_no IS NOT NULL)),
    CHECK ((state = 'CANCELLED') = (cancelled_by IS NOT NULL)),
    CHECK (state <> 'COMPLETED' OR delivery_object_id IS NOT NULL),
    CHECK (state NOT IN ('PROCESSING','RETRYABLE_FAILURE','COMPLETED') OR attempts > 0),
    CHECK (generation >= attempts),
    CHECK ((attempts = 0) = (last_attempt_at IS NULL)),
    CHECK ((state IN ('RETRYABLE_FAILURE','REQUIRES_REVIEW','CANCELLED')) = (failure_code IS NOT NULL))
) ENGINE=InnoDB;
