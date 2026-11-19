package com.sakezuki.backend.auth;

import com.sakezuki.backend.BackendApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/** Test-only server: refuses every DB except the disposable localhost:3308 database. */
public class AuthE2eServer {
    public static void main(String[] args) {
        String url = System.getenv("AUTH_TEST_JDBC_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:3308/sakezuki_auth_it(?:\\?.*)?"))
            throw new IllegalStateException("An isolated auth test database is required");
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        Map.ofEntries(
                Map.entry("spring.config.import", ""), Map.entry("server.port", "8081"),
                Map.entry("spring.datasource.url", url),
                Map.entry("spring.datasource.username", System.getenv("AUTH_TEST_DB_USER")),
                Map.entry("spring.datasource.password", System.getenv("AUTH_TEST_DB_PASSWORD")),
                Map.entry("spring.security.oauth2.client.registration.google.client-id", "e2e-test"),
                Map.entry("spring.security.oauth2.client.registration.google.client-secret", "e2e-test"),
                Map.entry("spring.security.oauth2.client.registration.google.redirect-uri", "http://localhost:3001/login/oauth2/code/google"),
                Map.entry("spring.ai.google.genai.api-key", "e2e-test"),
                Map.entry("auth.frontend-url", "http://localhost:3001"),
                Map.entry("jwt.secret", Base64.getEncoder().encodeToString(key)),
                Map.entry("jwt.access-token-expiration", "2s"),
                Map.entry("logging.level.com.zaxxer.hikari", "INFO")
        ).forEach(System::setProperty);
        var context = SpringApplication.run(BackendApplication.class, args);
        var db = context.getBean(JdbcTemplate.class);
        String email = System.getenv("AUTH_TEST_LOGIN_EMAIL");
        String password = System.getenv("AUTH_TEST_LOGIN_PASSWORD");
        db.update("INSERT INTO MEMBER(email,password,name,address1,phone) VALUES(?,?,?,?,?)", email,
                context.getBean(PasswordEncoder.class).encode(password), "E2E test", "Test address", "e2e-" + java.util.UUID.randomUUID().toString().substring(0, 12));
        Long no = db.queryForObject("SELECT no FROM MEMBER WHERE email=?", Long.class, email);
        db.update("INSERT INTO MEMBER_AUTHORITY(member_no,authority) VALUES(?, 'ROLE_USER')", no);
        // 브라우저 과금 검증에만 쓰는 합성 본인확인 결과다. 운영 JAR에는 이 진입점이 포함되지 않는다.
        long now=java.time.Instant.now().getEpochSecond();
        db.update("INSERT INTO MEMBER_VERIFICATION VALUES(?,'e2e','e2e',?,?,TRUE)",no,now,now);
        db.update("INSERT INTO CREDIT_ACCOUNT VALUES(?,10)",no);
        db.update("INSERT INTO CREDIT_LEDGER(member_no,reference_id,kind,delta,balance_after,created_at_epoch) VALUES(?,'INITIAL','GRANT',10,10,?)",no,now);
        var sakeNos=db.queryForList("SELECT no FROM SAKE ORDER BY no LIMIT 3",Long.class);
        String food="E2E pairing "+java.util.UUID.randomUUID();
        for(Long sake:sakeNos)db.update("INSERT INTO RECOMMEND_SAKE(food,sake_no,reason) VALUES(?,?,?)",food,sake,"E2E 당시 추천 이유");
        System.out.println("AUTH_E2E_FOOD="+food);
        System.out.println("AUTH_E2E_READY");
    }
}
