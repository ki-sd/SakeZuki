package com.sakezuki.backend.auth.service;

import com.sakezuki.backend.auth.AuthProperties;
import com.sakezuki.backend.auth.mapper.RefreshTokenMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private final RefreshTokenMapper mapper;
    private final AuthProperties properties;
    private final SecureRandom random = new SecureRandom();
    public record Issued(Long memberNo, String rawToken) {}

    @Transactional
    public Issued issue(Long memberNo) {
        String family = UUID.randomUUID().toString();
        mapper.insertFamily(family, memberNo, Instant.now().plus(properties.getRefreshExpiration()).getEpochSecond());
        return insert(memberNo, family);
    }

    // Replay revocation must commit even though the HTTP response is 401.
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Issued rotate(String raw) {
        String hash = hash(raw);
        var token = mapper.findToken(hash);
        if (token == null) throw new InvalidRefreshTokenException();
        var family = mapper.lockFamily(token.familyId());
        if (family == null || family.revoked() || family.expiresAt() <= Instant.now().getEpochSecond())
            throw new InvalidRefreshTokenException();
        // Read again after acquiring the family lock: concurrent rotations serialize here.
        token = mapper.lockToken(hash);
        if (token.used()) {
            mapper.revokeFamily(family.id());
            throw new InvalidRefreshTokenException();
        }
        mapper.useToken(hash);
        return insert(family.memberNo(), family.id());
    }

    @Transactional
    public void revoke(String raw) {
        if (raw == null || raw.isBlank()) return;
        String hashed;
        try { hashed = hash(raw); }
        catch (InvalidRefreshTokenException ignored) { return; }
        var token = mapper.findToken(hashed);
        if (token != null) {
            mapper.lockFamily(token.familyId());
            mapper.revokeFamily(token.familyId());
        }
    }

    @Scheduled(fixedDelay = 86400000)
    public void cleanup() { mapper.deleteExpired(); }

    private Issued insert(Long memberNo, String family) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        mapper.insertToken(hash(raw), family);
        return new Issued(memberNo, raw);
    }

    static String hash(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) throw new InvalidRefreshTokenException();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
