package org.demo.paymentservice.billing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BillingDataRequest(
        boolean company,
        @NotBlank @Size(max = 200) String buyerName,
        @Size(max = 200) String companyName,
        @Pattern(regexp = "[0-9]{10}", message = "NIP musi składać się dokładnie z 10 cyfr") String taxId,
        @NotBlank @Size(max = 200) String street,
        @NotBlank @Size(max = 20) String postalCode,
        @NotBlank @Size(max = 120) String city,
        @NotBlank @Size(max = 80) String country
) {
    @JsonIgnore
    @AssertTrue(message = "Faktura firmowa wymaga nazwy firmy i 10-cyfrowego NIP")
    public boolean isCompanyDataValid() {
        if (!company) {
            return true;
        }
        return companyName != null
                && !companyName.isBlank()
                && taxId != null
                && taxId.matches("[0-9]{10}");
    }
}
