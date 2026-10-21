package com.finsecure.pay.service;

import com.finsecure.pay.model.TransactionRequest;
import com.finsecure.pay.model.ValidationResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class TransactionValidationService {

    private static final Set<String> ALLOWED_CURRENCIES =
        Set.of("SGD", "USD", "EUR");

    public ValidationResponse validate(TransactionRequest request) {
        List<String> errors = new ArrayList<>();

        if (request == null) {
            return new ValidationResponse(
                false, null, List.of("Transaction is required")
            );
        }

        if (request.transactionId() == null
                || request.transactionId().isBlank()) {
            errors.add("Transaction ID is required");
        }

        if (request.amount() == null
                || request.amount().signum() <= 0) {
            errors.add("Amount must be greater than zero");
        }

        if (request.currency() == null
                || !ALLOWED_CURRENCIES.contains(request.currency())) {
            errors.add("Currency must be SGD, USD, or EUR");
        }

        if (request.merchantId() == null
                || request.merchantId().isBlank()) {
            errors.add("Merchant ID is required");
        }

        return new ValidationResponse(
            errors.isEmpty(),
            request.transactionId(),
            List.copyOf(errors)
        );
    }
}