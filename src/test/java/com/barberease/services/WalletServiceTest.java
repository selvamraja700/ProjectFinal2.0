package com.barberease.services;

import com.barberease.exceptions.InsufficientWalletBalanceException;
import com.barberease.models.Customer;
import com.barberease.models.Wallet;
import com.barberease.models.WalletTransaction;
import com.barberease.repositories.CustomerRepository;
import com.barberease.repositories.WalletRepository;
import com.barberease.repositories.WalletTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class WalletServiceTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private WalletTransactionRepository walletTransactionRepository;

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private WalletService walletService;

    private Customer mockCustomer;
    private Wallet mockWallet;

    @BeforeEach
    void setUp() {
        mockCustomer = new Customer();
        mockCustomer.setId(1L);
        mockCustomer.setName("John Doe");
        mockCustomer.setEmail("john@example.com");
        mockCustomer.setPhone("9876543210");
        mockCustomer.setWalletBalance(BigDecimal.ZERO);

        mockWallet = new Wallet(mockCustomer, BigDecimal.ZERO);
        mockWallet.setId(10L);
    }

    @Test
    @DisplayName("Default balance is 0.00 for new wallet")
    void testDefaultBalanceIsZero() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(mockCustomer));
        when(walletRepository.findByCustomerId(1L)).thenReturn(Optional.empty());
        when(walletRepository.save(any(Wallet.class))).thenAnswer(i -> i.getArgument(0));

        Wallet created = walletService.getOrCreateWallet(1L);
        assertNotNull(created);
        assertEquals(BigDecimal.ZERO, created.getBalance());
    }

    @Test
    @DisplayName("Recharge ₹500 with BARBER 50: Accepted")
    void testRechargeSuccessWith500AndBarber50WithSpace() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(mockCustomer));
        when(walletRepository.findByCustomerIdForUpdate(1L)).thenReturn(Optional.of(mockWallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(i -> i.getArgument(0));

        Wallet result = walletService.rechargeWallet(1L, new BigDecimal("500.00"), "BARBER 50");

        assertNotNull(result);
        assertEquals(new BigDecimal("500.00"), result.getBalance());
        assertEquals(new BigDecimal("500.00"), mockCustomer.getWalletBalance());

        ArgumentCaptor<WalletTransaction> txCaptor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(walletTransactionRepository).save(txCaptor.capture());
        WalletTransaction tx = txCaptor.getValue();
        assertEquals("CREDIT", tx.getType());
        assertEquals(new BigDecimal("500.00"), tx.getAmount());
        assertEquals(new BigDecimal("500.00"), tx.getBalanceAfter());
    }

    @Test
    @DisplayName("Recharge ₹2,000 with BARBER50 (no space): Accepted")
    void testRechargeSuccessWith2000AndBarber50NoSpace() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(mockCustomer));
        when(walletRepository.findByCustomerIdForUpdate(1L)).thenReturn(Optional.of(mockWallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(i -> i.getArgument(0));

        Wallet result = walletService.rechargeWallet(1L, new BigDecimal("2000.00"), "BARBER50");

        assertNotNull(result);
        assertEquals(new BigDecimal("2000.00"), result.getBalance());
        assertEquals(new BigDecimal("2000.00"), mockCustomer.getWalletBalance());
    }

    @Test
    @DisplayName("Amount > ₹2,000 is rejected with valid amount message")
    void testRechargeAmountGreaterThan2000Rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            walletService.rechargeWallet(1L, new BigDecimal("2500.00"), "BARBER50");
        });

        assertTrue(ex.getMessage().contains("Maximum recharge amount is ₹2,000"));
        verify(walletRepository, never()).save(any());
        verify(walletTransactionRepository, never()).save(any());
        assertEquals(BigDecimal.ZERO, mockWallet.getBalance());
    }

    @Test
    @DisplayName("Amount exceeding total ₹2,000 wallet limit is rejected with remaining valid amount")
    void testRechargeExceedingTotalLimitRejected() {
        mockWallet.setBalance(new BigDecimal("1500.00"));
        mockCustomer.setWalletBalance(new BigDecimal("1500.00"));

        when(customerRepository.findById(1L)).thenReturn(Optional.of(mockCustomer));
        when(walletRepository.findByCustomerIdForUpdate(1L)).thenReturn(Optional.of(mockWallet));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            walletService.rechargeWallet(1L, new BigDecimal("1000.00"), "BARBER50");
        });

        assertTrue(ex.getMessage().contains("Total wallet balance cannot exceed ₹2,000"));
        assertTrue(ex.getMessage().contains("500.00"));
        verify(walletRepository, never()).save(any());
    }

    @Test
    @DisplayName("Amount is ₹0 or negative or null: Rejected")
    void testRechargeAmountZeroOrNegativeOrNullRejected() {
        assertThrows(IllegalArgumentException.class, () -> {
            walletService.rechargeWallet(1L, BigDecimal.ZERO, "BARBER50");
        });

        assertThrows(IllegalArgumentException.class, () -> {
            walletService.rechargeWallet(1L, new BigDecimal("-50.00"), "BARBER50");
        });

        assertThrows(IllegalArgumentException.class, () -> {
            walletService.rechargeWallet(1L, null, "BARBER50");
        });

        verify(walletRepository, never()).save(any());
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Coupon is empty or null: Rejected")
    void testRechargeEmptyCouponRejected() {
        IllegalArgumentException exEmpty = assertThrows(IllegalArgumentException.class, () -> {
            walletService.rechargeWallet(1L, new BigDecimal("500.00"), "");
        });
        assertTrue(exEmpty.getMessage().contains("Invalid Coupon Code"));

        IllegalArgumentException exNull = assertThrows(IllegalArgumentException.class, () -> {
            walletService.rechargeWallet(1L, new BigDecimal("500.00"), null);
        });
        assertTrue(exNull.getMessage().contains("Invalid Coupon Code"));

        verify(walletRepository, never()).save(any());
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Coupon is BARBER 500 or BARBER 5: Rejected")
    void testRechargeCouponBarber500Rejected() {
        IllegalArgumentException ex500 = assertThrows(IllegalArgumentException.class, () -> {
            walletService.rechargeWallet(1L, new BigDecimal("500.00"), "BARBER 500");
        });
        assertEquals("Invalid Coupon Code: Please enter the correct coupon code to recharge your wallet.", ex500.getMessage());

        IllegalArgumentException ex5 = assertThrows(IllegalArgumentException.class, () -> {
            walletService.rechargeWallet(1L, new BigDecimal("500.00"), "BARBER 5");
        });
        assertEquals("Invalid Coupon Code: Please enter the correct coupon code to recharge your wallet.", ex5.getMessage());

        verify(walletRepository, never()).save(any());
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Coupon is invalid: Balance remains unchanged")
    void testInvalidCouponLeavesBalanceUnchanged() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            walletService.rechargeWallet(1L, new BigDecimal("500.00"), "DISCOUNT50");
        });

        assertEquals("Invalid Coupon Code: Please enter the correct coupon code to recharge your wallet.", ex.getMessage());
        verify(walletRepository, never()).save(any());
        verify(walletTransactionRepository, never()).save(any());
        assertEquals(BigDecimal.ZERO, mockWallet.getBalance());
    }

    @Test
    @DisplayName("Debit: Wallet balance > service price succeeds")
    void testDebitSufficientBalance() {
        mockWallet.setBalance(new BigDecimal("150.00"));
        mockCustomer.setWalletBalance(new BigDecimal("150.00"));

        when(customerRepository.findById(1L)).thenReturn(Optional.of(mockCustomer));
        when(walletRepository.findByCustomerIdForUpdate(1L)).thenReturn(Optional.of(mockWallet));

        BigDecimal remaining = walletService.debitForBooking(1L, new BigDecimal("120.00"), "T001", "Haircut booking");

        assertEquals(new BigDecimal("30.00"), remaining);
        assertEquals(new BigDecimal("30.00"), mockWallet.getBalance());
        assertEquals(new BigDecimal("30.00"), mockCustomer.getWalletBalance());

        ArgumentCaptor<WalletTransaction> txCaptor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(walletTransactionRepository).save(txCaptor.capture());
        WalletTransaction tx = txCaptor.getValue();
        assertEquals("DEBIT", tx.getType());
        assertEquals(new BigDecimal("120.00"), tx.getAmount());
        assertEquals(new BigDecimal("30.00"), tx.getBalanceAfter());
        assertEquals("T001", tx.getBookingReference());
    }

    @Test
    @DisplayName("Debit: Wallet balance == service price succeeds with 0.00 balance")
    void testDebitExactBalanceBecomesZero() {
        mockWallet.setBalance(new BigDecimal("150.00"));
        mockCustomer.setWalletBalance(new BigDecimal("150.00"));

        when(customerRepository.findById(1L)).thenReturn(Optional.of(mockCustomer));
        when(walletRepository.findByCustomerIdForUpdate(1L)).thenReturn(Optional.of(mockWallet));

        BigDecimal remaining = walletService.debitForBooking(1L, new BigDecimal("150.00"), "T002", "Exact balance booking");

        assertEquals(new BigDecimal("0.00"), remaining);
        assertEquals(new BigDecimal("0.00"), mockWallet.getBalance());
        assertEquals(new BigDecimal("0.00"), mockCustomer.getWalletBalance());
        verify(walletTransactionRepository).save(any(WalletTransaction.class));
    }

    @Test
    @DisplayName("Debit: Wallet balance < service price throws InsufficientWalletBalanceException")
    void testDebitInsufficientBalanceRejected() {
        mockWallet.setBalance(new BigDecimal("150.00"));
        mockCustomer.setWalletBalance(new BigDecimal("150.00"));

        when(customerRepository.findById(1L)).thenReturn(Optional.of(mockCustomer));
        when(walletRepository.findByCustomerIdForUpdate(1L)).thenReturn(Optional.of(mockWallet));

        InsufficientWalletBalanceException ex = assertThrows(InsufficientWalletBalanceException.class, () -> {
            walletService.debitForBooking(1L, new BigDecimal("200.00"), "T003", "Failed booking");
        });

        assertEquals(new BigDecimal("200.00"), ex.getRequiredAmount());
        assertEquals(new BigDecimal("150.00"), ex.getCurrentBalance());

        // Verify balance left completely unchanged and no transactions saved
        assertEquals(new BigDecimal("150.00"), mockWallet.getBalance());
        assertEquals(new BigDecimal("150.00"), mockCustomer.getWalletBalance());
        verify(walletTransactionRepository, never()).save(any());
    }
}
