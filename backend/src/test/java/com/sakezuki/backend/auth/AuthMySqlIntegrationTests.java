package com.sakezuki.backend.auth;

import com.sakezuki.backend.auth.service.RefreshTokenService;
import com.sakezuki.backend.auth.service.InvalidRefreshTokenException;
import com.sakezuki.backend.auth.service.AuthService;
import com.sakezuki.backend.auth.dto.SocialSignupRequestDTO;
import com.sakezuki.backend.member.service.MemberService;
import com.sakezuki.backend.security.CustomUserDetails;
import com.sakezuki.backend.security.jwt.*;
import com.sakezuki.backend.security.oauth.SocialSignupDTO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql-integration")
@EnabledIfEnvironmentVariable(named = "AUTH_TEST_JDBC_URL", matches = "jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):3308/sakezuki_auth_it(?:\\?.*)?")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "spring.security.oauth2.client.registration.google.client-id=integration-test",
        "spring.security.oauth2.client.registration.google.client-secret=integration-test",
        "spring.ai.google.genai.api-key=integration-test", "logging.level.com.zaxxer.hikari=INFO",
        "jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class AuthMySqlIntegrationTests {
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("AUTH_TEST_JDBC_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("AUTH_TEST_DB_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("AUTH_TEST_DB_PASSWORD"));
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder passwords;
    @Autowired RefreshTokenService refresh;
    @Autowired AuthService auth;
    @Autowired MemberService members;
    @Autowired JwtTokenProvider jwt;
    @Autowired JwtProperties jwtProperties;
    private final ObjectMapper json = new ObjectMapper();
    private Long memberNo;
    private String email;

    @BeforeEach void fixture() {
        String id = UUID.randomUUID().toString();
        email = id + "@example.invalid";
        db.update("INSERT INTO MEMBER(email,password,name,address1,phone) VALUES(?,?,?,?,?)",
                email, passwords.encode("Integration-password-42!"), "통합테스트", "테스트 주소", id.substring(0, 20));
        memberNo = db.queryForObject("SELECT no FROM MEMBER WHERE email=?", Long.class, email);
        db.update("INSERT INTO MEMBER_AUTHORITY(member_no,authority) VALUES(?, 'ROLE_USER')", memberNo);
    }
    private class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> get(String path, String bearer) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(15));
            if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
            return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String path, String body, boolean csrf) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
            if (csrf) {
                var csrfResponse = get("/api/auth/csrf", null);
                assertEquals(200, csrfResponse.statusCode(), "CSRF endpoint status");
                JsonNode token = json.readTree(csrfResponse.body());
                builder.header(token.get("headerName").asString(), token.get("token").asString());
            }
            return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> login(String password) throws Exception {
            return post("/api/auth/login", json.writeValueAsString(Map.of("email", email, "password", password)), true);
        }
        String refreshCookie() { return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("sakezuki_refresh")).findFirst().orElseThrow().getValue(); }
    }

    @Test void flywayV4AndActualTablesAreValid() {
        assertEquals(4, db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='4' AND success=1", Integer.class));
        assertNotNull(db.queryForList("SELECT token_hash,family_id,used FROM AUTH_REFRESH_TOKEN LIMIT 1"));
    }

    @Test void emailLoginCsrfCookiesRotationReplayAndLogoutOverRealHttp() throws Exception {
        var browser = new Browser();
        assertEquals(403, browser.post("/api/auth/login", "{}", false).statusCode());
        assertEquals(401, browser.login("wrong-password").statusCode());
        var login = browser.login("Integration-password-42!");
        assertEquals(200, login.statusCode());
        assertFalse(json.readTree(login.body()).has("refreshToken"));
        String access = json.readTree(login.body()).get("accessToken").asString();
        assertEquals(200, browser.get("/api/user/me", access).statusCode());
        assertEquals(401, browser.get("/api/user/me", null).statusCode());
        assertEquals(403, browser.get("/api/admin/integration-probe", access).statusCode());
        String old = browser.refreshCookie();
        assertTrue(login.headers().allValues("set-cookie").stream().anyMatch(c -> c.contains("HttpOnly") && c.contains("SameSite=Lax") && c.contains("Path=/api/auth")));
        assertEquals(403, browser.post("/api/auth/refresh", "{}", false).statusCode());
        assertEquals(200, browser.post("/api/auth/refresh", "{}", true).statusCode());
        String rotated = browser.refreshCookie(); assertNotEquals(old, rotated);
        assertThrows(InvalidRefreshTokenException.class, () -> refresh.rotate(old));
        assertThrows(InvalidRefreshTokenException.class, () -> refresh.rotate(rotated));
        assertEquals(200, browser.login("Integration-password-42!").statusCode());
        String beforeLogout = browser.refreshCookie();
        assertEquals(204, browser.post("/api/auth/logout", "{}", true).statusCode());
        assertThrows(InvalidRefreshTokenException.class, () -> refresh.rotate(beforeLogout));
    }

    @Test void refreshMapperUsesHashedTokensAndRejectsExpiredFamily() throws Exception {
        var issued = refresh.issue(memberNo);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(issued.rawToken().getBytes(StandardCharsets.US_ASCII)));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM AUTH_REFRESH_TOKEN WHERE token_hash=?", Integer.class, hash));
        db.update("UPDATE AUTH_TOKEN_FAMILY SET expires_at_epoch=0 WHERE member_no=?", memberNo);
        assertThrows(InvalidRefreshTokenException.class, () -> refresh.rotate(issued.rawToken()));
    }

    @Test void concurrentRotationCommitsReplayRevocationUnderMySqlRepeatableRead() throws Exception {
        assertEquals("REPEATABLE-READ", db.queryForObject("SELECT @@transaction_isolation", String.class));
        String raw = refresh.issue(memberNo).rawToken();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> operation = () -> {
                gate.await();
                try { return refresh.rotate(raw).rawToken(); }
                catch (InvalidRefreshTokenException e) { return "rejected"; }
            };
            var first = pool.submit(operation); var second = pool.submit(operation); gate.countDown();
            String a = first.get(15, TimeUnit.SECONDS), b = second.get(15, TimeUnit.SECONDS);
            assertNotEquals(a.equals("rejected"), b.equals("rejected"));
            assertThrows(InvalidRefreshTokenException.class, () -> refresh.rotate(a.equals("rejected") ? b : a));
            assertEquals(true, db.queryForObject("SELECT revoked FROM AUTH_TOKEN_FAMILY WHERE member_no=?", Boolean.class, memberNo));
        }
    }

    @Test void socialSignupAndExistingIdentityUseRealMapperAndRejectDuplicates() {
        String unique = UUID.randomUUID().toString();
        var identity = new SocialSignupDTO("GOOGLE", unique, unique + "@example.invalid", "소셜테스트");
        var dto = new SocialSignupRequestDTO(); dto.setAddress1("테스트 주소"); dto.setPhone("010" + String.format("%08d", Math.abs(unique.hashCode() % 100000000)));
        var result = auth.socialSignup(identity, dto);
        assertNotNull(result.getAccessToken());
        var social = members.findSocialAccount("GOOGLE", unique); assertNotNull(social);
        assertNull(members.getLoginData(social.getMemberNo()).getPassword());
        assertTrue(members.getLoginData(social.getMemberNo()).getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
        assertNotNull(auth.socialLogin(social.getMemberNo()).getAccessToken());
        assertThrows(ResponseStatusException.class, () -> auth.socialSignup(identity, dto));
        var sameEmail = new SocialSignupDTO("GOOGLE", "other-" + unique, email, "소셜테스트");
        assertThrows(ResponseStatusException.class, () -> auth.socialSignup(sameEmail, dto));
    }

    @Test void socialSignupRollsBackMemberAndAuthorityWhenPhoneCollides() {
        var dto = new SocialSignupRequestDTO(); dto.setAddress1("테스트 주소"); dto.setPhone("01098765432");
        db.update("UPDATE MEMBER SET phone=? WHERE no=?", dto.getPhone(), memberNo);
        String candidate = UUID.randomUUID() + "@example.invalid";
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> auth.socialSignup(new SocialSignupDTO("GOOGLE", UUID.randomUUID().toString(), candidate, "테스트"), dto));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM MEMBER WHERE email=?", Integer.class, candidate));
    }

    @Test void concurrentSocialSignupCreatesOnlyOneCompleteMember() throws Exception {
        String id = UUID.randomUUID().toString();
        var info = new SocialSignupDTO("GOOGLE", id, id + "@example.invalid", "Concurrent signup");
        var details = new SocialSignupRequestDTO(); details.setAddress1("Test address");
        details.setPhone(String.format("%011d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100000000000L)));
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> signup = () -> {
                gate.await();
                try { auth.socialSignup(info, details); return true; }
                catch (ResponseStatusException | org.springframework.dao.DataIntegrityViolationException e) { return false; }
            };
            var a = pool.submit(signup); var b = pool.submit(signup); gate.countDown();
            assertNotEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
        Long no = db.queryForObject("SELECT no FROM MEMBER WHERE email=?", Long.class, info.getEmail());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM SOCIAL_ACCOUNT WHERE provider_user_id=?", Integer.class, id));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM MEMBER_AUTHORITY WHERE member_no=?", Integer.class, no));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM MEMBER_GRADE WHERE member_no=?", Integer.class, no));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM AUTH_TOKEN_FAMILY WHERE member_no=?", Integer.class, no));
    }

    @Test void expiredAccessAndRoleBoundariesAreEnforcedByActualServer() throws Exception {
        Duration before = jwtProperties.getAccessTokenExpiration();
        String expired;
        // Short positive lifetime with an old issued-at is covered by a signed fixture below.
        var encoder = org.springframework.security.oauth2.jwt.NimbusJwtEncoder.withSecretKey(
                new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(jwtProperties.getSecret()), "HmacSHA256")).build();
        var now = java.time.Instant.now();
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer(jwtProperties.getIssuer()).subject(memberNo.toString())
                .issuedAt(now.minusSeconds(600)).expiresAt(now.minusSeconds(300)).claim("roles", List.of("ROLE_USER")).build();
        expired = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims)).getTokenValue();
        var browser = new Browser(); assertEquals(401, browser.get("/api/user/me", expired).statusCode());
        assertEquals(before, jwtProperties.getAccessTokenExpiration());
        String admin = jwt.createAccessToken(new CustomUserDetails(memberNo, email, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        assertEquals(200, browser.get("/api/user/me", admin).statusCode());
    }

    @Test void publicSakeReadsAndRecommendValidationRemainReachable() throws Exception {
        var browser = new Browser();
        var list = browser.get("/api/sake/list", null); assertEquals(200, list.statusCode());
        JsonNode items = json.readTree(list.body()).get("list"); assertFalse(items.isEmpty());
        assertEquals(200, browser.get("/api/sake/" + items.get(0).get("no").asLong(), null).statusCode());
        assertEquals(400, browser.post("/api/recommend/sake", "{\"food\":\" \"}", false).statusCode());
    }

    @Test void corsUsesConfiguredFrontendOriginAndRejectsOtherSites() throws Exception {
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        for (String origin : List.of("http://localhost:3000", "https://untrusted.example.invalid")) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sake/list"))
                    .header("Origin", origin).GET().build();
            assertEquals(origin.equals("http://localhost:3000") ? 200 : 403,
                    client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }

    @Test void cachedRecommendationsInBothDirectionsUseRealDatabaseWithoutExternalAi() throws Exception {
        var sakeNos = db.queryForList("SELECT no FROM SAKE ORDER BY no LIMIT 3", Long.class);
        String food = "Integration-food-" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            db.update("INSERT INTO RECOMMEND_FOOD(sake_no,name,reason) VALUES(?,?,?)", sakeNos.get(0), "Food " + i, "Test reason");
            db.update("INSERT INTO RECOMMEND_SAKE(food,sake_no,reason) VALUES(?,?,?)", food, sakeNos.get(i), "Test reason");
        }
        var browser = new Browser();
        var foods = browser.post("/api/recommend/food", json.writeValueAsString(Map.of("sakeNo", sakeNos.get(0))), false);
        assertEquals(200, foods.statusCode()); assertEquals(3, json.readTree(foods.body()).get("recommends").size());
        var sakes = browser.post("/api/recommend/sake", json.writeValueAsString(Map.of("food", food)), false);
        assertEquals(200, sakes.statusCode()); assertEquals(3, json.readTree(sakes.body()).get("recommendations").size());
    }
}
