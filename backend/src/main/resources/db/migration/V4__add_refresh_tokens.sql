CREATE TABLE AUTH_TOKEN_FAMILY (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    member_no BIGINT NOT NULL,
    -- Unix seconds keep expiry independent of JDBC/MySQL session time zones.
    expires_at_epoch BIGINT NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT atf_member_fk FOREIGN KEY (member_no) REFERENCES MEMBER(no),
    INDEX atf_expiry_idx (expires_at_epoch)
) ENGINE=InnoDB;

CREATE TABLE AUTH_REFRESH_TOKEN (
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    family_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT art_family_fk FOREIGN KEY (family_id) REFERENCES AUTH_TOKEN_FAMILY(id) ON DELETE CASCADE
) ENGINE=InnoDB;
