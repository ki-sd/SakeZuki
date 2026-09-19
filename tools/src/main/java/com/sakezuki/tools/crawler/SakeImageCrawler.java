package com.sakezuki.tools.crawler;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public class SakeImageCrawler {

    private static final String BASE_URL="https://sake-guide.com/sake/detail/";

    public String crawlImageUrl(Long sourceId) {
        try {
            Document doc=Jsoup.connect(BASE_URL+sourceId)
                    .userAgent("Mozilla/5.0")
                    .timeout(10000)
                    .get();

            Element image=doc.selectFirst(".sake-hero__figure img[data-src]");
            if(image==null) return null;

            String imageUrl=image.absUrl("data-src").trim();
            if(imageUrl.isBlank() || imageUrl.contains("noimage")) return null;

            return imageUrl;
        } catch(Exception e) {
            System.out.println("이미지 크롤링 실패: sourceId="+sourceId+", "+e.getMessage());
            return null;
        }
    }
}