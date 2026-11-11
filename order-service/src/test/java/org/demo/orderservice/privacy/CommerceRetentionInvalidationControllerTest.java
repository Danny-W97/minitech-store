package org.demo.orderservice.privacy;

import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.Actor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Phase.REDACTION;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CommerceRetentionInvalidationControllerTest {
    final CommerceRetentionJobs jobs = mock(CommerceRetentionJobs.class);
    final UUID generation = UUID.fromString("00000000-0000-0000-0000-000000000007");
    final String path = "/internal/privacy/commerce-retention/invalidate";
    String command() { return "{\"orderId\":17,\"generation\":\"" + generation + "\",\"orderVersion\":4,\"phase\":\"REDACTION\"}"; }
    MockMvc mvc(Actor actor) {
        return MockMvcBuilders.standaloneSetup(new CommerceRetentionInvalidationController(jobs))
                .setHandlerExceptionResolvers(new ResponseStatusExceptionResolver(), new DefaultHandlerExceptionResolver())
                .addFilters((request, response, chain) -> { request.setAttribute(Access.ACTOR, actor); chain.doFilter(request, response); }).build();
    }

    @Test void onlyTheSevenAuthenticatedSourcesCanInvalidate() throws Exception {
        for (String service : List.of("returns-service", "payment-service", "inventory-service", "promotion-service", "delivery-service", "billing-service", "notification-service")) {
            mvc(new Actor("SERVICE", service, null, null)).perform(post(path).contentType(MediaType.APPLICATION_JSON).content(command()))
                    .andExpect(status().isAccepted()).andExpect(content().string(""))
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        }
        verify(jobs, times(7)).invalidate(17, generation, 4, REDACTION);
    }

    @Test void anonymousCustomerOperatorAndOtherServicesCannotForgeSourceHeaders() throws Exception {
        for (Actor actor : List.of(Actor.ANONYMOUS, new Actor("CUSTOMER", null, 17L, "fixture@example.test"),
                new Actor("OPERATOR", null, null, null), new Actor("SERVICE", "customer-service", null, null),
                new Actor("SERVICE", "product-service", null, null), new Actor("SERVICE", null, null, null))) {
            mvc(actor).perform(post(path).header("X-Service-Name", "billing-service").header("X-Actor-Role", "SERVICE")
                    .contentType(MediaType.APPLICATION_JSON).content(command()))
                    .andExpect(status().is(actor.authenticated() ? 403 : 401));
        }
        verifyNoInteractions(jobs);
    }

    @Test void malformedScopesNeverReachStorageAndDuplicatesRevealNoExistence() throws Exception {
        var mvc = mvc(new Actor("SERVICE", "billing-service", null, null));
        for (String body : List.of("{}", command().replace("\"orderId\":17", "\"orderId\":0"),
                command().replace("\"orderVersion\":4", "\"orderVersion\":-1"),
                command().replace("\"REDACTION\"", "\"UNKNOWN\""), command().replace(generation.toString(), "invalid"))) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(jobs);
        when(jobs.invalidate(17, generation, 4, REDACTION)).thenReturn(true, false);
        for (int n = 0; n < 2; n++) mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(command()))
                .andExpect(status().isAccepted()).andExpect(content().string(""));
    }

    @Test void endpointIsAbsentUnlessExplicitlyEnabledAndEnablingDoesNotStartAWorker() {
        var context = new ApplicationContextRunner().withUserConfiguration(CommerceRetentionInvalidationController.class);
        context.run(c -> assertThat(c).doesNotHaveBean(CommerceRetentionInvalidationController.class));
        context.withPropertyValues("privacy.commerce-retention-invalidation.enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean(CommerceRetentionInvalidationController.class));
        context.withPropertyValues("privacy.commerce-retention-invalidation.enabled=true")
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .run(c -> { assertThat(c).hasSingleBean(CommerceRetentionInvalidationController.class);
                    assertThat(c).doesNotHaveBean(CommerceRetentionJobs.class); });
    }
}
