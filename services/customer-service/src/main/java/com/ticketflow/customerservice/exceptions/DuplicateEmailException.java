package com.ticketflow.customerservice.exceptions;

public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String email) {
        super("This email is unavailable");
    }
}
