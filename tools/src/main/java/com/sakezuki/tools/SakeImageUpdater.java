package com.sakezuki.tools;

import com.sakezuki.tools.crawler.SakeImageCrawler;
import com.sakezuki.tools.repository.SakeRepository;
import com.sakezuki.tools.util.EnvLoader;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SakeImageUpdater {

    private static final int THREAD_COUNT=5;

    private final SakeImageCrawler crawler=new SakeImageCrawler();

    private final String url="jdbc:mysql://localhost:3406/"+EnvLoader.get("MYSQL_DATABASE");
    private final String user=EnvLoader.get("MYSQL_USER");
    private final String password=EnvLoader.get("MYSQL_PASSWORD");



    public void run() {
        List<Long> sourceIds=findSourceIds();

        System.out.println("이미지 보충 대상: "+sourceIds.size()+"건");

        if (sourceIds.isEmpty()) {
            System.out.println("이미지 보충 대상이 없습니다.");
            return;
        }

        ExecutorService executor=Executors.newFixedThreadPool(THREAD_COUNT);
        AtomicInteger successCount=new AtomicInteger();
        AtomicInteger noImageCount=new AtomicInteger();
        AtomicInteger failCount=new AtomicInteger();
        AtomicInteger progressCount=new AtomicInteger();

        for (Long sourceId : sourceIds) {
            executor.submit(()->{
                try {
                    String imageUrl=crawler.crawlImageUrl(sourceId);

                    try (Connection conn=getConnection()) {
                        SakeRepository repository=new SakeRepository(conn);
                        repository.updateImageUrl(sourceId,imageUrl);
                    }

                    if (imageUrl==null) {
                        noImageCount.incrementAndGet();
                    } else {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.out.println("이미지 처리 실패: sourceId="+sourceId+", "+e.getMessage());
                } finally {
                    int progress=progressCount.incrementAndGet();

                    if (progress%100==0 || progress==sourceIds.size()) {
                        System.out.println("진행률: "+progress+"/"+sourceIds.size()+" | 성공="+successCount.get()+" | 이미지없음="+noImageCount.get()+" | 실패="+failCount.get());
                    }
                }
            });

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        executor.shutdown();

        try {
            if (!executor.awaitTermination(3,TimeUnit.HOURS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        System.out.println("이미지 보충 완료");
        System.out.println("성공="+successCount.get()+", 이미지없음="+noImageCount.get()+", 실패="+failCount.get());
    }

    private List<Long> findSourceIds() {
        try (Connection conn=getConnection()) {
            SakeRepository repository=new SakeRepository(conn);
            return repository.findUncheckedImageSourceIds();
        } catch (Exception e) {
            throw new RuntimeException("이미지 보충 대상 조회 실패",e);
        }
    }

    private Connection getConnection() throws Exception {
        return DriverManager.getConnection(url,user,password);
    }

    public static void main(String[] args) {
        System.setOut(
                new PrintStream(
                        System.out,
                        true,
                        StandardCharsets.UTF_8
                )
        );
        SakeImageUpdater updater=new SakeImageUpdater();
        updater.run();
    }
}