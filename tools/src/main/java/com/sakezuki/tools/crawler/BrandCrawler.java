package com.sakezuki.tools.crawler;

import com.sakezuki.tools.model.BrandData;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;

public class BrandCrawler {

    public BrandData crawl(String url) throws IOException {

        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0")
                .timeout(10000)
                .get();

        BrandData brand = new BrandData();

        brand.setSourceId(extractSourceId(url));

        // 후리가나
        Element kanaElement = doc.selectFirst(".kana");

        if (kanaElement != null) {
            brand.setNameKana(kanaElement.text().trim());
        }

        // 브랜드 정보 테이블
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

                case "銘柄名":
                    brand.setNameJa(
                            removeKana(value)
                    );
                    break;

                case "造っている酒蔵名":
                    brand.setBreweryName(value);

                    Element breweryLink =
                            valueCell.selectFirst(
                                    "a[href^=/breweries/]"
                            );

                    if (breweryLink != null) {
                        brand.setBreweryUrl(
                                breweryLink.absUrl("href")
                        );
                    }

                    break;

                case "酒蔵の都道府県":
                    brand.setPrefecture(value);
                    break;
            }
        }

        return brand;
    }

    private String extractSourceId(String url) {

        int index = url.lastIndexOf('/');

        if (index >= 0) {
            return url.substring(index + 1);
        }

        return url;
    }

    private String removeKana(String value) {

        int index = value.indexOf('（');

        if (index >= 0) {
            return value.substring(0, index).trim();
        }

        return value;
    }
}