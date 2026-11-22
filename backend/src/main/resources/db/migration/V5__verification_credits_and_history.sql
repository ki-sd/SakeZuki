-- 기존 회원과 공용 추천 캐시는 유지한다. 별도 가입 상태가 없으면 본인확인 대기 회원이다.
CREATE TABLE MEMBER_VERIFICATION (
    member_no BIGINT PRIMARY KEY,
    terms_version VARCHAR(50) NOT NULL,
    privacy_version VARCHAR(50) NOT NULL,
    consented_at_epoch BIGINT NOT NULL,
    verified_at_epoch BIGINT,
    adult BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY (member_no) REFERENCES MEMBER(no)
) ENGINE=InnoDB;

-- 원문 CI/DI 대신 서버 비밀키 HMAC만 보관한다. 사업자 간 동일한 식별 범위 계약이 필요하다.
CREATE TABLE IDENTITY_CLAIM (
    identity_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    member_no BIGINT NOT NULL UNIQUE,
    created_at_epoch BIGINT NOT NULL,
    FOREIGN KEY (member_no) REFERENCES MEMBER(no)
) ENGINE=InnoDB;

CREATE TABLE IDENTITY_CHALLENGE (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    member_no BIGINT NOT NULL,
    provider_reference VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    expires_at_epoch BIGINT NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY (member_no) REFERENCES MEMBER(no),
    INDEX (expires_at_epoch)
) ENGINE=InnoDB;

CREATE TABLE CREDIT_ACCOUNT (
    member_no BIGINT PRIMARY KEY,
    balance BIGINT NOT NULL DEFAULT 0,
    CHECK (balance >= 0),
    FOREIGN KEY (member_no) REFERENCES MEMBER(no)
) ENGINE=InnoDB;

CREATE TABLE RECOMMEND_REQUEST (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    member_no BIGINT NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(10) NOT NULL,
    input_value VARCHAR(200),
    status VARCHAR(20) NOT NULL,
    result_snapshot LONGTEXT,
    created_at_epoch BIGINT NOT NULL,
    completed_at_epoch BIGINT,
    expires_at_epoch BIGINT,
    UNIQUE (member_no, idempotency_key),
    FOREIGN KEY (member_no) REFERENCES MEMBER(no),
    CHECK (status IN ('RESERVED', 'COMPLETED', 'FAILED', 'TIMED_OUT')),
    INDEX (status, created_at_epoch),
    INDEX (member_no, completed_at_epoch)
) ENGINE=InnoDB;

-- 예약 시 가용 잔액에서 차감한다. 확정은 0, 실패 복구는 +1로 기록해 중복 환불을 막는다.
CREATE TABLE CREDIT_LEDGER (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_no BIGINT NOT NULL,
    reference_id VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(20) NOT NULL,
    delta BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    created_at_epoch BIGINT NOT NULL,
    UNIQUE (member_no, reference_id, kind),
    FOREIGN KEY (member_no) REFERENCES MEMBER(no),
    INDEX (member_no, id)
) ENGINE=InnoDB;
