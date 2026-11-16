package com.ticketflow.notificationservice.api;
import com.ticketflow.notificationservice.service.NotificationQueries;
import com.ticketflow.notificationservice.security.CustomerIdentityResolver.CustomerResolutionException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
@RestControllerAdvice
public class NotificationErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class) ProblemDetail invalid(){return problem(400,"INVALID_PARAMETER");}
    @ExceptionHandler(NotificationQueries.Missing.class) ProblemDetail missing(){return problem(404,"NOTIFICATION_NOT_FOUND");}
    @ExceptionHandler(NotificationQueries.RetryConflict.class) ProblemDetail conflict(){return problem(409,"NOTIFICATION_NOT_DEAD");}
    @ExceptionHandler(CustomerResolutionException.class) ProblemDetail customer(CustomerResolutionException ex){return problem(ex.missing()?404:503,ex.missing()?"CUSTOMER_PROFILE_NOT_FOUND":"CUSTOMER_RESOLUTION_UNAVAILABLE");}
    ProblemDetail problem(int status,String code){var p=ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status),code);p.setProperty("code",code);return p;}
}
