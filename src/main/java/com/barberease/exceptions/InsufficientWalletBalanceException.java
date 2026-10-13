package com.barberease.exceptions;

import java.math.BigDecimal;

public class InsufficientWalletBalanceException extends RuntimeException {
    private final BigDecimal requiredAmount;
    private final BigDecimal currentBalance;

    public InsufficientWalletBalanceException(BigDecimal requiredAmount, BigDecimal currentBalance) {
        super(String.format("Your wallet balance is insufficient to book this service. Required: ₹%.2f, Available: ₹%.2f. Please recharge your wallet and try again.", 
                requiredAmount != null ? requiredAmount : BigDecimal.ZERO, 
                currentBalance != null ? currentBalance : BigDecimal.ZERO));
        this.requiredAmount = requiredAmount != null ? requiredAmount : BigDecimal.ZERO;
        this.currentBalance = currentBalance != null ? currentBalance : BigDecimal.ZERO;
    }

    public BigDecimal getRequiredAmount() {
        return requiredAmount;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }
}
