package com.barberease.controllers;

import com.barberease.models.Wallet;
import com.barberease.models.WalletTransaction;
import com.barberease.security.UserPrincipal;
import com.barberease.services.WalletService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    @Autowired
    private WalletService walletService;

    private Long getAuthenticatedCustomerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal)) {
            throw new IllegalArgumentException("Authentication required");
        }
        return ((UserPrincipal) auth.getPrincipal()).getId();
    }

    @GetMapping("/balance")
    public ResponseEntity<Map<String, Object>> getBalance() {
        Long customerId = getAuthenticatedCustomerId();
        BigDecimal balance = walletService.getBalance(customerId);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("balance", balance);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/recharge")
    public ResponseEntity<Map<String, Object>> recharge(@RequestBody Map<String, Object> request) {
        Long customerId = getAuthenticatedCustomerId();

        Object amountObj = request.get("amount");
        if (amountObj == null) {
            throw new IllegalArgumentException("Recharge amount is required.");
        }

        BigDecimal amount;
        try {
            if (amountObj instanceof Number) {
                amount = new BigDecimal(amountObj.toString());
            } else if (amountObj instanceof String) {
                String strVal = ((String) amountObj).trim();
                if (strVal.isEmpty()) {
                    throw new IllegalArgumentException("Recharge amount cannot be empty.");
                }
                amount = new BigDecimal(strVal);
            } else {
                throw new IllegalArgumentException("Invalid amount format.");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Please enter a valid numeric recharge amount.");
        }

        Object couponObj = request.get("coupon_code");
        if (couponObj == null) {
            couponObj = request.get("couponCode");
        }
        if (couponObj == null) {
            couponObj = request.get("coupon");
        }
        String couponCode = couponObj != null ? couponObj.toString() : null;

        Wallet wallet = walletService.rechargeWallet(customerId, amount, couponCode);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("title", "Wallet Recharge Successful!");
        body.put("message", "₹" + amount.setScale(2, java.math.RoundingMode.HALF_UP) + " has been added to your wallet successfully.");
        body.put("balance", wallet.getBalance());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/transactions")
    public ResponseEntity<Map<String, Object>> getTransactions() {
        Long customerId = getAuthenticatedCustomerId();
        List<WalletTransaction> transactions = walletService.getTransactions(customerId);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", transactions);
        return ResponseEntity.ok(body);
    }
}
