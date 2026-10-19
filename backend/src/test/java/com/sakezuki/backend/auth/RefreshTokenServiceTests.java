package com.sakezuki.backend.auth;

import com.sakezuki.backend.auth.mapper.RefreshTokenMapper;
import com.sakezuki.backend.auth.service.InvalidRefreshTokenException;
import com.sakezuki.backend.auth.service.RefreshTokenService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefreshTokenServiceTests {
    private final RefreshTokenMapper mapper = mock(RefreshTokenMapper.class);
    private final RefreshTokenService service = new RefreshTokenService(mapper, new AuthProperties());
    private final String raw = "a".repeat(43);

    @Test void storesHashAndRotatesOnlyOnce() {
        var issued = service.issue(1L);
        var hash = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertToken(hash.capture(), anyString());
        assertEquals(64, hash.getValue().length());
        assertNotEquals(issued.rawToken(), hash.getValue());
        assertEquals(43, issued.rawToken().length());
        when(mapper.findToken(anyString())).thenReturn(new RefreshTokenMapper.Token("hash", "family", false));
        when(mapper.lockFamily("family")).thenReturn(new RefreshTokenMapper.Family("family", 1L, Instant.now().plusSeconds(60).getEpochSecond(), false));
        when(mapper.lockToken(anyString())).thenReturn(new RefreshTokenMapper.Token("hash", "family", false));
        var rotated = service.rotate(raw);
        assertNotEquals(raw, rotated.rawToken());
        verify(mapper).useToken(anyString());
        when(mapper.lockToken(anyString())).thenReturn(new RefreshTokenMapper.Token("hash", "family", true));
        assertThrows(InvalidRefreshTokenException.class, () -> service.rotate(raw));
        verify(mapper).revokeFamily("family");
    }

    @Test void rejectsMissingUnknownExpiredAndRevokedTokens() {
        assertThrows(InvalidRefreshTokenException.class, () -> service.rotate(null));
        assertThrows(InvalidRefreshTokenException.class, () -> service.rotate("bad"));
        assertThrows(InvalidRefreshTokenException.class, () -> service.rotate(raw));
        when(mapper.findToken(anyString())).thenReturn(new RefreshTokenMapper.Token("hash", "family", false));
        when(mapper.lockFamily("family")).thenReturn(new RefreshTokenMapper.Family("family", 1L, Instant.now().minusSeconds(1).getEpochSecond(), false));
        assertThrows(InvalidRefreshTokenException.class, () -> service.rotate(raw));
        when(mapper.lockFamily("family")).thenReturn(new RefreshTokenMapper.Family("family", 1L, Instant.now().plusSeconds(60).getEpochSecond(), true));
        assertThrows(InvalidRefreshTokenException.class, () -> service.rotate(raw));
        verify(mapper, never()).useToken(anyString());
    }

    @Test void logoutRevokesEntireFamilyIncludingRotatedTokens() {
        when(mapper.findToken(anyString())).thenReturn(new RefreshTokenMapper.Token("hash", "family", true));
        service.revoke(raw);
        verify(mapper).lockFamily("family");
        verify(mapper).revokeFamily("family");
        service.revoke(null);
        service.revoke("malformed-cookie");
    }

    @Test void replayRevocationCommitsDespiteUnauthorizedException() {
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status = new org.springframework.transaction.support.SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        var attributes = new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource();
        var interceptor = new org.springframework.transaction.interceptor.TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(attributes);
        var factory = new org.springframework.aop.framework.ProxyFactory(service);
        factory.addAdvice(interceptor);
        var proxied = (RefreshTokenService) factory.getProxy();
        when(mapper.findToken(anyString())).thenReturn(new RefreshTokenMapper.Token("hash", "family", false));
        when(mapper.lockFamily("family")).thenReturn(new RefreshTokenMapper.Family("family", 1L, Instant.now().plusSeconds(60).getEpochSecond(), false));
        when(mapper.lockToken(anyString())).thenReturn(new RefreshTokenMapper.Token("hash", "family", true));
        assertThrows(InvalidRefreshTokenException.class, () -> proxied.rotate(raw));
        verify(mapper).revokeFamily("family");
        verify(manager).commit(status);
        verify(manager, never()).rollback(any());
    }
}
