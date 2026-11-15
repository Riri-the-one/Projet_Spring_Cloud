package com.ticketflow.customerservice.exceptions;

public class CustomerProfileNotFoundException extends RuntimeException {
    public CustomerProfileNotFoundException() { super("No profile is associated with the authenticated identity"); }
}
