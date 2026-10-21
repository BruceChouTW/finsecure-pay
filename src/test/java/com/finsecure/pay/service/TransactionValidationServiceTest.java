package com.finsecure.pay.service;

import com.finsecure.pay.model.TransactionRequest;
import com.finsecure.pay.model.ValidationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class TransactionValidationServiceTest {

    private TransactionValidationService service;

    @BeforeEach
    void setUp() {
        service = new TransactionValidationService();
    }

    @Test
    void shouldAcceptValidTransaction() {
        var request = new TransactionRequest(
            "TXN-1001",
            new BigDecimal("25.50"),
            "SGD",
            "MERCHANT-001"
        );

        ValidationResponse result = service.validate(request);

        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void shouldRejectNegativeAmount() {
        var request = new TransactionRequest(
            "TXN-1002",
            new BigDecimal("-10.00"),
            "SGD",
            "MERCHANT-001"
        );

        ValidationResponse result = service.validate(request);

        assertFalse(result.valid());
        assertTrue(result.errors().contains(
            "Amount must be greater than zero"
        ));
    }

    @Test
    void shouldRejectUnsupportedCurrency() {
        var request = new TransactionRequest(
            "TXN-1003",
            new BigDecimal("10.00"),
            "XYZ",
            "MERCHANT-001"
        );

        ValidationResponse result = service.validate(request);

        assertFalse(result.valid());
        assertTrue(result.errors().contains(
            "Currency must be SGD, USD, or EUR"
        ));
    }

    @Test
    void shouldRejectMissingTransactionId() {
        var request = new TransactionRequest(
            " ",
            new BigDecimal("10.00"),
            "SGD",
            "MERCHANT-001"
        );

        ValidationResponse result = service.validate(request);

        assertFalse(result.valid());
        assertTrue(result.errors().contains(
            "Transaction ID is required"
        ));
    }
}