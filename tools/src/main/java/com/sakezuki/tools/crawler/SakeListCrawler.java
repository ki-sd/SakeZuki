package com.sakezuki.tools.crawler;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SakeListCrawler {

    private static final String LIST_URL =
            "https://sake-guide.com/sake";

    private static final Pattern SAKE_ID_PATTERN =
            Pattern.compile("/sake/detail/(\\d+)");

    // 목록 페이지 요청 사이 간격
    private static final long PAGE_DELAY_MS = 1200;

    public Set<Long> crawlAll() throws IOException {

        Set<Long> sourceIds =
                new LinkedHashSet<>();

        int page = 1;

        while (true) {

            System.out.println(
                    "[LIST] " + page + " 페이지 수집 중..."
            );

            Document doc =
                    fetchPage(page);

            Set<Long> pageIds =
                    extractIds(doc);

            /*
             * 더 이상 상세 데이터가 없으면 종료
             */
            if (pageIds.isEmpty()) {

                System.out.println(
                        "[LIST] 데이터 없음 -> 목록 수집 종료"
                );

                break;
            }

            int before =
                    sourceIds.size();

            sourceIds.addAll(pageIds);

            int added =
                    sourceIds.size() - before;

            System.out.println(
                    "[LIST] page="
                            + page
                            + " / 발견="
                            + pageIds.size()
                            + " / 신규="
                            + added
                            + " / 누적="
                            + sourceIds.size()
            );


            /*
             * 정상적인 페이지라면 24개.
             *
             * 마지막 페이지는 24개보다 적으므로
             * 그 페이지를 처리하고 종료한다.
             */
            if (pageIds.size() < 24) {

                System.out.println(
                        "[LIST] 마지막 페이지 도달"
                );

                break;
            }


            page++;


            /*
             * 목록 페이지 요청 간격
             */
            sleep(PAGE_DELAY_MS);
        }


        System.out.println();
        System.out.println(
                "[LIST] 최종 sourceId 수 = "
                        + sourceIds.size()
        );

        return sourceIds;
    }


    private Document fetchPage(
            int page
    ) throws IOException {

        /*
         * 업로드한 HTML 기준:
         *
         * POST /sake
         *
         * data[SakeSearch][mode] = page
         * data[SakeSearch][page] = 페이지번호
         */

        Connection connection =
                Jsoup.connect(LIST_URL)
                        .userAgent(
                                "Mozilla/5.0"
                        )
                        .timeout(15000)
                        .method(
                                Connection.Method.POST
                        );


        connection.data(
                "_method",
                "POST"
        );

        connection.data(
                "data[SakeSearch][keyword]",
                ""
        );

        connection.data(
                "data[SakeSearch][sort_no]",
                "1"
        );

        connection.data(
                "data[SakeSearch][mode]",
                "page"
        );

        connection.data(
                "data[SakeSearch][page]",
                String.valueOf(page)
        );

        connection.data(
                "data[SakeSearch][category]",
                ""
        );

        connection.data(
                "data[SakeSearch][rawrice]",
                ""
        );

        connection.data(
                "data[SakeSearch][alcohol]",
                ""
        );


        return connection.execute()
                .parse();
    }


    private Set<Long> extractIds(
            Document doc
    ) {

        Set<Long> ids =
                new LinkedHashSet<>();


        /*
         * 같은 사케에 이미지 링크/제목 링크 등이
         * 여러 개 있을 수 있으므로 Set으로 중복 제거
         */
        for (
                Element link :
                doc.select(
                        "a[href^='/sake/detail/']"
                )
        ) {

            String href =
                    link.attr("href");

            Matcher matcher =
                    SAKE_ID_PATTERN.matcher(
                            href
                    );


            if (matcher.find()) {

                long sourceId =
                        Long.parseLong(
                                matcher.group(1)
                        );

                ids.add(sourceId);
            }
        }


        return ids;
    }


    private void sleep(
            long millis
    ) {

        try {

            Thread.sleep(millis);

        } catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();

            throw new RuntimeException(
                    e
            );
        }
    }
}