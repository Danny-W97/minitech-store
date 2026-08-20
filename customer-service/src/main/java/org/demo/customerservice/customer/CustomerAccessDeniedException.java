package org.demo.customerservice.customer;

public class CustomerAccessDeniedException extends RuntimeException {
    public CustomerAccessDeniedException() {
        super("You can access only your own customer profile");
    }
}
