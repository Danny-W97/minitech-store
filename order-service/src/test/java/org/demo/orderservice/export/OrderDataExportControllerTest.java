package org.demo.orderservice.export;

import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.Actor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderDataExportControllerTest {
    private final OrderDataExportService service=mock(OrderDataExportService.class);
    private final Actor owner=new Actor("CUSTOMER",null,17L,"owner@fixture.example");
    private MockMvc mvc(Actor actor){return MockMvcBuilders.standaloneSetup(new OrderDataExportController(service))
            .setHandlerExceptionResolvers(new ResponseStatusExceptionResolver(),new DefaultHandlerExceptionResolver())
            .addFilters((request,response,chain)->{request.setAttribute(Access.ACTOR,actor);chain.doFilter(request,response);}).build();}
    @Test void headersAreOwnedAndPartialWithPrivateDownloadHeaders()throws Exception{
        when(service.orders(17,0,2)).thenReturn(new OrderDataExportPage(1,"orders-page",false,Instant.now(),List.of(),false,"0",List.of()));
        mvc(owner).perform(get("/orders/me/data-export?size=2&customerId=42").header("X-Customer-Id","42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.complete").value(false)).andExpect(jsonPath("$.scope").value("orders-page"))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("private")))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"orders-data-page.json\""))
                .andExpect(header().string("X-Content-Type-Options","nosniff"));
        verify(service).orders(17,0,2);
    }
    @Test void itemPagesUseTheSameVerifiedOwnerAndExplicitFields()throws Exception{
        when(service.items(17,99,0,2)).thenReturn(new OrderItemDataExportPage(1,"order-items-page",false,Instant.now(),99L,
                List.of(new OrderItemDataExportPage.Item(1L,101L,"SKU","Zażółć",2,new BigDecimal("12.34"),new BigDecimal("24.68"))),false,"0",List.of()));
        mvc(owner).perform(get("/orders/me/data-export/99/items?size=2&customerId=42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orderId").value(99)).andExpect(jsonPath("$.items[0].productName").value("Zażółć"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(12.34))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"order-items-data-page.json\""));
        verify(service).items(17,99,0,2);
    }
    @Test void rolesOtherThanCustomerAreDeniedForBothRoutes()throws Exception{
        for(var actor:List.of(Actor.ANONYMOUS,new Actor("OPERATOR",null,17L,"owner@fixture.example"),new Actor("SERVICE","payment-service",17L,"owner@fixture.example"),new Actor("CUSTOMER",null,null,"x")))
            for(var path:List.of("/orders/me/data-export","/orders/me/data-export/99/items"))mvc(actor).perform(get(path)).andExpect(status().is4xxClientError());
        verifyNoInteractions(service);
    }
    @Test void cursorOverflowIsRejectedBeforeEitherQuery()throws Exception{
        for(var path:List.of("/orders/me/data-export","/orders/me/data-export/99/items"))mvc(owner).perform(get(path+"?cursor=9223372036854775808")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void unknownOrUnownedOrderIsNotFoundInsteadOfAnEmptySuccessfulDownload()throws Exception{
        when(service.items(17,99,0,50)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found"));
        mvc(owner).perform(get("/orders/me/data-export/99/items")).andExpect(status().isNotFound());
    }
}
