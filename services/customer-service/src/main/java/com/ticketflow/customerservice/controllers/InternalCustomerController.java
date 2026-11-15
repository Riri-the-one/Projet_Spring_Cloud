package com.ticketflow.customerservice.controllers;

import com.ticketflow.customerservice.dto.CustomerIdentityResponse;
import com.ticketflow.customerservice.service.CustomerService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalCustomerController {
    private final CustomerService service;

    public InternalCustomerController(CustomerService service) { this.service = service; }

    @GetMapping("/internal/customers/me")
    ResponseEntity<CustomerIdentityResponse> resolve() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CustomerIdentityResponse(service.me().getId()));
    }
}
