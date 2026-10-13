package com.barberease.services;

import com.barberease.exceptions.InsufficientWalletBalanceException;
import com.barberease.models.Customer;
import com.barberease.models.Wallet;
import com.barberease.models.WalletTransaction;
import com.barberease.repositories.CustomerRepository;
import com.barberease.repositories.WalletRepository;
import com.barberease.repositories.WalletTransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@Transactional
public class WalletService {

    public static final BigDecimal MIN_RECHARGE = new BigDecimal("100.00");
    public static final BigDecimal MAX_RECHARGE = new BigDecimal("2000.00");

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    @Autowired
    private CustomerRepository customerRepository;

    /**
     * Retrieves or creates a Wallet entity for the given customer.
     */
    public Wallet getOrCreateWallet(Long customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));

        return walletRepository.findByCustomerId(customerId)
                .orElseGet(() -> {
                    BigDecimal initBal = customer.getWalletBalance() != null 
                            ? customer.getWalletBalance() 
                            : new BigDecimal("100.00");
                    Wallet w = new Wallet(customer, initBal);
                    return walletRepository.save(w);
                });
    }

    /**
     * Gets current wallet balance for customer.
     */
    @Transactional(readOnly = true)
    public BigDecimal getBalance(Long customerId) {
        return walletRepository.findByCustomerId(customerId)
                .map(Wallet::getBalance)
                .orElseGet(() -> {
                    Customer customer = customerRepository.findById(customerId)
                            .orElseThrow(() -> new IllegalArgumentException("Customer not found"));
                    return customer.getWalletBalance() != null ? customer.getWalletBalance() : new BigDecimal("100.00");
                });
    }

    /**
     * Recharges user wallet with validation (₹100 to ₹2000 inclusive).
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Wallet rechargeWallet(Long customerId, BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("Recharge amount is required.");
        }

        // Standardize scale to 2 decimal places
        BigDecimal cleanAmount = amount.setScale(2, RoundingMode.HALF_UP);

        if (cleanAmount.compareTo(MIN_RECHARGE) < 0) {
            throw new IllegalArgumentException("Minimum recharge amount is ₹100.00.");
        }
        if (cleanAmount.compareTo(MAX_RECHARGE) > 0) {
            throw new IllegalArgumentException("Maximum recharge amount is ₹2,000.00 per transaction.");
        }

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));

        Wallet wallet = walletRepository.findByCustomerIdForUpdate(customerId)
                .orElseGet(() -> {
                    BigDecimal initBal = customer.getWalletBalance() != null 
                            ? customer.getWalletBalance() 
                            : new BigDecimal("100.00");
                    Wallet w = new Wallet(customer, initBal);
                    return walletRepository.save(w);
                });

        BigDecimal newBalance = wallet.getBalance().add(cleanAmount);
        wallet.setBalance(newBalance);
        wallet = walletRepository.save(wallet);

        // Keep customer table wallet_balance in sync
        customer.setWalletBalance(newBalance);
        customerRepository.save(customer);

        // Audit transaction record
        String ref = "REC-" + System.currentTimeMillis();
        WalletTransaction tx = new WalletTransaction(
                wallet,
                customer,
                "CREDIT",
                cleanAmount,
                newBalance,
                ref,
                "Wallet recharge (Development credit)"
        );
        walletTransactionRepository.save(tx);

        return wallet;
    }

    /**
     * Debits wallet for service booking atomically.
     * Uses pessimistic locking to prevent race conditions or double debits.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal debitForBooking(Long customerId, BigDecimal servicePrice, String bookingReference, String description) {
        if (servicePrice == null || servicePrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Invalid service price.");
        }

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));

        // Retrieve and lock wallet record
        Wallet wallet = walletRepository.findByCustomerIdForUpdate(customerId)
                .orElseGet(() -> {
                    BigDecimal initBal = customer.getWalletBalance() != null 
                            ? customer.getWalletBalance() 
                            : new BigDecimal("100.00");
                    Wallet w = new Wallet(customer, initBal);
                    return walletRepository.save(w);
                });

        BigDecimal currentBalance = wallet.getBalance();

        // Enforce balance check
        if (currentBalance.compareTo(servicePrice) < 0) {
            throw new InsufficientWalletBalanceException(servicePrice, currentBalance);
        }

        BigDecimal newBalance = currentBalance.subtract(servicePrice);
        wallet.setBalance(newBalance);
        walletRepository.save(wallet);

        // Keep customer record in sync
        customer.setWalletBalance(newBalance);
        customerRepository.save(customer);

        // Record debit transaction
        WalletTransaction tx = new WalletTransaction(
                wallet,
                customer,
                "DEBIT",
                servicePrice,
                newBalance,
                bookingReference,
                description != null ? description : "Payment for service booking " + bookingReference
        );
        walletTransactionRepository.save(tx);

        return newBalance;
    }

    /**
     * Returns transaction history for customer.
     */
    @Transactional(readOnly = true)
    public List<WalletTransaction> getTransactions(Long customerId) {
        return walletTransactionRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }
}
