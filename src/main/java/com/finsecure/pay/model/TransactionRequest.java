package com.finsecure.pay.model;

import java.math.BigDecimal;

public record TransactionRequest(
    String transactionId,
    BigDecimal amount,
    String currency,
    String merchantId
) {}