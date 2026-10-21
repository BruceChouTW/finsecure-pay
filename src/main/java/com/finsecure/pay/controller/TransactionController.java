package com.finsecure.pay.controller;

import com.finsecure.pay.model.TransactionRequest;
import com.finsecure.pay.model.ValidationResponse;
import com.finsecure.pay.service.TransactionValidationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class TransactionController {

    private final TransactionValidationService validationService;

    public TransactionController(
            TransactionValidationService validationService) {
        this.validationService = validationService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @PostMapping("/validate")
    public ResponseEntity<ValidationResponse> validate(
            @RequestBody(required = false) TransactionRequest request) {

        ValidationResponse result = validationService.validate(request);

        if (!result.valid()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(result);
        }

        return ResponseEntity.ok(result);
    }
}