package com.sakezuki.tools.crawler;

import com.sakezuki.tools.model.SakeData;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;

// 사이트의 표 머리글을 필드명으로 해석해 한 제품의 원문 데이터를 만든다.
// 화면 표시용 한국어 값은 여기서 추측하지 않고 별도 번역 단계에 맡긴다.
public class SakeCrawler {
    private static final String BASE_URL =
            "https://sake-guide.com/sake/detail/";

    public SakeData crawl(long sourceId) throws IOException {

        String url = BASE_URL + sourceId;

        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0")
                .timeout(10000)
                .get();

        SakeData sake = new SakeData();
        sake.setSourceId(sourceId);

        // 기본 정보
        Elements rows = doc.select("table tr");

        for (Element row : rows) {

            Elements cells = row.select("th, td");

            if (cells.size() < 2) {
                continue;
            }

            String key = cells.get(0).text().trim();
            String value = cells.get(1).text().trim();

            switch (key) {

                case "日本酒名":
                    parseSakeName(sake, value);
                    break;

                case "都道府県":
                    sake.setPrefecture(value);
                    break;

                case "酒蔵":
                    sake.setBreweryName(value);
                    break;

                case "銘柄":
                    sake.setBrandName(value);
                    break;

                case "特定名称区分":
                    sake.setSakeType(value);
                    break;

                case "原料米":
                    sake.setRice(value);
                    break;

                case "精米歩合":
                    sake.setPolishingRatio(value);
                    break;

                case "使用酵母":
                    sake.setYeast(value);
                    break;

                case "日本酒度":
                    sake.setSakeMeterValue(value);
                    break;

                case "酸度":
                    sake.setAcidity(value);
                    break;

                case "アルコール度数":
                    sake.setAlcoholPercentage(value);
                    break;
            }
        }

        // 브랜드 링크
        Element brandLink =
                doc.selectFirst(".sake-hero__subtitle a[href^=/brands/]");

        if (brandLink != null) {
            sake.setBrandUrl(brandLink.absUrl("href"));
        }

        // 양조장 링크
        Element breweryLink =
                doc.selectFirst(".sake-hero__subtitle a[href^=/breweries/]:not([href*=/pref/])");

        if (breweryLink != null) {
            sake.setBreweryUrl(breweryLink.absUrl("href"));
        }

        return sake;
    }

    private void parseSakeName(SakeData sake, String value) {

        int start = value.lastIndexOf('（');
        int end = value.lastIndexOf('）');

        if (start >= 0 && end > start) {

            String name = value.substring(0, start).trim();
            String kana = value.substring(start + 1, end).trim();

            sake.setNameJa(name);
            sake.setNameKana(kana);

        } else {
            sake.setNameJa(value);
        }
    }
}
