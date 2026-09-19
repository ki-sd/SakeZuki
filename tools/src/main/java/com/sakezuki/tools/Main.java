package com.sakezuki.tools;

import com.sakezuki.tools.crawler.BrandCrawler;
import com.sakezuki.tools.crawler.BreweryCrawler;
import com.sakezuki.tools.crawler.SakeCrawler;
import com.sakezuki.tools.crawler.SakeListCrawler;

import com.sakezuki.tools.model.BrandData;
import com.sakezuki.tools.model.BreweryData;
import com.sakezuki.tools.model.SakeData;

import com.sakezuki.tools.repository.BrandRepository;
import com.sakezuki.tools.repository.BreweryRepository;
import com.sakezuki.tools.repository.SakeRepository;

import java.io.PrintStream;

import java.nio.charset.StandardCharsets;

import java.sql.Connection;
import java.sql.DriverManager;

import java.util.Set;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

    /*
     * ========================================
     * DB
     * ========================================
     */

    private static final String DB_URL =
            System.getenv().getOrDefault(
                    "SAKEZUKI_DB_URL",
                    "jdbc:mysql://localhost:3406/sakezuki"
            );

    private static final String DB_USER =
            System.getenv().getOrDefault(
                    "SAKEZUKI_DB_USER",
                    "sakezuki"
            );

    private static final String DB_PASSWORD =
            System.getenv("SAKEZUKI_DB_PASSWORD");


    /*
     * ========================================
     * 크롤링 설정
     * ========================================
     */

    private static final int THREAD_COUNT = 3;

    /*
     * Worker 투입 간격.
     *
     * 3 Thread를 쓰되 작업을 한꺼번에
     * 시작시키지 않는다.
     */
    private static final long SUBMIT_DELAY_MS =
            1000;


    /*
     * ========================================
     * 캐시
     * ========================================
     *
     * 같은 브랜드/양조장을
     * 계속 HTTP 요청하지 않도록 저장.
     *
     * key   = URL
     * value = 크롤링 결과
     */

    private static final ConcurrentHashMap<String, BreweryData>
            BREWERY_CACHE =
            new ConcurrentHashMap<>();


    private static final ConcurrentHashMap<String, BrandData>
            BRAND_CACHE =
            new ConcurrentHashMap<>();


    /*
     * ========================================
     * 통계
     * ========================================
     */

    private static final AtomicInteger SUCCESS_COUNT =
            new AtomicInteger();

    private static final AtomicInteger FAIL_COUNT =
            new AtomicInteger();

    private static final AtomicInteger COMPLETE_COUNT =
            new AtomicInteger();


    public static void main(String[] args) {
        if (DB_PASSWORD == null || DB_PASSWORD.isBlank()) {
            throw new IllegalStateException(
                    "SAKEZUKI_DB_PASSWORD 환경변수가 설정되지 않았습니다."
            );
        }

        /*
         * ========================================
         * UTF-8
         * ========================================
         */

        System.setOut(
                new PrintStream(
                        System.out,
                        true,
                        StandardCharsets.UTF_8
                )
        );


        try {

            System.out.println();
            System.out.println(
                    "========================================"
            );
            System.out.println(
                    "SakeZuki 전체 크롤러 시작"
            );
            System.out.println(
                    "========================================"
            );


            /*
             * ========================================
             * STEP 1
             *
             * 전체 사케 sourceId 수집
             * ========================================
             */

            SakeListCrawler listCrawler =
                    new SakeListCrawler();


            Set<Long> sourceIds =
                    listCrawler.crawlAll();


            if (sourceIds.isEmpty()) {

                System.err.println(
                        "수집된 사케가 없습니다."
                );

                return;
            }


            final int totalCount =
                    sourceIds.size();


            System.out.println();
            System.out.println(
                    "========================================"
            );

            System.out.println(
                    "상세 크롤링 시작"
            );

            System.out.println(
                    "전체 사케 = "
                            + totalCount
            );

            System.out.println(
                    "Worker = "
                            + THREAD_COUNT
            );

            System.out.println(
                    "========================================"
            );


            /*
             * ========================================
             * STEP 2
             *
             * Worker Pool
             * ========================================
             */

            ExecutorService executor =
                    Executors.newFixedThreadPool(
                            THREAD_COUNT
                    );


            /*
             * ========================================
             * STEP 3
             *
             * 사케 작업 등록
             * ========================================
             */

            for (Long sourceId : sourceIds) {

                executor.submit(
                        () ->
                                processSake(
                                        sourceId,
                                        totalCount
                                )
                );


                /*
                 * 서버에 순간적으로
                 * 요청이 몰리는 것 완화
                 */

                sleep(
                        SUBMIT_DELAY_MS
                );
            }


            /*
             * ========================================
             * STEP 4
             *
             * 새로운 작업 접수 종료
             * ========================================
             */

            executor.shutdown();


            /*
             * ========================================
             * STEP 5
             *
             * Worker 종료 대기
             *
             * 전체 1만건이므로
             * 12시간까지 허용
             * ========================================
             */

            boolean finished =
                    executor.awaitTermination(
                            12,
                            TimeUnit.HOURS
                    );


            if (!finished) {

                System.err.println(
                        "제한시간 초과"
                );

                executor.shutdownNow();
            }


            /*
             * ========================================
             * 최종 결과
             * ========================================
             */

            System.out.println();
            System.out.println(
                    "========================================"
            );

            System.out.println(
                    "SakeZuki 전체 크롤링 종료"
            );

            System.out.println(
                    "전체 = "
                            + totalCount
            );

            System.out.println(
                    "성공 = "
                            + SUCCESS_COUNT.get()
            );

            System.out.println(
                    "실패 = "
                            + FAIL_COUNT.get()
            );

            System.out.println(
                    "양조장 캐시 = "
                            + BREWERY_CACHE.size()
            );

            System.out.println(
                    "브랜드 캐시 = "
                            + BRAND_CACHE.size()
            );

            System.out.println(
                    "========================================"
            );


        } catch (Exception e) {

            System.err.println(
                    "전체 크롤러 실행 실패"
            );

            e.printStackTrace();
        }
    }


    /*
     * ========================================
     * 사케 하나 처리
     * ========================================
     */

    private static void processSake(
            Long sourceId,
            int totalCount
    ) {

        String threadName =
                Thread.currentThread()
                        .getName();


        try {

            /*
             * ========================================
             * 1. SAKE 상세
             * ========================================
             */

            SakeCrawler sakeCrawler =
                    new SakeCrawler();


            SakeData sake =
                    sakeCrawler.crawl(
                            sourceId
                    );


            if (sake == null) {

                throw new RuntimeException(
                        "SAKE 크롤링 결과 null"
                );
            }


            if (
                    sake.getBreweryUrl() == null
                            || sake.getBreweryUrl().isBlank()
            ) {

                throw new RuntimeException(
                        "양조장 URL 없음"
                );
            }


            if (
                    sake.getBrandUrl() == null
                            || sake.getBrandUrl().isBlank()
            ) {

                throw new RuntimeException(
                        "브랜드 URL 없음"
                );
            }


            /*
             * ========================================
             * 2. BREWERY
             *
             * URL 기준 캐시
             * ========================================
             */

            BreweryData brewery =
                    getBrewery(
                            sake.getBreweryUrl()
                    );


            /*
             * ========================================
             * 3. BRAND
             *
             * URL 기준 캐시
             * ========================================
             */

            BrandData brand =
                    getBrand(
                            sake.getBrandUrl()
                    );


            /*
             * ========================================
             * 4. DB
             * ========================================
             */

            saveToDatabase(
                    sake,
                    brewery,
                    brand
            );


            SUCCESS_COUNT.incrementAndGet();


        } catch (Exception e) {

            FAIL_COUNT.incrementAndGet();


            System.err.println(
                    "[FAIL]"
                            + " sourceId="
                            + sourceId
                            + " | "
                            + e.getMessage()
            );


        } finally {

            int complete =
                    COMPLETE_COUNT.incrementAndGet();


            /*
             * 너무 많은 로그가 찍히지 않도록
             * 10건마다 진행률 출력
             */

            if (
                    complete % 10 == 0
                            || complete == totalCount
            ) {

                double percent =
                        ((double) complete
                                / totalCount)
                                * 100.0;


                System.out.printf(
                        "[PROGRESS] %d / %d (%.2f%%)"
                                + " | 성공=%d"
                                + " | 실패=%d%n",
                        complete,
                        totalCount,
                        percent,
                        SUCCESS_COUNT.get(),
                        FAIL_COUNT.get()
                );
            }
        }
    }


    /*
     * ========================================
     * Brewery 캐시
     * ========================================
     */

    private static BreweryData getBrewery(
            String url
    ) throws Exception {

        BreweryData cached =
                BREWERY_CACHE.get(url);


        if (cached != null) {

            return cached;
        }


        /*
         * 동시에 같은 양조장이 들어왔을 때
         * 중복 HTTP 요청을 최대한 방지.
         */

        synchronized (
                BREWERY_CACHE
        ) {

            cached =
                    BREWERY_CACHE.get(url);


            if (cached != null) {

                return cached;
            }


            BreweryCrawler crawler =
                    new BreweryCrawler();


            BreweryData brewery =
                    crawler.crawl(url);


            if (brewery == null) {

                throw new RuntimeException(
                        "BREWERY 결과 null: "
                                + url
                );
            }


            BREWERY_CACHE.put(
                    url,
                    brewery
            );


            return brewery;
        }
    }


    /*
     * ========================================
     * Brand 캐시
     * ========================================
     */

    private static BrandData getBrand(
            String url
    ) throws Exception {

        BrandData cached =
                BRAND_CACHE.get(url);


        if (cached != null) {

            return cached;
        }


        synchronized (
                BRAND_CACHE
        ) {

            cached =
                    BRAND_CACHE.get(url);


            if (cached != null) {

                return cached;
            }


            BrandCrawler crawler =
                    new BrandCrawler();


            BrandData brand =
                    crawler.crawl(url);


            if (brand == null) {

                throw new RuntimeException(
                        "BRAND 결과 null: "
                                + url
                );
            }


            BRAND_CACHE.put(
                    url,
                    brand
            );


            return brand;
        }
    }


    /*
     * ========================================
     * DB
     * ========================================
     */

    private static void saveToDatabase(
            SakeData sake,
            BreweryData brewery,
            BrandData brand
    ) throws Exception {


        try (
                Connection conn =
                        DriverManager.getConnection(
                                DB_URL,
                                DB_USER,
                                DB_PASSWORD
                        )
        ) {

            conn.setAutoCommit(false);


            try {

                BreweryRepository breweryRepository =
                        new BreweryRepository(
                                conn
                        );


                BrandRepository brandRepository =
                        new BrandRepository(
                                conn
                        );


                SakeRepository sakeRepository =
                        new SakeRepository(
                                conn
                        );


                /*
                 * ========================================
                 * FK 순서
                 *
                 * BREWERY
                 *    ↓
                 * BRAND
                 *    ↓
                 * SAKE
                 * ========================================
                 */

                long breweryNo =
                        breweryRepository.save(
                                brewery
                        );


                long brandNo =
                        brandRepository.save(
                                brand,
                                breweryNo
                        );


                sakeRepository.save(
                        sake,
                        breweryNo,
                        brandNo
                );


                conn.commit();


            } catch (Exception e) {

                conn.rollback();

                throw e;


            } finally {

                conn.setAutoCommit(true);
            }
        }
    }


    /*
     * ========================================
     * Sleep
     * ========================================
     */

    private static void sleep(
            long millis
    ) {

        try {

            Thread.sleep(
                    millis
            );


        } catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();

            throw new RuntimeException(
                    e
            );
        }
    }
}