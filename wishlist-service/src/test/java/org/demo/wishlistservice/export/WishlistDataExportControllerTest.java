package org.demo.wishlistservice.export;

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

class WishlistDataExportControllerTest {
    private final WishlistDataExportService service = mock(WishlistDataExportService.class);

    private MockMvc mvc(Actor actor) {
        return MockMvcBuilders.standaloneSetup(new WishlistDataExportController(service))
                .setHandlerExceptionResolvers(new ResponseStatusExceptionResolver(), new DefaultHandlerExceptionResolver())
                .addFilters((request, response, chain) -> {
                    request.setAttribute(Access.ACTOR, actor);
                    chain.doFilter(request, response);
                }).build();
    }

    @Test void ownerGetsPartialJsonAndCannotChooseAnotherSubject() throws Exception {
        var actor = new Actor("CUSTOMER", null, 17L, "owner@fixture.example");
        when(service.export(17, 0, 2)).thenReturn(new WishlistDataExportPage(1, "wishlist-page", false, Instant.now(),
                List.of(new WishlistDataExportPage.Item(101L, Instant.parse("2026-10-28T10:00:00Z"))), true, "99",
                List.of(new WishlistDataExportPage.Omission("otherAccountData", "pending"))));
        mvc(actor).perform(get("/wishlists/me/data-export?size=2&customerId=42&id=42")
                        .header("X-Customer-Id", "42"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("private")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"wishlist-data-page.json\""))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.scope").value("wishlist-page"))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.items[0].productId").value(101))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").value("99"));
        verify(service).export(17, 0, 2);
    }

    @Test void anonymousOperatorAndServicesCannotReadEvenIfTheyHaveAClaimedId() throws Exception {
        for (var actor : List.of(Actor.ANONYMOUS, new Actor("OPERATOR", null, 17L, "owner@fixture.example"),
                new Actor("SERVICE", "order-service", 17L, "owner@fixture.example"),
                new Actor("CUSTOMER", null, null, "owner@fixture.example"),
                new Actor("CUSTOMER", null, 0L, "owner@fixture.example"))) {
            mvc(actor).perform(get("/wishlists/me/data-export")).andExpect(status().is4xxClientError());
        }
        verifyNoInteractions(service);
    }

    @Test void malformedCursorIsRejectedBeforeTheService() throws Exception {
        var actor = new Actor("CUSTOMER", null, 17L, "owner@fixture.example");
        mvc(actor).perform(get("/wishlists/me/data-export?cursor=9223372036854775808"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
