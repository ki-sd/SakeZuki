CREATE TABLE SUBMISSION_REVIEW_DECISION (
    actor_no BIGINT NOT NULL,
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    submission_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_no INT NOT NULL,
    decision ENUM('REQUEST_REVISION','REJECT') NOT NULL,
    result_version BIGINT NOT NULL CHECK (result_version > 0),
    reason VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (actor_no, request_key),
    FOREIGN KEY (actor_no) REFERENCES MEMBER(no),
    FOREIGN KEY (submission_id, revision_no) REFERENCES SUBMISSION_REVISION(submission_id, revision_no)
);
