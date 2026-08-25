package com.socp.platform.error.web;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void preservesResponseStatusExceptionHttpCodeAndReason() {
        var response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "案件标题不能为空"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getBody().code());
        assertEquals("案件标题不能为空", response.getBody().message());
    }

    @Test
    void mapsRequestValidationToBadRequestEnvelope() {
        var target = new Object();
        var binding = new BeanPropertyBindingResult(target, "request");
        binding.addError(new FieldError("request", "code", "code is required"));

        var response = handler.handleValidation(new MethodArgumentNotValidException(null, binding));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getBody().code());
        assertEquals("code: code is required", response.getBody().message());
    }
}
