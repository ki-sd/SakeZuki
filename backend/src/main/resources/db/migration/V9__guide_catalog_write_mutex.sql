-- Tools acquires this row before writing a Guide catalog row or identity.
CREATE TABLE GUIDE_CATALOG_WRITE_MUTEX (
    id TINYINT NOT NULL PRIMARY KEY,
    CONSTRAINT ck_guide_catalog_write_mutex_singleton CHECK (id = 1)
) ENGINE=InnoDB;
INSERT INTO GUIDE_CATALOG_WRITE_MUTEX (id) VALUES (1);
