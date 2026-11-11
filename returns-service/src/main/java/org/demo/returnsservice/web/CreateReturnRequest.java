package org.demo.returnsservice.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.demo.returnsservice.returns.ReturnType;
import jakarta.validation.Valid;
import java.util.List;

public record CreateReturnRequest(
        @NotNull @Positive Long orderId,
        @NotNull @Positive Long customerId,
        @NotBlank @Email @Size(max = 255) String customerEmail,
        @NotNull ReturnType requestType,
        @NotBlank @Size(max = 120) String reason,
        @NotBlank @Size(max = 2000) String description,
        @Size(min=1,max=100) List<@Valid Item> items
) {
    @io.swagger.v3.oas.annotations.media.Schema(name="ReturnItemSelection")
    public record Item(@NotNull @Positive Long orderItemId,@NotNull @Positive Integer quantity) {}
    public CreateReturnRequest(Long orderId,Long customerId,String customerEmail,ReturnType requestType,String reason,String description) {
        this(orderId,customerId,customerEmail,requestType,reason,description,null);
    }
}
