package org.demo.returnsservice;

import org.demo.returnsservice.returns.ReturnRequestRepository;
import org.demo.returnsservice.returns.ReturnService;
import org.demo.returnsservice.returns.ReturnStatus;
import org.demo.returnsservice.returns.ReturnType;
import org.demo.returnsservice.web.CreateReturnRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReturnsServiceApplicationTests {
    @org.junit.jupiter.api.BeforeEach void noHttpFixture() { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private org.demo.returnsservice.returns.ReturnOrderClient orders;
    @Autowired
    private ReturnService returnService;

    @Autowired
    private ReturnRequestRepository returnRequestRepository;

    @Test
    void contextLoads() {
    }

    @Test
    void createsOnlyOneRequestForTheSameOrderCustomerAndType() {
        org.mockito.Mockito.doReturn(new org.demo.returnsservice.returns.ReturnOrderClient.Snapshot(501L,42L,"customer@example.test","COMPLETED","PAID",false,
                java.util.List.of(new org.demo.returnsservice.returns.ReturnOrderClient.Line(1L,101L,"Product",1))))
                .when(orders).get(501L,42L,"customer@example.test");
        var request = new CreateReturnRequest(
                501L,
                42L,
                "customer@example.test",
                ReturnType.RETURN,
                "Produkt nie pasuje",
                "Proszę o przyjęcie zwrotu w terminie ustawowym."
        );

        var first = returnService.create(request);
        var repeated = returnService.create(request);

        assertThat(first.id()).isEqualTo(repeated.id());
        assertThat(returnRequestRepository.count()).isEqualTo(1);
        assertThat(first.status()).isEqualTo(ReturnStatus.REQUESTED);
    }
}
