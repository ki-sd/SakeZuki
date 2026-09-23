-- =========================================================
-- V1__initial_schema.sql
-- SakeZuki Initial Schema
-- =========================================================


-- =========================================================
-- BREWERY
-- =========================================================

CREATE TABLE `BREWERY` (
                           `no` bigint NOT NULL AUTO_INCREMENT,
                           `source_id` varchar(255) NOT NULL,
                           `name_ja` varchar(500) NOT NULL,
                           `name_kana` varchar(500) DEFAULT NULL,
                           `name_ko` varchar(1000) DEFAULT NULL,
                           `corporation_name` varchar(1000) DEFAULT NULL,
                           `founded_year` varchar(100) DEFAULT NULL,
                           `ceo` varchar(100) DEFAULT NULL,
                           `prefecture` varchar(100) DEFAULT NULL,
                           `address` varchar(1000) DEFAULT NULL,
                           `post` varchar(20) DEFAULT NULL,
                           `phone` varchar(50) DEFAULT NULL,
                           `fax` varchar(50) DEFAULT NULL,
                           `email` varchar(255) DEFAULT NULL,
                           `website` varchar(500) DEFAULT NULL,
                           `tour_available` char(1) DEFAULT 'N',
                           `latitude` decimal(10,7) DEFAULT NULL,
                           `longitude` decimal(10,7) DEFAULT NULL,
                           `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
                           `updated_at` datetime DEFAULT CURRENT_TIMESTAMP
                               ON UPDATE CURRENT_TIMESTAMP,
                           `corporation_name_ko` varchar(500) DEFAULT NULL,
                           `address_ko` varchar(1000) DEFAULT NULL,

                           PRIMARY KEY (`no`),
                           UNIQUE KEY `br_sid_uq` (`source_id`),
                           KEY `idx_brewery_prefecture` (`prefecture`),

                           CONSTRAINT `br_tour_ck`
                               CHECK (`tour_available` IN ('Y', 'N'))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;


-- =========================================================
-- BRAND
-- =========================================================

CREATE TABLE `BRAND` (
                         `no` bigint NOT NULL AUTO_INCREMENT,
                         `source_id` varchar(255) NOT NULL,
                         `brewery_no` bigint NOT NULL,
                         `name_ja` varchar(500) NOT NULL,
                         `name_kana` varchar(500) DEFAULT NULL,
                         `name_ko` varchar(1000) DEFAULT NULL,
                         `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
                         `updated_at` datetime DEFAULT CURRENT_TIMESTAMP
                             ON UPDATE CURRENT_TIMESTAMP,

                         PRIMARY KEY (`no`),
                         UNIQUE KEY `bd_sid_uq` (`source_id`),
                         KEY `idx_brand_brewery` (`brewery_no`),

                         CONSTRAINT `bd_brno_fk`
                             FOREIGN KEY (`brewery_no`)
                                 REFERENCES `BREWERY` (`no`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;


-- =========================================================
-- SAKE
-- =========================================================

CREATE TABLE `SAKE` (
                        `no` bigint NOT NULL AUTO_INCREMENT,
                        `source_id` bigint NOT NULL,
                        `brewery_no` bigint NOT NULL,
                        `brand_no` bigint DEFAULT NULL,
                        `name_ja` varchar(500) NOT NULL,
                        `name_kana` varchar(500) DEFAULT NULL,
                        `name_ko` varchar(1000) DEFAULT NULL,
                        `sake_type` varchar(100) DEFAULT NULL,
                        `rice` varchar(500) DEFAULT NULL,
                        `polishing_ratio` varchar(100) DEFAULT NULL,
                        `yeast` varchar(500) DEFAULT NULL,
                        `sake_meter_value` varchar(100) DEFAULT NULL,
                        `acidity` varchar(100) DEFAULT NULL,
                        `alcohol_percentage` varchar(100) DEFAULT NULL,
                        `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
                        `updated_at` datetime DEFAULT CURRENT_TIMESTAMP
                            ON UPDATE CURRENT_TIMESTAMP,
                        `image_url` varchar(1000) DEFAULT NULL,
                        `image_checked` char(1) DEFAULT 'N',
                        `rice_ko` varchar(500) DEFAULT NULL,
                        `yeast_ko` varchar(500) DEFAULT NULL,

                        PRIMARY KEY (`no`),
                        UNIQUE KEY `sk_sid_uq` (`source_id`),

                        KEY `idx_sake_brewery` (`brewery_no`),
                        KEY `idx_sake_brand` (`brand_no`),
                        KEY `idx_sake_type` (`sake_type`),
                        KEY `idx_sake_name_ja` (`name_ja`),

                        CONSTRAINT `sk_bdno_fk`
                            FOREIGN KEY (`brand_no`)
                                REFERENCES `BRAND` (`no`),

                        CONSTRAINT `sk_brno_fk`
                            FOREIGN KEY (`brewery_no`)
                                REFERENCES `BREWERY` (`no`),

                        CONSTRAINT `sk_img_ck`
                            CHECK (`image_checked` IN ('Y', 'N'))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;


-- =========================================================
-- SAKE_RECOMMEND_PROFILE
-- =========================================================

CREATE TABLE `SAKE_RECOMMEND_PROFILE` (
                                          `sake_no` bigint NOT NULL,
                                          `sake_meter_min` decimal(6,1) DEFAULT NULL,
                                          `sake_meter_max` decimal(6,1) DEFAULT NULL,
                                          `acidity_min` decimal(5,1) DEFAULT NULL,
                                          `acidity_max` decimal(5,1) DEFAULT NULL,
                                          `polishing_ratio_min` decimal(5,1) DEFAULT NULL,
                                          `polishing_ratio_max` decimal(5,1) DEFAULT NULL,

                                          PRIMARY KEY (`sake_no`),

                                          CONSTRAINT `srp_sake_fk`
                                              FOREIGN KEY (`sake_no`)
                                                  REFERENCES `SAKE` (`no`)
                                                  ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;


-- =========================================================
-- RECOMMEND_FOOD
-- =========================================================

CREATE TABLE `RECOMMEND_FOOD` (
                                  `no` bigint NOT NULL AUTO_INCREMENT,
                                  `sake_no` bigint NOT NULL,
                                  `name` varchar(100) NOT NULL,
                                  `reason` text NOT NULL,
                                  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                  PRIMARY KEY (`no`),
                                  KEY `rf_sno_fk` (`sake_no`),

                                  CONSTRAINT `rf_sno_fk`
                                      FOREIGN KEY (`sake_no`)
                                          REFERENCES `SAKE` (`no`)
                                          ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;


-- =========================================================
-- RECOMMEND_SAKE
-- =========================================================

CREATE TABLE `RECOMMEND_SAKE` (
                                  `no` bigint NOT NULL AUTO_INCREMENT,
                                  `food` varchar(200) NOT NULL,
                                  `sake_no` bigint NOT NULL,
                                  `reason` text NOT NULL,
                                  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                  PRIMARY KEY (`no`),

                                  KEY `rs_sake_fk` (`sake_no`),
                                  KEY `idx_recommend_sake_food` (`food`),

                                  CONSTRAINT `rs_sake_fk`
                                      FOREIGN KEY (`sake_no`)
                                          REFERENCES `SAKE` (`no`)
                                          ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;