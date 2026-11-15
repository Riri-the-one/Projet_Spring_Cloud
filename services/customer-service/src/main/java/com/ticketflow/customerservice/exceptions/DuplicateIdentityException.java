package com.ticketflow.customerservice.exceptions;

public class DuplicateIdentityException extends RuntimeException {
    public DuplicateIdentityException() { super("A profile is already associated with this identity"); }
}
