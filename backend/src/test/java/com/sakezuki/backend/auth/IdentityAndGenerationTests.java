package com.sakezuki.backend.auth;

import com.sakezuki.backend.identity.*;
import com.sakezuki.backend.credit.CreditProperties;
import com.sakezuki.backend.recommend.service.GenerationGuard;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdentityAndGenerationTests {
    IdentityProperties policy() {
        var p=new IdentityProperties();p.setHashKey("test-key");p.setTermsVersion("v1");p.setPrivacyVersion("v1");
        p.setTermsUrl("/terms");p.setPrivacyUrl("/privacy");p.setProviderOrigin("https://identity.example.invalid");return p;
    }
    @Test void providerRedirectIsRestrictedToExactHttpsOrigin() {
        var provider=mock(IdentityProvider.class);when(provider.available()).thenReturn(true);
        var mapper=mock(IdentityMapper.class);var tx=mock(IdentityTransactions.class);
        var service=new IdentityService(provider,policy(),mapper,tx);
        for(String url:new String[]{"https://evil.invalid/","http://identity.example.invalid/","https://identity.example.invalid.evil.invalid/","https://user@identity.example.invalid/"}) {
            when(provider.start(anyString())).thenReturn(new IdentityProvider.Challenge("reference",url));
            assertThrows(IllegalStateException.class,()->service.start(1L,"v1","v1"));
        }
        verify(mapper,never()).challenge(any());
        when(provider.start(anyString())).thenReturn(new IdentityProvider.Challenge("reference","https://identity.example.invalid:443/start"));
        assertNotNull(service.start(1L,"v1","v1").requestId());verify(mapper).challenge(any());
    }
    @Test void identityCompletionUsesServerProviderResultAndRejectsOtherMemberChallenge() {
        var provider=mock(IdentityProvider.class);when(provider.available()).thenReturn(true);
        var mapper=mock(IdentityMapper.class);var tx=mock(IdentityTransactions.class);
        var service=new IdentityService(provider,policy(),mapper,tx);String id=UUID.randomUUID().toString();
        when(mapper.readChallenge(1L,id)).thenReturn(new IdentityMapper.Challenge(id,1L,"server-reference",Instant.now().plusSeconds(60).getEpochSecond(),false));
        var result=new IdentityProvider.Verified("server-reference","scope","opaque-subject",true,Instant.now());
        when(provider.verify("server-reference","proof")).thenReturn(result);
        service.complete(1L,id,"proof");verify(tx).finish(1L,id,result);
        assertThrows(ResponseStatusException.class,()->service.complete(2L,id,"proof"));
        verifyNoMoreInteractions(tx);
    }
    @Test void generationBudgetCountsFailedCallsButCacheHitsDoNotConsumeIt() {
        var policy=new CreditProperties();policy.setDailyGenerationLimit(1);var guard=new GenerationGuard(policy);
        assertThrows(IllegalStateException.class,()->guard.generate(()->null,()->{throw new IllegalStateException("failed call");}));
        assertEquals("cache",guard.generate(()->"cache",()->"unexpected"));
        assertEquals(429,assertThrows(ResponseStatusException.class,()->guard.generate(()->null,()->"unexpected")).getStatusCode().value());
    }
    @Test void concurrentCacheMissDoesNotLaunchAnotherExternalCall() throws Exception {
        var guard=new GenerationGuard(new CreditProperties());var started=new CountDownLatch(1);var finish=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var task=pool.submit(()->guard.generate(()->null,()->{started.countDown();try{finish.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return "done";}));
            assertTrue(started.await(5,TimeUnit.SECONDS));
            try{assertEquals(429,assertThrows(ResponseStatusException.class,()->guard.generate(()->null,()->"unexpected")).getStatusCode().value());}
            finally{finish.countDown();}
            assertEquals("done",task.get(5,TimeUnit.SECONDS));
        }
    }
}
