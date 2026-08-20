package org.demo.returnsservice.returns;

import org.demo.returnsservice.web.CreateReturnRequest;
import org.demo.returnsservice.web.ReturnResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
public class ReturnService {
    private final ReturnRequestRepository repository;
    public ReturnService(ReturnRequestRepository repository) { this.repository = repository; }

    @Transactional
    public ReturnResponse create(CreateReturnRequest request) {
        return repository.findByOrderIdAndCustomerIdAndRequestType(request.orderId(), request.customerId(), request.requestType())
                .map(ReturnResponse::from)
                .orElseGet(() -> ReturnResponse.from(repository.save(new ReturnRequest(request.orderId(), request.customerId(),
                        request.customerEmail(), request.requestType(), request.reason(), request.description()))));
    }
    @Transactional(readOnly = true) public List<ReturnResponse> forCustomer(Long customerId) {
        return repository.findAllByCustomerIdOrderByCreatedAtDesc(customerId).stream().map(ReturnResponse::from).toList();
    }
    @Transactional(readOnly = true) public List<ReturnResponse> all() {
        return repository.findAllByOrderByCreatedAtDesc().stream().map(ReturnResponse::from).toList();
    }
    @Transactional(readOnly = true) public ReturnResponse get(Long id) {
        return repository.findById(id).map(ReturnResponse::from).orElseThrow(() -> notFound(id));
    }
    @Transactional public ReturnResponse changeStatus(Long id, ReturnStatus status, String note) {
        ReturnRequest request = repository.findById(id).orElseThrow(() -> notFound(id));
        request.changeStatus(status, note); return ReturnResponse.from(request);
    }
    private ResponseStatusException notFound(Long id) { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleziono zgłoszenia " + id + "."); }
}
