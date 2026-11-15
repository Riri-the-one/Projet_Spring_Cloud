package com.ticketflow.ticketservice.service;
public class ConfirmationConflictException extends RuntimeException {
    public ConfirmationConflictException() { super("Confirmation conflicts with an existing issuance"); }
}
