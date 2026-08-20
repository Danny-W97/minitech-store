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
    @Autowired
    private ReturnService returnService;

    @Autowired
    private ReturnRequestRepository returnRequestRepository;

    @Test
    void contextLoads() {
    }

    @Test
    void createsOnlyOneRequestForTheSameOrderCustomerAndType() {
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
