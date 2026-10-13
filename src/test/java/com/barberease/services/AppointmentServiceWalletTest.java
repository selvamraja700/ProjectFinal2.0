package com.barberease.services;

import com.barberease.exceptions.InsufficientWalletBalanceException;
import com.barberease.models.*;
import com.barberease.repositories.*;
import com.barberease.websocket.BarberWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AppointmentServiceWalletTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private ChairRepository chairRepository;

    @Mock
    private TokenRepository tokenRepository;

    @Mock
    private QueueRepository queueRepository;

    @Mock
    private WalletService walletService;

    @Mock
    private BarberWebSocketHandler webSocketHandler;

    @InjectMocks
    private AppointmentService appointmentService;

    private Customer customer;
    private com.barberease.models.Service haircutService;
    private Chair chair1;

    @BeforeEach
    void setUp() {
        customer = new Customer();
        customer.setId(1L);
        customer.setName("Alice");
        customer.setEmail("alice@example.com");
        customer.setWalletBalance(new BigDecimal("150.00"));

        haircutService = new com.barberease.models.Service();
        haircutService.setId(1L);
        haircutService.setName("Haircut");
        haircutService.setPrice(new BigDecimal("120.00"));
        haircutService.setDurationMinutes(30);
        haircutService.setActive(true);

        chair1 = new Chair();
        chair1.setId(1L);
        chair1.setChairNumber(1);
        chair1.setStatus("available");
        chair1.setActive(true);
    }

    @Test
    @DisplayName("bookSlot: Succeeds and debits wallet when balance is sufficient")
    void testBookSlotSufficientBalance() {
        LocalDate date = LocalDate.now();
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(serviceRepository.findById(1L)).thenReturn(Optional.of(haircutService));
        when(appointmentRepository.findByAppointmentDateOrderByTimeSlotAsc(date)).thenReturn(new ArrayList<>());
        when(chairRepository.findByChairNumber(1)).thenReturn(Optional.of(chair1));
        when(tokenRepository.findFirstByTokenDateOrderByIdDesc(date)).thenReturn(Optional.empty());
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(i -> i.getArgument(0));

        Appointment booked = appointmentService.bookSlot(1L, 1L, date, "10:00 AM - 11:00 AM", "VIP", "wallet");

        assertNotNull(booked);
        assertEquals("confirmed", booked.getStatus());
        assertEquals("T001", booked.getTokenNumber());
        verify(walletService).debitForBooking(eq(1L), eq(new BigDecimal("120.00")), eq("T001"), any());
        verify(appointmentRepository).save(any(Appointment.class));
        verify(tokenRepository).save(any(Token.class));
    }

    @Test
    @DisplayName("bookSlot: Fails and throws InsufficientWalletBalanceException without creating appointment")
    void testBookSlotInsufficientBalance() {
        LocalDate date = LocalDate.now();
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(serviceRepository.findById(1L)).thenReturn(Optional.of(haircutService));
        when(appointmentRepository.findByAppointmentDateOrderByTimeSlotAsc(date)).thenReturn(new ArrayList<>());
        when(tokenRepository.findFirstByTokenDateOrderByIdDesc(date)).thenReturn(Optional.empty());

        doThrow(new InsufficientWalletBalanceException(new BigDecimal("120.00"), new BigDecimal("50.00")))
                .when(walletService).debitForBooking(eq(1L), eq(new BigDecimal("120.00")), any(), any());

        assertThrows(InsufficientWalletBalanceException.class, () -> {
            appointmentService.bookSlot(1L, 1L, date, "10:00 AM - 11:00 AM", "VIP", "wallet");
        });

        // Verify appointment was NOT saved and token was NOT created
        verify(appointmentRepository, never()).save(any());
        verify(tokenRepository, never()).save(any());
    }
}
