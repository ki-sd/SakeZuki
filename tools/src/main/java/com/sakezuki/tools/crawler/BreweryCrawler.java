package com.sakezuki.tools.crawler;

import com.sakezuki.tools.model.BreweryData;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BreweryCrawler {

    public BreweryData crawl(String url) throws IOException {

        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0")
                .timeout(10000)
                .get();

        BreweryData brewery = new BreweryData();

        // sourceId
        brewery.setSourceId(extractSourceId(url));

        // 양조장명
        Element nameElement = doc.selectFirst("h1");

        if (nameElement != null) {
            brewery.setNameJa(
                    removePrefecture(nameElement.text().trim())
            );
        }

        // 후리가나
        Element kanaElement = doc.selectFirst(".kana");

        if (kanaElement != null) {
            brewery.setNameKana(kanaElement.text().trim());
        }

        // 기본 정보 테이블
        Elements rows = doc.select("table tr");

        for (Element row : rows) {

            Elements cells = row.select("th, td");

            if (cells.size() < 2) {
                continue;
            }

            String key = cells.get(0).text().trim();

            Element valueCell = cells.get(1);
            String value = valueCell.text().trim();

            switch (key) {

                case "会社名":
                    brewery.setCorporationName(value);
                    break;

                case "所在地":
                    parseAddress(brewery, value);
                    break;

                case "創業年":
                    brewery.setFoundedYear(value);
                    break;

                case "公式サイト":
                    Element websiteLink =
                            valueCell.selectFirst("a[href]");

                    if (websiteLink != null) {
                        brewery.setWebsite(
                                websiteLink.absUrl("href")
                        );
                    }

                    break;

                case "見学情報":
                    brewery.setTourAvailable(
                            value.contains("見学あり") ? "Y" : "N"
                    );
                    break;
            }
        }

        // JSON-LD에서 전화번호 + 위경도
        parseJsonLd(doc, brewery);

        return brewery;
    }

    private String extractSourceId(String url) {

        int index = url.lastIndexOf('/');

        if (index >= 0) {
            return url.substring(index + 1);
        }

        return url;
    }

    private String removePrefecture(String value) {

        int index = value.indexOf('（');

        if (index >= 0) {
            return value.substring(0, index).trim();
        }

        return value;
    }

    private void parseAddress(BreweryData brewery, String value) {

        // "〒742-0422 山口県岩国市周東町獺越2167-4（地図を見る）"
        String cleaned = value
                .replace("（地図を見る）", "")
                .trim();

        Pattern postPattern =
                Pattern.compile("〒(\\d{3}-\\d{4})");

        Matcher postMatcher =
                postPattern.matcher(cleaned);

        if (postMatcher.find()) {
            brewery.setPost(postMatcher.group(1));

            cleaned = cleaned
                    .replace(postMatcher.group(0), "")
                    .trim();
        }

        brewery.setAddress(cleaned);

        // 주소 시작 부분에서 현 추출
        Pattern prefecturePattern =
                Pattern.compile("^(.+?[都道府県])");

        Matcher prefectureMatcher =
                prefecturePattern.matcher(cleaned);

        if (prefectureMatcher.find()) {
            brewery.setPrefecture(
                    prefectureMatcher.group(1)
            );
        }
    }

    private void parseJsonLd(
            Document doc,
            BreweryData brewery
    ) {

        Elements scripts =
                doc.select("script[type=application/ld+json]");

        for (Element script : scripts) {

            String json = script.data();

            if (json == null || json.isBlank()) {
                continue;
            }

            if (brewery.getPhone() == null) {

                Pattern phonePattern =
                        Pattern.compile(
                                "\"telephone\"\\s*:\\s*\"([^\"]+)\""
                        );

                Matcher matcher =
                        phonePattern.matcher(json);

                if (matcher.find()) {
                    brewery.setPhone(matcher.group(1));
                }
            }

            if (brewery.getLatitude() == null) {

                Pattern latitudePattern =
                        Pattern.compile(
                                "\"latitude\"\\s*:\\s*([0-9.]+)"
                        );

                Matcher matcher =
                        latitudePattern.matcher(json);

                if (matcher.find()) {
                    brewery.setLatitude(
                            Double.parseDouble(matcher.group(1))
                    );
                }
            }

            if (brewery.getLongitude() == null) {

                Pattern longitudePattern =
                        Pattern.compile(
                                "\"longitude\"\\s*:\\s*([0-9.]+)"
                        );

                Matcher matcher =
                        longitudePattern.matcher(json);

                if (matcher.find()) {
                    brewery.setLongitude(
                            Double.parseDouble(matcher.group(1))
                    );
                }
            }
        }
    }
}