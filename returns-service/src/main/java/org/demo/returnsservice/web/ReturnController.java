package org.demo.returnsservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.demo.returnsservice.returns.ReturnService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/returns")
@Tag(name = "Returns", description = "Returns and complaints")
public class ReturnController {
    private final ReturnService service;
    public ReturnController(ReturnService service) { this.service = service; }
    @PostMapping @Operation(summary = "Create a return or complaint")
    public ResponseEntity<ReturnResponse> create(@Valid @RequestBody CreateReturnRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }
    @GetMapping("/customer/{customerId}") public List<ReturnResponse> customer(@PathVariable @Positive Long customerId) { return service.forCustomer(customerId); }
    @GetMapping public List<ReturnResponse> all() { return service.all(); }
    @GetMapping("/{id}") public ReturnResponse get(@PathVariable @Positive Long id) { return service.get(id); }
    @PatchMapping("/{id}/status") public ReturnResponse status(@PathVariable @Positive Long id, @Valid @RequestBody ChangeReturnStatusRequest request) {
        return service.changeStatus(id, request.status(), request.note());
    }
}
