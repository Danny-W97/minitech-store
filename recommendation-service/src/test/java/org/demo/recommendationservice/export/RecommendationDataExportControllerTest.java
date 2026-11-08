package org.demo.recommendationservice.export;

import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.Actor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RecommendationDataExportControllerTest {
    private final RecommendationDataExportService service = mock(RecommendationDataExportService.class);
    private MockMvc mvc(Actor actor) {
        return MockMvcBuilders.standaloneSetup(new RecommendationDataExportController(service))
                .setHandlerExceptionResolvers(new ResponseStatusExceptionResolver(), new DefaultHandlerExceptionResolver())
                .addFilters((request,response,chain)->{request.setAttribute(Access.ACTOR,actor);chain.doFilter(request,response);})
                .build();
    }
    @Test void ownerDownloadsRetainedLogWithExplicitCoverageAndCannotChooseAnotherSubject() throws Exception {
        Instant now=Instant.parse("2026-10-28T10:00:00Z");
        when(service.export(17,0,2)).thenReturn(new RecommendationDataExportPage(1,"recommendation-log-page",false,now,
                false,"optional-retained-log",List.of(new RecommendationDataExportPage.Item(99L,null,"CUSTOMER_HISTORY",4,now)),
                true,"99",List.of(new RecommendationDataExportPage.Omission("unloggedOrPurgedRequests","not-available"))));
        mvc(new Actor("CUSTOMER",null,17L,"owner@fixture.example"))
                .perform(get("/recommendations/me/data-export?size=2&customerId=42&id=42").header("X-Customer-Id","42"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,org.hamcrest.Matchers.containsString("private")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"recommendation-log-page.json\""))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(jsonPath("$.complete").value(false)).andExpect(jsonPath("$.recordingEnabled").value(false))
                .andExpect(jsonPath("$.historyCoverage").value("optional-retained-log"))
                .andExpect(jsonPath("$.items[0].strategy").value("CUSTOMER_HISTORY"))
                .andExpect(jsonPath("$.items[0].sourceProductId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].customerId").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").value("99"));
        verify(service).export(17,0,2);
    }
    @Test void anonymousOperatorAndServicesAreDeniedEvenWithAnIdClaim() throws Exception {
        for(var actor:List.of(Actor.ANONYMOUS,new Actor("OPERATOR",null,17L,"owner@fixture.example"),
                new Actor("SERVICE","recommendation-service",17L,"owner@fixture.example"),
                new Actor("CUSTOMER",null,null,"owner@fixture.example")))
            mvc(actor).perform(get("/recommendations/me/data-export")).andExpect(status().is4xxClientError());
        verifyNoInteractions(service);
    }
    @Test void malformedCursorNeverReachesTheService() throws Exception {
        mvc(new Actor("CUSTOMER",null,17L,"owner@fixture.example"))
                .perform(get("/recommendations/me/data-export?cursor=9223372036854775808")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
