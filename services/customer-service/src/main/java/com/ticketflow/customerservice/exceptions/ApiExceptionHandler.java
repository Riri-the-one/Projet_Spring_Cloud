package com.ticketflow.customerservice.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;
import java.util.Locale;

/**
 * Maps domain exceptions to RFC 9457 problem responses.
 * The parent class already handles validation errors (400).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(CustomerNotFoundException.class)
    ProblemDetail handleNotFound(CustomerNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(DuplicateEmailException.class)
    ProblemDetail handleDuplicate(DuplicateEmailException ex) {
        return problem(HttpStatus.CONFLICT, "CUSTOMER_EMAIL_CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(DuplicateIdentityException.class)
    ProblemDetail handleIdentity(DuplicateIdentityException ex) {
        return problem(HttpStatus.CONFLICT, "CUSTOMER_IDENTITY_CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(CustomerProfileNotFoundException.class)
    ProblemDetail handleMissingProfile(CustomerProfileNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "CUSTOMER_PROFILE_NOT_FOUND", ex.getMessage());
    }

    // This handler runs after the transactional service proxy has rolled back. Never catch and
    // continue a failed database transaction, and never expose the SQL/error message to the caller.
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleIntegrity(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                String name = violation.getConstraintName().toLowerCase(Locale.ROOT);
                if (name.contains("uk_customer_identity")) {
                    return handleIdentity(new DuplicateIdentityException());
                }
                if (name.contains("uk_customer_email")) { return handleDuplicate(new DuplicateEmailException(null)); }
            }
        }
        return problem(HttpStatus.BAD_REQUEST, "CUSTOMER_INTEGRITY_ERROR", "Invalid profile data");
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
