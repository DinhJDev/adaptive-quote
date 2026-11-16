package dev.dinhj.adaptivequote.web;

import dev.dinhj.adaptivequote.quote.QuotePricing.InvalidSelection;
import dev.dinhj.adaptivequote.quote.QuoteReceipts.ReceiptFailure;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {QuoteApi.class, QuoteReceiptApi.class, QuoteLinkApi.class})
class ApiErrors {
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> invalidRequest() {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Check the quote request and try again.");
    }

    @ExceptionHandler(InvalidSelection.class)
    ResponseEntity<ProblemDetail> invalidSelection(InvalidSelection exception) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_SELECTION", exception.getMessage());
    }

    @ExceptionHandler(ReceiptFailure.class)
    ResponseEntity<ProblemDetail> receiptFailure(ReceiptFailure exception) {
        return problem(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> storageUnavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "Quote storage is temporarily unavailable.");
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore()).body(problem);
    }
}
