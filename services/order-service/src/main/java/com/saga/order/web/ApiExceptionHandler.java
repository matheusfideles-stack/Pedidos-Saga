package com.saga.order.web;

import com.saga.order.domain.IllegalTransitionException;
import com.saga.order.service.ApiExceptions.IdempotencyConflictException;
import com.saga.order.service.ApiExceptions.IdempotencyInProgressException;
import com.saga.order.service.ApiExceptions.OrderNotFoundException;
import com.saga.order.service.ApiExceptions.UnknownProductException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail idempotencyConflict(IdempotencyConflictException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", e.getMessage());
    }

    @ExceptionHandler(UnknownProductException.class)
    public ProblemDetail unknownProduct(UnknownProductException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "PRODUCT_NOT_AVAILABLE", e.getMessage());
    }

    @ExceptionHandler({IdempotencyInProgressException.class, IllegalTransitionException.class})
    public ProblemDetail conflict(RuntimeException e) {
        return problem(HttpStatus.CONFLICT, "CONFLICT", e.getMessage());
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail notFound(OrderNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("code", code);
        return pd;
    }
}
