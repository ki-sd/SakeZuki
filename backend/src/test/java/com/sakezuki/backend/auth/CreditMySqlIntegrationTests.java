package com.sakezuki.backend.auth;

import com.sakezuki.backend.credit.*;
import com.sakezuki.backend.identity.*;
import com.sakezuki.backend.recommend.service.GeminiRecommendService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("mysql-integration")
@EnabledIfEnvironmentVariable(named="AUTH_TEST_JDBC_URL", matches="jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):3308/sakezuki_auth_it(?:\\?.*)?")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"spring.config.import=", "spring.ai.google.genai.api-key=test",
        "spring.security.oauth2.client.registration.google.client-id=test", "spring.security.oauth2.client.registration.google.client-secret=test",
        "jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "identity.hash-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "identity.terms-version=test-v1", "identity.privacy-version=test-v1", "logging.level.com.zaxxer.hikari=INFO"})
class CreditMySqlIntegrationTests {
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", ()->System.getenv("AUTH_TEST_JDBC_URL"));
        p.add("spring.datasource.username", ()->System.getenv("AUTH_TEST_DB_USER"));
        p.add("spring.datasource.password", ()->System.getenv("AUTH_TEST_DB_PASSWORD"));
    }
    @Autowired JdbcTemplate db;
    @Autowired IdentityTransactions verification;
    @Autowired IdentityService provider;
    @Autowired CreditTransactions transactions;
    @Autowired CreditMapper mapper;
    @Autowired PaidRecommendationService paid;
    @Autowired CreditMaintenance maintenance;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.sakezuki.backend.security.jwt.JwtTokenProvider tokens;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @MockitoBean GeminiRecommendService gemini;
    Long member;
    @BeforeEach void fixture() { member = member(); }
    Long member() {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO MEMBER(email,name,address1,phone) VALUES(?,?,?,?)",id+"@example.invalid","테스트","테스트",id.substring(0,20));
        Long no=db.queryForObject("SELECT no FROM MEMBER WHERE email=?",Long.class,id+"@example.invalid");
        db.update("INSERT INTO MEMBER_AUTHORITY(member_no,authority) VALUES(?,'ROLE_USER')",no);
        return no;
    }
    String verify(Long member, String subject) {
        verification.consent(member,"test-v1","test-v1");
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO IDENTITY_CHALLENGE VALUES(?,?,?,?,FALSE)",id,member,id,Instant.now().plusSeconds(600).getEpochSecond());
        verification.finish(member,id,new IdentityProvider.Verified(id,"same-service",subject,true,Instant.now()));
        return id;
    }
    @Test void missingProviderNeverVerifiesAndDoesNotGrantCredits() {
        assertFalse(provider.available());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->provider.start(member,"test-v1","test-v1")).getStatusCode().value());
        assertNull(mapper.balance(member));
    }
    @Test void samePersonCannotGrantAcrossAccountsAndConflictRollsBack() {
        String subject=UUID.randomUUID().toString(); verify(member,subject);
        Long other=member();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->verify(other,subject));
        assertNull(mapper.balance(other)); assertFalse(mapper.verified(other));
        assertEquals(10,mapper.balance(member));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM CREDIT_LEDGER WHERE member_no=?",Integer.class,member));
    }
    @Test void expiredWrongOwnerAndUnderageProofsNeverGrant() {
        verification.consent(member,"test-v1","test-v1");
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO IDENTITY_CHALLENGE VALUES(?,?,?,?,FALSE)",id,member,id,Instant.now().plusSeconds(600).getEpochSecond());
        var underage=new IdentityProvider.Verified(id,"test",UUID.randomUUID().toString(),false,Instant.now());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->verification.finish(member,id,underage)).getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->verification.finish(member(),id,underage));
        db.update("UPDATE IDENTITY_CHALLENGE SET expires_at_epoch=0 WHERE id=?",id);
        assertThrows(ResponseStatusException.class,()->verification.finish(member,id,underage));
        assertNull(mapper.balance(member));
    }
    @Test void concurrentLastCreditCannotOverspendAndRetriesAreIdempotent() throws Exception {
        verify(member,UUID.randomUUID().toString()); db.update("UPDATE CREDIT_ACCOUNT SET balance=1 WHERE member_no=?",member);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var futures=new ArrayList<Future<CreditTransactions.Reservation>>();
            for(int i=0;i<8;i++) futures.add(pool.submit(()->{start.await();try{return transactions.reserve(member,UUID.randomUUID().toString(),"x","SAKE","test");}catch(ResponseStatusException e){assertEquals(402,e.getStatusCode().value());return null;}}));
            start.countDown();
            var successes=new ArrayList<CreditTransactions.Reservation>();
            for(var future:futures){var value=future.get(15,TimeUnit.SECONDS);if(value!=null)successes.add(value);}
            assertEquals(1,successes.size()); assertEquals(0,mapper.balance(member));
            var winner=successes.getFirst().request();
            assertFalse(transactions.reserve(member,winner.idempotencyKey(),"x","SAKE","test").owner());
            assertThrows(ResponseStatusException.class,()->transactions.reserve(member,winner.idempotencyKey(),"changed","SAKE","other"));
        }
    }
    @Test void completionAndRefundRaceHasExactlyOneTerminalOutcome() throws Exception {
        verify(member,UUID.randomUUID().toString());
        var request=transactions.reserve(member,UUID.randomUUID().toString(),"x","SAKE","test").request();
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var completed=pool.submit(()->{start.await();try{transactions.complete(member,request.id(),"{\"food\":\"snapshot\"}");}catch(ResponseStatusException ignored){}return null;});
            var refunded=pool.submit(()->{start.await();transactions.refund(member,request.id(),false);return null;});
            start.countDown();completed.get(15,TimeUnit.SECONDS);refunded.get(15,TimeUnit.SECONDS);
        }
        var finalRequest=mapper.byId(member,request.id());
        assertTrue(Set.of("COMPLETED","FAILED").contains(finalRequest.status()));
        assertEquals("COMPLETED".equals(finalRequest.status())?9:10,mapper.balance(member));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM CREDIT_LEDGER WHERE member_no=? AND reference_id=? AND kind IN ('REFUND','COMMIT')",Integer.class,member,request.id()));
    }
    @Test void failureAndRestartRecoveryRefundOnlyOnceAndLateCompletionFails() {
        verify(member,UUID.randomUUID().toString());
        var request=transactions.reserve(member,UUID.randomUUID().toString(),"x","SAKE","test").request();
        db.update("UPDATE RECOMMEND_REQUEST SET created_at_epoch=0 WHERE id=?",request.id());
        maintenance.recover();maintenance.recover();
        assertEquals(10,mapper.balance(member));
        assertEquals("TIMED_OUT",mapper.byId(member,request.id()).status());
        assertThrows(ResponseStatusException.class,()->transactions.complete(member,request.id(),"{}"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM CREDIT_LEDGER WHERE member_no=? AND kind='REFUND'",Integer.class,member));
    }
    @Test void cachedRecommendationChargesOnceSnapshotsAndSurvivesCacheChange() {
        verify(member,UUID.randomUUID().toString());
        String food="snapshot-"+UUID.randomUUID();
        var sakes=db.queryForList("SELECT no FROM SAKE ORDER BY no LIMIT 3",Long.class);
        for(Long sake:sakes)db.update("INSERT INTO RECOMMEND_SAKE(food,sake_no,reason) VALUES(?,?,?)",food,sake,"original");
        String key=UUID.randomUUID().toString();
        String result=paid.recommend(member,key,"SAKE",food,null);
        assertEquals(9,mapper.balance(member));
        db.update("UPDATE RECOMMEND_SAKE SET reason='changed' WHERE food=?",food);
        assertEquals(result,paid.recommend(member,key,"SAKE",food,null));
        assertTrue(result.contains("original")); assertFalse(result.contains("changed"));
        verifyNoInteractions(gemini);
        var request=mapper.byKey(member,key);
        assertNull(mapper.byId(member(),request.id()));
        assertEquals(1,mapper.history(member,Instant.now().getEpochSecond(),0).size());
        db.update("UPDATE RECOMMEND_REQUEST SET expires_at_epoch=0 WHERE id=?",request.id());
        assertEquals(410,assertThrows(ResponseStatusException.class,()->paid.recommend(member,key,"SAKE",food,null)).getStatusCode().value());
        maintenance.recover(); assertNull(mapper.byId(member,request.id()).resultSnapshot());
        assertEquals(3,mapper.entries(member,0).size());
    }
    @Test void generationFailureRefundsAndFailedKeyNeverChargesAgain() {
        verify(member,UUID.randomUUID().toString());
        when(gemini.analyzeFood(anyString())).thenThrow(new IllegalStateException("external failure"));
        String key=UUID.randomUUID().toString();String food="failure-"+UUID.randomUUID();
        assertThrows(IllegalStateException.class,()->paid.recommend(member,key,"SAKE",food,null));
        assertEquals(10,mapper.balance(member));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->paid.recommend(member,key,"SAKE",food,null)).getStatusCode().value());
        assertEquals(10,mapper.balance(member));
    }
    @Test void concurrentSameKeyHasExactlyOneReservationOwner() throws Exception {
        verify(member,UUID.randomUUID().toString());String key=UUID.randomUUID().toString();
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(6)) {
            var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<6;i++)futures.add(pool.submit(()->{start.await();return transactions.reserve(member,key,"same","SAKE","test").owner();}));
            start.countDown();int owners=0;
            for(var future:futures)if(future.get(15,TimeUnit.SECONDS))owners++;
            assertEquals(1,owners);assertEquals(9,mapper.balance(member));
        }
    }
    @Test void resultAndLedgerRollbackTogetherThenRecoveryIsSafe() {
        verify(member,UUID.randomUUID().toString());
        var request=transactions.reserve(member,UUID.randomUUID().toString(),"x","SAKE","test").request();
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{transactions.complete(member,request.id(),"{\"result\":true}");throw new IllegalStateException("commit interruption");}));
        assertEquals("RESERVED",mapper.byId(member,request.id()).status());
        assertNull(mapper.byId(member,request.id()).resultSnapshot());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM CREDIT_LEDGER WHERE member_no=? AND kind='COMMIT'",Integer.class,member));
        db.update("UPDATE RECOMMEND_REQUEST SET created_at_epoch=0 WHERE id=?",request.id());
        maintenance.recover();assertEquals(10,mapper.balance(member));
    }
    String bearer(Long no) {
        return tokens.createAccessToken(new com.sakezuki.backend.security.CustomUserDetails(no,"test@example.invalid",null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));
    }
    java.net.http.HttpResponse<String> http(String path, Long no, String key, String body) throws Exception {
        var builder=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path));
        if(no!=null)builder.header("Authorization","Bearer "+bearer(no));
        if(key!=null)builder.header("Idempotency-Key",key);
        if(body!=null)builder.header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));else builder.GET();
        builder.timeout(java.time.Duration.ofSeconds(15));
        return java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build()
                .send(builder.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
    }
    @Test void httpEnforcesAuthenticationVerificationCreditAndHistoryOwnership() throws Exception {
        String key=UUID.randomUUID().toString();String food="http-"+UUID.randomUUID();String body="{\"food\":\""+food+"\"}";
        assertEquals(401,http("/api/recommend/sake",null,key,body).statusCode());
        assertEquals(403,http("/api/recommend/sake",member,key,body).statusCode());
        verify(member,UUID.randomUUID().toString());
        assertEquals(400,http("/api/recommend/sake",member,null,body).statusCode());
        for(Long sake:db.queryForList("SELECT no FROM SAKE ORDER BY no LIMIT 3",Long.class))
            db.update("INSERT INTO RECOMMEND_SAKE(food,sake_no,reason) VALUES(?,?,?)",food,sake,"http snapshot");
        var result=http("/api/recommend/sake",member,key,body);assertEquals(200,result.statusCode());
        assertTrue(result.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(result.body(),http("/api/recommend/sake",member,key,body).body());assertEquals(9,mapper.balance(member));
        var record=mapper.byKey(member,key);
        assertEquals(200,http("/api/user/recommendations/"+record.id(),member,null,null).statusCode());
        assertEquals(404,http("/api/user/recommendations/"+record.id(),member(),null,null).statusCode());
        db.update("UPDATE CREDIT_ACCOUNT SET balance=0 WHERE member_no=?",member);
        assertEquals(200,http("/api/recommend/sake",member,key,body).statusCode());
        assertEquals(402,http("/api/recommend/sake",member,UUID.randomUUID().toString(),body).statusCode());
        db.update("UPDATE MEMBER SET status='BLOCKED' WHERE no=?",member);
        assertEquals(401,http("/api/user/account",member,null,null).statusCode());
    }
}
