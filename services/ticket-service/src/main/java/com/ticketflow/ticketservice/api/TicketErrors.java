package com.ticketflow.ticketservice.api;

import com.ticketflow.ticketservice.security.CustomerIdentityResolver.CustomerResolutionException;
import com.ticketflow.ticketservice.service.TicketQueryService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class TicketErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler(TicketQueryService.NotFound.class)
    ProblemDetail missing() { return problem(404, "TICKET_NOT_FOUND", "Ticket or issuance not found"); }
    @ExceptionHandler(CustomerResolutionException.class)
    ProblemDetail customer(CustomerResolutionException ex) {
        return ex.missing() ? problem(404, "CUSTOMER_PROFILE_NOT_FOUND", "Associated Customer profile required")
                : problem(503, "CUSTOMER_RESOLUTION_UNAVAILABLE", "Customer identity resolution unavailable");
    }
    private ProblemDetail problem(int status, String code, String detail) {
        var result = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        result.setProperty("code", code); return result;
    }
}
