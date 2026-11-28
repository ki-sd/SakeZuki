package com.sakezuki.backend.auth;

import com.sakezuki.backend.BackendApplication;
import com.sakezuki.backend.image.ImageStores;
import com.sakezuki.imagecore.ImageStorage;
import com.sakezuki.imagecore.ImageTransform;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
        var context = new SpringApplication(BackendApplication.class, StagingFixture.class).run(args);
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
        String submission = "e2e00000-0000-4000-8000-000000000001";
        db.update("""
                INSERT INTO CATALOG_SUBMISSION(id,owner_no,status,version,creation_key,payload_hash)
                VALUES (?,?,'NEEDS_REVISION',1,?,REPEAT('0',64))
                """,submission,no,submission);
        db.update("""
                INSERT INTO SUBMISSION_REVISION(submission_id,revision_no,author_no,author_role,revision_kind,payload)
                VALUES (?,1,?,'MEMBER','INITIAL',
                    '{"sake":{"ja":"合成酒","ko":"보완할 사케"},"brewery":{"existingNo":1}}')
                """,submission,no);
        db.update("UPDATE CATALOG_SUBMISSION SET current_revision_no=1 WHERE id=?",submission);
        db.update("""
                INSERT INTO SUBMISSION_EVENT(submission_id,revision_no,actor_no,actor_role,previous_status,next_status,reason)
                VALUES (?,1,?,'ADMIN','PENDING','NEEDS_REVISION','사케 이름을 확인해 주세요.')
                """,submission,no);
        System.out.println("AUTH_E2E_FOOD="+food);
        System.out.println("AUTH_E2E_READY");
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class StagingFixture {
        @Bean @Primary
        ImageStores e2eImageStores() {
            var objects = new ConcurrentHashMap<String, ImageStorage.Payload>();
            ImageStorage.Readable staging = new ImageStorage.Readable() {
                @Override public boolean createIfAbsent(ObjectKey key, String mime, String hash, byte[] bytes) {
                    return objects.putIfAbsent(key.value(),new Payload(mime,CACHE_CONTROL,hash,bytes.clone()))==null;
                }

                @Override public Optional<ObjectInfo> read(ObjectKey key,int max) throws java.io.IOException {
                    return readPayload(key,max).map(value -> new ObjectInfo(value.contentType(),value.cacheControl(),
                            value.declaredSha256(),ImageTransform.sha256(value.bytes()),value.bytes().length));
                }

                @Override public Optional<Payload> readPayload(ObjectKey key,int max) throws java.io.IOException {
                    var value = objects.get(key.value());
                    if (value==null) return Optional.empty();
                    if (value.bytes().length>max) throw new ReadLimitExceeded();
                    return Optional.of(new Payload(value.contentType(),value.cacheControl(),value.declaredSha256(),value.bytes().clone()));
                }
            };
            return area -> area==ImageStores.Area.STAGING ? Optional.of(staging) : Optional.empty();
        }
    }

}
