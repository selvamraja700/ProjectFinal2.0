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

    public static final BigDecimal MAX_RECHARGE_AMOUNT = new BigDecimal("2000.00");
    public static final String REQUIRED_COUPON_CODE = "BARBER 50";

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    @Autowired
    private CustomerRepository customerRepository;

    /**
     * Retrieves or creates a Wallet entity for the given customer. Default balance is 0.00.
     */
    public Wallet getOrCreateWallet(Long customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));

        return walletRepository.findByCustomerId(customerId)
                .orElseGet(() -> {
                    BigDecimal initBal = customer.getWalletBalance() != null 
                            ? customer.getWalletBalance() 
                            : BigDecimal.ZERO;
                    Wallet w = new Wallet(customer, initBal);
                    return walletRepository.save(w);
                });
    }

    /**
     * Gets current wallet balance for customer. Default is 0.00.
     */
    @Transactional(readOnly = true)
    public BigDecimal getBalance(Long customerId) {
        return walletRepository.findByCustomerId(customerId)
                .map(Wallet::getBalance)
                .orElseGet(() -> {
                    Customer customer = customerRepository.findById(customerId)
                            .orElseThrow(() -> new IllegalArgumentException("Customer not found"));
                    return customer.getWalletBalance() != null ? customer.getWalletBalance() : BigDecimal.ZERO;
                });
    }

    /**
     * Recharges user wallet with user entered amount (up to ₹2,000) and coupon validation.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Wallet rechargeWallet(Long customerId, BigDecimal amount, String couponCode) {
        if (amount == null) {
            throw new IllegalArgumentException("Invalid Amount: Recharge amount is required.");
        }

        // Standardize scale to 2 decimal places
        BigDecimal cleanAmount = amount.setScale(2, RoundingMode.HALF_UP);

        if (cleanAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Invalid Amount: Please enter a valid recharge amount greater than ₹0.");
        }

        if (cleanAmount.compareTo(MAX_RECHARGE_AMOUNT) > 0) {
            throw new IllegalArgumentException("Invalid Amount: Maximum recharge amount is ₹2,000. Please enter a valid amount up to ₹2,000.");
        }

        if (couponCode == null || couponCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid Coupon Code: Please enter the correct coupon code to recharge your wallet.");
        }

        String normCode = couponCode.trim().replaceAll("\\s+", " ").toUpperCase();
        if (!"BARBER 50".equals(normCode) && !"BARBER50".equals(normCode)) {
            throw new IllegalArgumentException("Invalid Coupon Code: Please enter the correct coupon code to recharge your wallet.");
        }

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));

        Wallet wallet = walletRepository.findByCustomerIdForUpdate(customerId)
                .orElseGet(() -> {
                    BigDecimal initBal = customer.getWalletBalance() != null 
                            ? customer.getWalletBalance() 
                            : BigDecimal.ZERO;
                    Wallet w = new Wallet(customer, initBal);
                    return walletRepository.save(w);
                });

        BigDecimal currentBalance = wallet.getBalance() != null ? wallet.getBalance() : BigDecimal.ZERO;
        BigDecimal newBalance = currentBalance.add(cleanAmount);

        if (newBalance.compareTo(MAX_RECHARGE_AMOUNT) > 0) {
            BigDecimal maxAllowed = MAX_RECHARGE_AMOUNT.subtract(currentBalance);
            if (maxAllowed.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Invalid Amount: Wallet balance is already at the maximum limit of ₹2,000.");
            } else {
                throw new IllegalArgumentException("Invalid Amount: Total wallet balance cannot exceed ₹2,000. Maximum valid amount to enter is ₹" + maxAllowed + ".");
            }
        }

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
                "Wallet recharge of ₹" + cleanAmount + " with coupon " + couponCode.trim() + " (Development/Demo credit)"
        );
        walletTransactionRepository.save(tx);

        return wallet;
    }

    public Wallet rechargeWallet(Long customerId, BigDecimal amount) {
        return rechargeWallet(customerId, amount, null);
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
                            : BigDecimal.ZERO;
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
