package com.finsecure.pay.model;

import java.util.List;

public record ValidationResponse(
    boolean valid,
    String transactionId,
    List<String> errors
) {}