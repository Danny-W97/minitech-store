package org.demo.reviewservice.export;

import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.Actor;
import org.demo.reviewservice.review.ReviewStatus;
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

class ReviewDataExportControllerTest {
    private final ReviewDataExportService service = mock(ReviewDataExportService.class);
    private MockMvc mvc(Actor actor) {
        return MockMvcBuilders.standaloneSetup(new ReviewDataExportController(service))
                .setHandlerExceptionResolvers(new ResponseStatusExceptionResolver(), new DefaultHandlerExceptionResolver())
                .addFilters((request, response, chain) -> {request.setAttribute(Access.ACTOR, actor); chain.doFilter(request, response);})
                .build();
    }

    @Test void ownerGetsExplicitSafeFieldsAndCannotSelectAnotherCustomer() throws Exception {
        var actor = new Actor("CUSTOMER", null, 17L, "owner@fixture.example");
        Instant now = Instant.parse("2026-10-28T10:00:00Z");
        when(service.export(17, 0, 2)).thenReturn(new ReviewDataExportPage(1, "reviews-page", false, now,
                List.of(new ReviewDataExportPage.Item(99L, 101L, 5, "Żółć", "<script>literal text</script>",
                        ReviewStatus.REJECTED, now, now)), true, "99",
                List.of(new ReviewDataExportPage.Omission("moderationNotes", "manual-review-and-redaction-procedure"))));
        mvc(actor).perform(get("/reviews/me/data-export?size=2&customerId=42&id=42").header("X-Customer-Id", "42"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("private")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"reviews-data-page.json\""))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.scope").value("reviews-page"))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.items[0].content").value("<script>literal text</script>"))
                .andExpect(jsonPath("$.items[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.items[0].orderId").doesNotExist())
                .andExpect(jsonPath("$.items[0].customerId").doesNotExist())
                .andExpect(jsonPath("$.items[0].moderationNote").doesNotExist())
                .andExpect(jsonPath("$.hasMore").value(true)).andExpect(jsonPath("$.nextCursor").value("99"));
        verify(service).export(17, 0, 2);
    }

    @Test void operatorAnonymousAndServiceCannotExportEvenWithAnIdClaim() throws Exception {
        for (var actor : List.of(Actor.ANONYMOUS, new Actor("OPERATOR", null, 17L, "owner@fixture.example"),
                new Actor("SERVICE", "review-service", 17L, "owner@fixture.example"),
                new Actor("CUSTOMER", null, null, "owner@fixture.example"))) {
            mvc(actor).perform(get("/reviews/me/data-export")).andExpect(status().is4xxClientError());
        }
        verifyNoInteractions(service);
    }

    @Test void malformedCursorIsRejectedBeforeTheService() throws Exception {
        mvc(new Actor("CUSTOMER", null, 17L, "owner@fixture.example"))
                .perform(get("/reviews/me/data-export?cursor=9223372036854775808")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
