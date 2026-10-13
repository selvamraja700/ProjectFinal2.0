package com.barberease.controllers;

import com.barberease.models.Customer;
import com.barberease.security.UserPrincipal;
import com.barberease.services.CustomerService;
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
@RequestMapping("/api/customers")
public class CustomerController {

    @Autowired
    private CustomerService customerService;

    private Long getAuthenticatedCustomerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal)) {
            throw new IllegalArgumentException("Authentication required");
        }
        return ((UserPrincipal) auth.getPrincipal()).getId();
    }

    @GetMapping("/profile")
    public ResponseEntity<Map<String, Object>> getProfile() {
        Long customerId = getAuthenticatedCustomerId();
        Customer customer = customerService.getProfile(customerId);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", customer);
        return ResponseEntity.ok(body);
    }

    @PutMapping("/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(@RequestBody Customer data) {
        Long customerId = getAuthenticatedCustomerId();
        Customer customer = customerService.updateProfile(customerId, data);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", customer);
        return ResponseEntity.ok(body);
    }

    @PutMapping("/profile/password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody Map<String, String> request) {
        Long customerId = getAuthenticatedCustomerId();
        String currentPassword = request.get("currentPassword");
        String newPassword = request.get("newPassword");

        customerService.changePassword(customerId, currentPassword, newPassword);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Password changed successfully");
        return ResponseEntity.ok(body);
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAll() {
        List<Customer> customers = customerService.getAllCustomers();

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", customers);
        return ResponseEntity.ok(body);
    }

    @PutMapping("/{id}/toggle")
    public ResponseEntity<Map<String, Object>> toggleStatus(@PathVariable Long id) {
        Customer customer = customerService.toggleCustomerStatus(id);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Customer status updated");
        body.put("data", customer);
        return ResponseEntity.ok(body);
    }

    @PutMapping("/profile/wallet")
    public ResponseEntity<Map<String, Object>> topUpWallet(@RequestBody Map<String, Object> request) {
        Long customerId = getAuthenticatedCustomerId();
        
        Object amountObj = request.get("amount");
        BigDecimal amount = BigDecimal.ZERO;
        if (amountObj != null) {
            if (amountObj instanceof Number) {
                amount = new BigDecimal(amountObj.toString());
            } else if (amountObj instanceof String) {
                amount = new BigDecimal((String) amountObj);
            }
        }

        Object couponObj = request.get("coupon_code");
        if (couponObj == null) {
            couponObj = request.get("couponCode");
        }
        if (couponObj == null) {
            couponObj = request.get("coupon");
        }
        String couponCode = couponObj != null ? couponObj.toString() : null;

        Customer customer = customerService.topUpWallet(customerId, amount, couponCode);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("title", "Wallet Recharge Successful!");
        body.put("message", "₹" + amount.setScale(2, java.math.RoundingMode.HALF_UP) + " has been added to your wallet successfully.");
        body.put("data", customer);
        return ResponseEntity.ok(body);
    }
}
