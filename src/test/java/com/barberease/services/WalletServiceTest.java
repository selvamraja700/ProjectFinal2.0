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
        mockCustomer.setWalletBalance(new BigDecimal("150.00"));

        mockWallet = new Wallet(mockCustomer, new BigDecimal("150.00"));
        mockWallet.setId(10L);
    }

    @Test
    @DisplayName("Recharge: ₹100 minimum is accepted")
    void testRechargeMinAccepted() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(mockCustomer));
        when(walletRepository.findByCustomerIdForUpdate(1L)).thenReturn(Optional.of(mockWallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(i -> i.getArgument(0));

        Wallet result = walletService.rechargeWallet(1L, new BigDecimal("100.00"));

        assertNotNull(result);
        assertEquals(new BigDecimal("250.00"), result.getBalance());
        assertEquals(new BigDecimal("250.00"), mockCustomer.getWalletBalance());

        ArgumentCaptor<WalletTransaction> txCaptor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(walletTransactionRepository).save(txCaptor.capture());
        WalletTransaction tx = txCaptor.getValue();
        assertEquals("CREDIT", tx.getType());
        assertEquals(new BigDecimal("100.00"), tx.getAmount());
        assertEquals(new BigDecimal("250.00"), tx.getBalanceAfter());
    }

    @Test
    @DisplayName("Recharge: ₹2,000 maximum is accepted")
    void testRechargeMaxAccepted() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(mockCustomer));
        when(walletRepository.findByCustomerIdForUpdate(1L)).thenReturn(Optional.of(mockWallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(i -> i.getArgument(0));

        Wallet result = walletService.rechargeWallet(1L, new BigDecimal("2000.00"));

        assertNotNull(result);
        assertEquals(new BigDecimal("2150.00"), result.getBalance());
        verify(walletTransactionRepository).save(any(WalletTransaction.class));
    }

    @Test
    @DisplayName("Recharge: ₹99 below minimum is rejected")
    void testRechargeBelowMinRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            walletService.rechargeWallet(1L, new BigDecimal("99.00"));
        });

        assertTrue(ex.getMessage().contains("Minimum recharge"));
        verify(walletRepository, never()).save(any());
        verify(walletTransactionRepository, never()).save(any());
        assertEquals(new BigDecimal("150.00"), mockWallet.getBalance());
    }

    @Test
    @DisplayName("Recharge: ₹2,001 above maximum is rejected")
    void testRechargeAboveMaxRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            walletService.rechargeWallet(1L, new BigDecimal("2001.00"));
        });

        assertTrue(ex.getMessage().contains("Maximum recharge"));
        verify(walletRepository, never()).save(any());
        verify(walletTransactionRepository, never()).save(any());
        assertEquals(new BigDecimal("150.00"), mockWallet.getBalance());
    }

    @Test
    @DisplayName("Recharge: Null or negative amount is rejected")
    void testRechargeNullOrNegativeRejected() {
        assertThrows(IllegalArgumentException.class, () -> {
            walletService.rechargeWallet(1L, null);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            walletService.rechargeWallet(1L, new BigDecimal("-50.00"));
        });

        verify(walletRepository, never()).save(any());
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Debit: Wallet balance > service price succeeds")
    void testDebitSufficientBalance() {
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
