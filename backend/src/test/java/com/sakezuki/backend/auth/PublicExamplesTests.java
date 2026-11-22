package com.sakezuki.backend.auth;

import com.sakezuki.backend.commons.ControllerException;
import com.sakezuki.backend.config.SecurityConfig;
import com.sakezuki.backend.credit.*;
import com.sakezuki.backend.recommend.RecommendationCondition;
import com.sakezuki.backend.recommend.examples.PublicRecommendationExamples;
import com.sakezuki.backend.recommend.service.GeminiRecommendService;
import com.sakezuki.backend.security.jwt.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PublicExamplesTests {
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    @Configuration static class ReadOnlyBeans {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean GeminiRecommendService ai() { return mock(GeminiRecommendService.class); }
        @Bean CreditMapper credits() { return mock(CreditMapper.class); }
        @Bean CreditTransactions transactions() { return mock(CreditTransactions.class); }
    }
    @BeforeEach void setup() {
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.register(AuthSecurityTests.Beans.class,ReadOnlyBeans.class,SecurityConfig.class,JwtConfig.class,JwtTokenProvider.class,
                PublicRecommendationExamples.class,ControllerException.class);
        context.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();
    }
    @AfterEach void close() {context.close();}
    @Test void anonymousExamplesNeverInvokeAiCreditsOrPersonalHistory() throws Exception {
        String body=mvc.perform(get("/api/examples/recommendations")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4)).andExpect(jsonPath("$[0].sourceType").value("EDITORIAL_SAMPLE"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        for(String privateField:List.of("memberNo","resultSnapshot","phone","email","tokenHash","idempotencyKey","referenceId"))assertFalse(body.contains(privateField));
        mvc.perform(get("/api/examples/recommendations/grilled-fish")).andExpect(status().isOk());
        mvc.perform(get("/api/examples/recommendations/"+UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/user/recommendations/"+UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/recommend/sake").contentType("application/json").content("{\"food\":\"test\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(context.getBean(GeminiRecommendService.class),context.getBean(CreditMapper.class),context.getBean(CreditTransactions.class));
    }
    @Test void editorialProductsMatchCommittedSeedAndBothDirectionsArePresent() throws Exception {
        String seed=new ClassPathResource("db/migration/V2__initial_seed.sql").getContentAsString(StandardCharsets.UTF_8);
        var examples=context.getBean(PublicRecommendationExamples.class).list().getBody();assertNotNull(examples);
        assertEquals(2,examples.stream().filter(e->e.kind().equals("SAKE")).count());
        assertEquals(2,examples.stream().filter(e->e.kind().equals("FOOD")).count());
        for(var example:examples) {
            assertEquals("EDITORIAL_SAMPLE",example.sourceType());assertTrue(example.sourceNote().contains("실제 AI 생성 결과가 아닌"));
            var products=new ArrayList<>(example.sakes());if(example.selectedSake()!=null)products.add(example.selectedSake());
            for(var product:products) {
                var row=java.util.regex.Pattern.compile("\\("+product.no()+",[0-9]+,[0-9]+,(?:NULL|[0-9]+),'"+java.util.regex.Pattern.quote(product.nameJa())+"','[^']*','"+java.util.regex.Pattern.quote(product.nameKo())+"'");
                assertTrue(row.matcher(seed).find(),"Product must match seed row: "+product.no());
            }
        }
    }
    @Test void matchingNormalizationPreservesExistingBillingHashWithoutSemanticMerging() throws Exception {
        var condition=RecommendationCondition.of("SAKE","  스키야키 \n",null);
        assertEquals("스키야키",condition.input());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("SAKE\n스키야키".getBytes(StandardCharsets.UTF_8))),condition.hash());
        assertNotEquals(condition.hash(),RecommendationCondition.of("SAKE","스키 야키",null).hash());
        assertNotEquals(RecommendationCondition.of("SAKE","A",null).hash(),RecommendationCondition.of("SAKE","a",null).hash());
        assertEquals("4",RecommendationCondition.of("FOOD",null,4L).input());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->RecommendationCondition.of("OTHER",null,4L));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->RecommendationCondition.of("SAKE"," ",null));
    }
}
