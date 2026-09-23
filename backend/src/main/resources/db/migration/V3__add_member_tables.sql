CREATE TABLE `member`(
    `no` BIGINT AUTO_INCREMENT,
    `email` VARCHAR(255) NOT NULL,
    `password` VARCHAR(255),
    `name` VARCHAR(50) NOT NULL,
    `address1` VARCHAR(1000) NOT NULL,
    `address2` VARCHAR(1000),
    `phone` VARCHAR(20) NOT NULL,
    `status` VARCHAR(10) DEFAULT 'ACTIVE' NOT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT mem_no_pk PRIMARY KEY (`no`),
    CONSTRAINT mem_email_uk UNIQUE (`email`),
    CONSTRAINT mem_phone_uk UNIQUE (`phone`)
)ENGINE=InnoDB
 DEFAULT CHARSET=utf8mb4
 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `social_account`(
    `no` BIGINT AUTO_INCREMENT,
    `mno` BIGINT NOT NULL,
    `provider` VARCHAR(100) NOT NULL,
    `provider_user_id` VARCHAR(100) NOT NULL,

    CONSTRAINT sa_no_pk PRIMARY KEY (`no`),
    CONSTRAINT sa_mno_fk FOREIGN KEY (`mno`)
        REFERENCES `member`(`no`),
    CONSTRAINT sa_pro_uk UNIQUE (`provider`,`provider_user_id`)
)ENGINE=InnoDB
 DEFAULT CHARSET=utf8mb4
 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `member_role`(
    `no` BIGINT AUTO_INCREMENT,
    `mno` BIGINT NOT NULL,
    `role` VARCHAR(20) DEFAULT 'ROLE_USER' NOT NULL,

    CONSTRAINT mr_no_pk PRIMARY KEY (`no`),
    CONSTRAINT mr_mno_fk FOREIGN KEY (`mno`)
        REFERENCES `member`(`no`),
    CONSTRAINT mr_mr_uk UNIQUE (`mno`,`role`)
)ENGINE=InnoDB
 DEFAULT CHARSET=utf8mb4
 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `member_grade`(
    `no` BIGINT AUTO_INCREMENT,
    `mno` BIGINT NOT NULL,
    `grade` VARCHAR(20) DEFAULT '일반회원' NOT NULL,

    CONSTRAINT mg_no_pk PRIMARY KEY (`no`),
    CONSTRAINT mg_mno_fk FOREIGN KEY (`mno`)
        REFERENCES `member`(`no`),
    CONSTRAINT mg_gr_uk UNIQUE (`mno`)
)ENGINE=InnoDB
 DEFAULT CHARSET=utf8mb4
 COLLATE=utf8mb4_0900_ai_ci;
