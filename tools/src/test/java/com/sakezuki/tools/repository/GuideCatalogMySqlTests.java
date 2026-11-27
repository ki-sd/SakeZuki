package com.sakezuki.tools.repository;

import com.sakezuki.tools.model.BrandData;
import com.sakezuki.tools.model.BreweryData;
import com.sakezuki.tools.model.SakeData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql-integration")
class GuideCatalogMySqlTests {
    private static final long GUIDE_ID = 7_000_000_001L;

    @Test
    void recrawlRetainsInternalIdsAndOriginalGuideKeySpelling() throws Exception {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try {
                var breweries = new BreweryRepository(connection);
                var brands = new BrandRepository(connection);
                var sakes = new SakeRepository(connection);
                var brewery = BreweryData.builder().sourceId("CatalogTestBrewery").nameJa("合成蔵").build();
                var brand = BrandData.builder().sourceId("CatalogTestBrand").nameJa("合成銘柄").build();
                var sake = SakeData.builder().sourceId(GUIDE_ID).nameJa("合成酒").build();
                long breweryNo = breweries.save(brewery);
                long brandNo = brands.save(brand, breweryNo);
                long sakeNo = sakes.save(sake, breweryNo, brandNo);

                brewery.setSourceId("catalogtestbrewery");
                brand.setSourceId("catalogtestbrand");
                sake.setNameJa("合成酒更新");
                assertEquals(breweryNo, breweries.save(brewery));
                assertEquals(brandNo, brands.save(brand, breweryNo));
                assertEquals(sakeNo, sakes.save(sake, breweryNo, brandNo));
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM BREWERY_SOURCE WHERE external_key='CatalogTestBrewery'"));
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM BRAND_SOURCE WHERE external_key='CatalogTestBrand'"));
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM SAKE_SOURCE WHERE external_key='7000000001'"));
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM SAKE WHERE source_id=7000000001 AND name_ja='合成酒更新'"));
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void sourceLessRowsAreExcludedFromGuideImageTranslationAndCleanup() throws Exception {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "INSERT INTO BREWERY(name_ja) VALUES('UserFixtureBrewery')");
                execute(connection, """
                        INSERT INTO BRAND(brewery_no,name_ja)
                        SELECT no,'UserFixtureBrand' FROM BREWERY WHERE name_ja='UserFixtureBrewery'
                        """);
                execute(connection, """
                        INSERT INTO SAKE(brewery_no,brand_no,name_ja,rice,yeast)
                        SELECT brewery_no,no,'SameNameCatalogFixture','CatalogFixtureRice','CatalogFixtureYeast'
                        FROM BRAND WHERE name_ja='UserFixtureBrand'
                        """);
                long userNo = scalar(connection, "SELECT no FROM SAKE WHERE name_ja='SameNameCatalogFixture'");
                execute(connection, "INSERT INTO SAKE_SOURCE(sake_no, source_system) VALUES(" + userNo + ",'USER_SUBMISSION')");
                long guideBreweryNo = new BreweryRepository(connection).save(
                        BreweryData.builder().sourceId("CatalogTestBrewery").nameJa("GuideFixtureBrewery").build());
                long guideBrandNo = new BrandRepository(connection).save(
                        BrandData.builder().sourceId("CatalogTestBrand").nameJa("GuideFixtureBrand").build(), guideBreweryNo);
                var sakes = new SakeRepository(connection);
                long guideNo = sakes.save(SakeData.builder().sourceId(GUIDE_ID).nameJa("SameNameCatalogFixture")
                        .rice("CatalogFixtureRice").yeast("CatalogFixtureYeast").build(), guideBreweryNo, guideBrandNo);
                var translations = new TranslationRepository(connection);
                assertTrue(sakes.findUncheckedImageSourceIds().contains(GUIDE_ID));
                assertFalse(sakes.findUncheckedImageSourceIds().contains(0L));
                sakes.updateImageUrl(GUIDE_ID, "https://example.invalid/test.png");
                assertFalse(sakes.findUncheckedImageSourceIds().contains(GUIDE_ID));
                assertFalse(translations.findUntranslatedBrands(Integer.MAX_VALUE).stream()
                        .anyMatch(item -> item.getNameJa().equals("UserFixtureBrand")));
                assertTrue(translations.findUntranslatedSakeNames(Integer.MAX_VALUE).stream()
                        .anyMatch(item -> item.getNameJa().equals("SameNameCatalogFixture") && item.getNo() == guideNo));
                translations.updateSakeNameKo("SameNameCatalogFixture", null, "합성");
                translations.updateRiceKo("CatalogFixtureRice", "합성");
                translations.updateYeastKo("CatalogFixtureYeast", "합성");
                translations.replaceRiceValue("CatalogFixtureRice", "GuideRice");
                translations.replaceYeastValue("CatalogFixtureYeast", "GuideYeast");
                assertEquals(1, scalar(connection, """
                        SELECT COUNT(*) FROM SAKE WHERE no=%d AND name_ko IS NULL
                        AND rice_ko IS NULL AND yeast_ko IS NULL AND rice='CatalogFixtureRice'
                        AND yeast='CatalogFixtureYeast' AND image_url IS NULL AND image_checked='N'
                        """.formatted(userNo)));
                assertEquals(1, scalar(connection, """
                        SELECT COUNT(*) FROM SAKE WHERE no=%d AND name_ko='합성'
                        AND rice_ko='합성' AND yeast_ko='합성' AND rice='GuideRice'
                        AND yeast='GuideYeast' AND image_checked='Y'
                        """.formatted(guideNo)));
                long userBrand = scalar(connection, "SELECT no FROM BRAND WHERE name_ja='UserFixtureBrand'");
                translations.updateBrandNameKo(userBrand, "합성");
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM BRAND WHERE no=" + userBrand + " AND name_ko IS NULL"));
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void sourceConstraintsAndConflictingDualWriteRejectWrongOwner() throws Exception {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "INSERT INTO BREWERY(name_ja) VALUES('UserFixtureA'),('UserFixtureB')");
                long first = scalar(connection, "SELECT no FROM BREWERY WHERE name_ja='UserFixtureA'");
                long second = scalar(connection, "SELECT no FROM BREWERY WHERE name_ja='UserFixtureB'");
                String prefix = "INSERT INTO BREWERY_SOURCE(brewery_no, source_system, external_key) VALUES";
                execute(connection, prefix + "(" + first + ",'SAKE_GUIDE','ReservedFixture')");
                execute(connection, prefix + "(" + second + ",'BREWERY_OFFICIAL','ReservedFixture')");
                execute(connection, prefix + "(" + first + ",'USER_SUBMISSION',NULL)");
                execute(connection, prefix + "(" + second + ",'USER_SUBMISSION',NULL)");
                assertThrows(SQLException.class, () -> execute(connection, prefix + "(" + first + ",'USER_SUBMISSION',NULL)"));
                assertThrows(SQLException.class, () -> execute(connection, prefix + "(" + second + ",'SAKE_GUIDE','ReservedFixture')"));
                assertThrows(SQLException.class, () -> execute(connection, prefix + "(0,'ADMIN',NULL)"));
                var conflicting = BreweryData.builder().sourceId("ReservedFixture").nameJa("WrongOwner").build();
                assertThrows(SQLException.class, () -> new BreweryRepository(connection).save(conflicting));
                assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM BREWERY WHERE source_id='ReservedFixture'"));
                assertEquals(first, scalar(connection, "SELECT brewery_no FROM BREWERY_SOURCE WHERE source_system='SAKE_GUIDE' AND external_key='ReservedFixture'"));
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void recrawlSeesGuideMappingCommittedAfterRepeatableReadSnapshot() throws Exception {
        try (var older = connect(); var writer = connect()) {
            older.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            older.setAutoCommit(false);
            long breweryNo = 0;
            try {
                assertEquals(0, scalar(older, "SELECT COUNT(*) FROM BREWERY_SOURCE WHERE external_key='RaceFixture'"));
                var brewery = BreweryData.builder().sourceId("RaceFixture").nameJa("合成競合蔵").build();
                breweryNo = new BreweryRepository(writer).save(brewery);
                assertTrue(writer.getAutoCommit());
                assertEquals(breweryNo, new BreweryRepository(older).save(brewery));
            } finally {
                older.rollback();
                if (breweryNo != 0) {
                    execute(writer, "DELETE FROM BREWERY_SOURCE WHERE brewery_no=" + breweryNo);
                    execute(writer, "DELETE FROM BREWERY WHERE no=" + breweryNo);
                }
            }
        }
    }

    @Test
    void detachedGuideOwnerBlocksCaseVariantAndExactRecrawl() throws Exception {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "INSERT INTO BREWERY(name_ja) VALUES('DetachedFixture')");
                execute(connection, "INSERT INTO BREWERY_SOURCE(brewery_no,source_system,external_key) "
                        + "SELECT no,'SAKE_GUIDE','ReservedCaseFixture' FROM BREWERY WHERE name_ja='DetachedFixture'");
                var repository = new BreweryRepository(connection);
                assertThrows(SQLException.class, () -> repository.save(
                        BreweryData.builder().sourceId("reservedcasefixture").nameJa("UnexpectedOverwrite").build()));
                assertThrows(SQLException.class, () -> repository.save(
                        BreweryData.builder().sourceId("ReservedCaseFixture").nameJa("UnexpectedOverwrite").build()));
                assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM BREWERY WHERE source_id='ReservedCaseFixture'"));
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void requestedKeyWithDifferentOwnerCannotUpdateLegacyMatch() throws Exception {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try {
                var repository = new BreweryRepository(connection);
                repository.save(BreweryData.builder().sourceId("AlphaFixture").nameJa("OriginalFixture").build());
                execute(connection, "INSERT INTO BREWERY(name_ja) VALUES('OtherFixture')");
                execute(connection, "INSERT INTO BREWERY_SOURCE(brewery_no,source_system,external_key) "
                        + "SELECT no,'SAKE_GUIDE','alphafixture' FROM BREWERY WHERE name_ja='OtherFixture'");
                assertThrows(SQLException.class, () -> repository.save(
                        BreweryData.builder().sourceId("alphafixture").nameJa("UnexpectedOverwrite").build()));
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM BREWERY WHERE name_ja='OriginalFixture'"));
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void accentAndKanaVariantsWithDistinctOwnersAreRejectedForBrand() throws Exception {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try {
                long brewery = scalar(connection, "SELECT MIN(no) FROM BREWERY");
                var repository = new BrandRepository(connection);
                for (String[] keys : new String[][]{{"CaféFixture", "CafeFixture"}, {"がFixture", "かFixture"},
                        {"ガUnicodeFixture", "カ\u3099UnicodeFixture"}}) {
                    long owner = repository.save(BrandData.builder().sourceId(keys[0]).nameJa("OriginalBrand").build(), brewery);
                    execute(connection, "INSERT INTO BRAND(brewery_no,name_ja) VALUES(" + brewery + ",'DetachedBrand')");
                    long detached = scalar(connection, "SELECT MAX(no) FROM BRAND");
                    try (var insert = connection.prepareStatement(
                            "INSERT INTO BRAND_SOURCE(brand_no,source_system,external_key) VALUES(?,'SAKE_GUIDE',?)")) {
                        insert.setLong(1, detached);
                        insert.setString(2, keys[1]);
                        assertEquals(1, insert.executeUpdate());
                    }
                    assertThrows(SQLException.class, () -> repository.save(
                            BrandData.builder().sourceId(keys[1]).nameJa("OverwriteBrand").build(), brewery));
                    assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM BRAND WHERE no=" + owner + " AND name_ja='OriginalBrand'"));
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void numericOwnerConflictRollsBackAutocommitAndRestoresConnection() throws Exception {
        try (var writer = connect(); var observer = connect()) {
            long brewery = scalar(writer, "SELECT MIN(no) FROM BREWERY");
            long brand = scalar(writer, "SELECT MIN(no) FROM BRAND WHERE brewery_no=" + brewery);
            execute(writer, "INSERT INTO SAKE(brewery_no,name_ja) VALUES(" + brewery + ",'DetachedSakeFixture')");
            long detached = scalar(writer, "SELECT no FROM SAKE WHERE name_ja='DetachedSakeFixture'");
            try {
                execute(writer, "INSERT INTO SAKE_SOURCE(sake_no,source_system,external_key) VALUES("
                        + detached + ",'SAKE_GUIDE','7000000002')");
                assertThrows(SQLException.class, () -> new SakeRepository(writer)
                        .save(SakeData.builder().sourceId(7_000_000_002L).nameJa("OverwriteSake").build(), brewery, brand));
                assertTrue(writer.getAutoCommit());
                assertEquals(0, scalar(observer, "SELECT COUNT(*) FROM SAKE WHERE source_id=7000000002"));
                assertEquals(detached, scalar(observer, "SELECT sake_no FROM SAKE_SOURCE WHERE external_key='7000000002'"));
            } finally {
                execute(writer, "DELETE FROM SAKE_SOURCE WHERE sake_no=" + detached);
                execute(writer, "DELETE FROM SAKE WHERE no=" + detached);
            }
        }
    }

    @Test
    void invalidKeysAreRejectedByRepositoriesAndDatabaseWithoutChangingSpelling() throws Exception {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try {
                long brewery = scalar(connection, "SELECT MIN(no) FROM BREWERY");
                long brand = scalar(connection, "SELECT MIN(no) FROM BRAND");
                long sake = scalar(connection, "SELECT MIN(no) FROM SAKE");
                for (String key : new String[]{"", "\t", "　", "\u00a0", " PaddedKey ", "　前", "後\u00a0", "内\t部", "内\u0085部"}) {
                    assertThrows(SQLException.class, () -> new BreweryRepository(connection)
                            .save(BreweryData.builder().sourceId(key).nameJa("Invalid").build()));
                    assertThrows(SQLException.class, () -> new BrandRepository(connection)
                            .save(BrandData.builder().sourceId(key).nameJa("Invalid").build(), brewery));
                    for (Object[] fixture : new Object[][]{{"BREWERY", "brewery_no", brewery}, {"BRAND", "brand_no", brand},
                            {"SAKE", "sake_no", sake}}) {
                        try (var insert = connection.prepareStatement("INSERT INTO " + fixture[0] + "_SOURCE("
                                + fixture[1] + ",source_system,external_key) VALUES(?,'ADMIN',?)")) {
                            insert.setLong(1, (Long) fixture[2]);
                            insert.setString(2, key);
                            assertThrows(SQLException.class, insert::executeUpdate);
                        }
                    }
                }
                for (Long invalidId : new Long[]{null, 0L, -1L}) {
                    assertThrows(SQLException.class, () -> new SakeRepository(connection)
                            .save(SakeData.builder().sourceId(invalidId).nameJa("Invalid").build(), brewery, brand));
                }
                new BrandRepository(connection).save(BrandData.builder().sourceId("酒蔵 Café").nameJa("Valid").build(), brewery);
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM BRAND_SOURCE WHERE external_key='酒蔵 Café'"));
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void pendingOwnerClaimSerializesContendingGuideWriteAndKeepsCallerWorkUncommitted() throws Exception {
        try (var owner = connect(); var contender = connect(); var observer = connect();
             var workers = Executors.newSingleThreadExecutor()) {
            execute(owner, "INSERT INTO BREWERY(name_ja) VALUES('QaRaceFixture')");
            long no = scalar(owner, "SELECT no FROM BREWERY WHERE name_ja='QaRaceFixture'");
            try {
                owner.setAutoCommit(false);
                execute(owner, "INSERT INTO BREWERY_SOURCE(brewery_no,source_system,external_key) VALUES("
                        + no + ",'SAKE_GUIDE','QaClaimFixture')");
                contender.setAutoCommit(false);
                execute(contender, "INSERT INTO BREWERY(name_ja) VALUES('QaCallerWork')");
                var starting = new CountDownLatch(1);
                var result = workers.submit(() -> {
                    starting.countDown();
                    return assertThrows(SQLException.class, () -> new BreweryRepository(contender).save(
                            BreweryData.builder().sourceId("qaclaimfixture").nameJa("OverwriteRace").build()));
                });
                assertTrue(starting.await(5, TimeUnit.SECONDS));
                owner.commit();
                assertTrue(result.get(10, TimeUnit.SECONDS).getMessage().contains("conflicts"));
                assertEquals(1, scalar(contender, "SELECT COUNT(*) FROM BREWERY WHERE name_ja='QaCallerWork'"));
                assertEquals(0, scalar(observer, "SELECT COUNT(*) FROM BREWERY WHERE name_ja IN ('QaCallerWork','OverwriteRace')"));
                assertFalse(contender.getAutoCommit());
            } finally {
                owner.rollback();
                contender.rollback();
                owner.setAutoCommit(true);
                execute(owner, "DELETE FROM BREWERY_SOURCE WHERE brewery_no=" + no);
                execute(owner, "DELETE FROM BREWERY WHERE no=" + no);
            }
        }
    }

    @Test
    void previouslyAbsentExactKeyCannotBeClaimedConcurrentlyByAnotherOwner() throws Exception {
        try (var writer = connect(); var contender = connect(); var observer = connect();
             var workers = Executors.newSingleThreadExecutor()) {
            execute(contender, "INSERT INTO BREWERY(name_ja) VALUES('QaOtherOwner')");
            long other = scalar(contender, "SELECT no FROM BREWERY WHERE name_ja='QaOtherOwner'");
            long owner = 0;
            try {
                writer.setAutoCommit(false);
                owner = new BreweryRepository(writer).save(
                        BreweryData.builder().sourceId("QaFreshKey").nameJa("QaFirstOwner").build());
                assertEquals(0, scalar(observer, "SELECT COUNT(*) FROM BREWERY_SOURCE WHERE external_key='QaFreshKey'"));
                var started = new CountDownLatch(1);
                var claim = workers.submit(() -> {
                    started.countDown();
                    return assertThrows(SQLException.class, () -> execute(contender,
                            "INSERT INTO BREWERY_SOURCE(brewery_no,source_system,external_key) VALUES("
                                    + other + ",'SAKE_GUIDE','QaFreshKey')"));
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class, () -> claim.get(150, TimeUnit.MILLISECONDS));
                writer.commit();
                assertEquals(1062, claim.get(10, TimeUnit.SECONDS).getErrorCode());
                assertEquals(owner, scalar(observer, "SELECT brewery_no FROM BREWERY_SOURCE WHERE external_key='QaFreshKey'"));
            } finally {
                writer.rollback();
                writer.setAutoCommit(true);
                execute(writer, "DELETE FROM BREWERY_SOURCE WHERE brewery_no IN (" + owner + "," + other + ")");
                execute(writer, "DELETE FROM BREWERY WHERE no IN (" + owner + "," + other + ")");
            }
        }
    }

    @Test
    void errorInOwnedWriteRollsBackWithoutReplacingOriginalError() throws Exception {
        try (var connection = connect(); var observer = connect()) {
            var failure = new AssertionError("fixture error");
            assertSame(failure, assertThrows(AssertionError.class, () ->
                    GuideCatalogSource.BREWERY.save(connection, "QaErrorFixture", () -> {
                        execute(connection, "INSERT INTO BREWERY(name_ja) VALUES('QaErrorFixture')");
                        throw failure;
                    })));
            assertTrue(connection.getAutoCommit());
            assertEquals(0, scalar(observer, "SELECT COUNT(*) FROM BREWERY WHERE name_ja='QaErrorFixture'"));
        }
    }

    @Test
    void translationCountsAndNonNullKanaUpdateIgnoreSourceLessRows() throws Exception {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try {
                var translations = new TranslationRepository(connection);
                int brandCount = translations.countUntranslatedBrands();
                int sakeCount = translations.countUntranslatedSakeNames();
                int riceCount = translations.countUntranslatedRice();
                int yeastCount = translations.countUntranslatedYeast();
                execute(connection, "INSERT INTO BREWERY(name_ja) VALUES('QaNoSourceBrewery')");
                long userBrewery = scalar(connection, "SELECT no FROM BREWERY WHERE name_ja='QaNoSourceBrewery'");
                execute(connection, "INSERT INTO BRAND(brewery_no,name_ja) VALUES(" + userBrewery + ",'QaNoSourceBrand')");
                execute(connection, """
                        INSERT INTO SAKE(brewery_no,name_ja,name_kana,rice,yeast)
                        VALUES(%d,'QaKanaSake','かな','QaUserRice','QaUserYeast')
                        """.formatted(userBrewery));
                assertEquals(brandCount, translations.countUntranslatedBrands());
                assertEquals(sakeCount, translations.countUntranslatedSakeNames());
                assertEquals(riceCount, translations.countUntranslatedRice());
                assertEquals(yeastCount, translations.countUntranslatedYeast());
                assertFalse(translations.findUntranslatedRice(Integer.MAX_VALUE).contains("QaUserRice"));
                assertFalse(translations.findUntranslatedYeast(Integer.MAX_VALUE).contains("QaUserYeast"));
                assertFalse(translations.findRiceValues().contains("QaUserRice"));
                assertFalse(translations.findYeastValues().contains("QaUserYeast"));

                long guideBrewery = new BreweryRepository(connection).save(
                        BreweryData.builder().sourceId("QaGuideBrewery").nameJa("QaGuideBrewery").build());
                long guideBrand = new BrandRepository(connection).save(
                        BrandData.builder().sourceId("QaGuideBrand").nameJa("QaGuideBrand").build(), guideBrewery);
                new SakeRepository(connection).save(SakeData.builder().sourceId(GUIDE_ID).nameJa("QaKanaSake")
                        .nameKana("かな").rice("QaGuideRice").yeast("QaGuideYeast").build(), guideBrewery, guideBrand);
                assertEquals(brandCount + 1, translations.countUntranslatedBrands());
                assertEquals(sakeCount + 1, translations.countUntranslatedSakeNames());
                assertEquals(riceCount + 1, translations.countUntranslatedRice());
                assertEquals(yeastCount + 1, translations.countUntranslatedYeast());
                assertTrue(translations.findUntranslatedRice(Integer.MAX_VALUE).contains("QaGuideRice"));
                assertTrue(translations.findUntranslatedYeast(Integer.MAX_VALUE).contains("QaGuideYeast"));
                assertTrue(translations.findRiceValues().contains("QaGuideRice"));
                assertTrue(translations.findYeastValues().contains("QaGuideYeast"));
                translations.updateSakeNameKo("QaKanaSake", "かな", "합성");
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM SAKE WHERE source_id IS NULL "
                        + "AND name_ja='QaKanaSake' AND name_ko IS NULL"));
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM SAKE WHERE source_id=" + GUIDE_ID + " AND name_ko='합성'"));
            } finally {
                connection.rollback();
            }
        }
    }

    private static Connection connect() throws SQLException {
        String url = System.getenv("AUTH_TEST_JDBC_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):3308/sakezuki_auth_it(?:\\?.*)?"));
        return DriverManager.getConnection(url, System.getenv("AUTH_TEST_DB_USER"), System.getenv("AUTH_TEST_DB_PASSWORD"));
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}
