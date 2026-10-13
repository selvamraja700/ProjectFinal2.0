package com.barberease.controllers;

import com.barberease.models.Appointment;
import com.barberease.security.UserPrincipal;
import com.barberease.services.AppointmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private com.barberease.services.WalletService walletService;

    private Long getAuthenticatedCustomerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal)) {
            throw new IllegalArgumentException("Authentication required");
        }
        return ((UserPrincipal) auth.getPrincipal()).getId();
    }

    private String getAuthenticatedUserType() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal)) {
            throw new IllegalArgumentException("Authentication required");
        }
        return ((UserPrincipal) auth.getPrincipal()).getType();
    }

    private Long parseId(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            return Long.parseLong((String) value);
        }
        throw new IllegalArgumentException("Invalid ID format");
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
        Long customerId = getAuthenticatedCustomerId();

        Long serviceId = parseId(request.get("service_id"));
        Long chairId = parseId(request.get("chair_id"));
        
        String dateStr = (String) request.get("appointment_date");
        LocalDate appointmentDate = LocalDate.parse(dateStr);

        String timeSlot = (String) request.get("time_slot");
        String notes = (String) request.get("notes");
        String paymentMethod = (String) request.get("payment_method");

        Appointment created = appointmentService.createAppointment(
                customerId, serviceId, chairId, appointmentDate, timeSlot, notes, paymentMethod);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Appointment confirmed");
        body.put("data", created);
        body.put("wallet_balance", walletService.getBalance(customerId));
        return new ResponseEntity<>(body, HttpStatus.CREATED);
    }

    @GetMapping("/my")
    public ResponseEntity<Map<String, Object>> getMy() {
        Long customerId = getAuthenticatedCustomerId();
        List<Appointment> appts = appointmentService.getCustomerAppointments(customerId);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", appts);
        return ResponseEntity.ok(body);
    }

    private LocalDate parseSafeDate(String dateStr) {
        if (dateStr == null) return LocalDate.now();
        String trimmed = dateStr.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) || "undefined".equalsIgnoreCase(trimmed)) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(trimmed);
        } catch (Exception e) {
            try {
                DateTimeFormatter[] formatters = new DateTimeFormatter[] {
                    DateTimeFormatter.ofPattern("yyyy-M-d"),
                    DateTimeFormatter.ofPattern("d-M-yyyy"),
                    DateTimeFormatter.ofPattern("M/d/yyyy")
                };
                for (DateTimeFormatter fmt : formatters) {
                    try {
                        return LocalDate.parse(trimmed, fmt);
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            return LocalDate.now();
        }
    }

    @GetMapping("/slots")
    public ResponseEntity<Map<String, Object>> getSlots(
            @RequestParam(name = "chairId", required = false) Long chairId,
            @RequestParam(name = "date", required = false) String dateStr) {
        
        LocalDate date = parseSafeDate(dateStr);
        List<String> bookedSlots = appointmentService.getBookedSlots(chairId, date);

        Map<String, Object> data = new HashMap<>();
        data.put("chairId", chairId);
        data.put("date", date.toString());
        data.put("bookedSlots", bookedSlots);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    private Long getOptionalAuthenticatedCustomerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && (auth.getPrincipal() instanceof UserPrincipal)) {
            return ((UserPrincipal) auth.getPrincipal()).getId();
        }
        return null;
    }

    @GetMapping("/slots/detailed")
    public ResponseEntity<Map<String, Object>> getDetailedSlots(
            @RequestParam(name = "date", required = false) String dateStr) {
        Map<String, Object> body = new HashMap<>();
        try {
            LocalDate date = parseSafeDate(dateStr);
            Long customerId = getOptionalAuthenticatedCustomerId();
            List<Map<String, Object>> slots = appointmentService.getDetailedSlots(date, customerId);

            body.put("success", true);
            body.put("date", date.toString());
            body.put("data", slots);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            body.put("success", false);
            body.put("message", "Database service error: " + e.getMessage());
            body.put("data", Collections.emptyList());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }

    @PostMapping("/book-slot")
    public ResponseEntity<Map<String, Object>> bookSlot(@RequestBody Map<String, Object> request) {
        Long customerId = getAuthenticatedCustomerId();
        Object serviceObj = request.get("service_id") != null ? request.get("service_id") : request.get("serviceId");
        Long serviceId = parseId(serviceObj);

        Object dateObj = request.get("appointment_date") != null ? request.get("appointment_date") : request.get("appointmentDate");
        String dateStr = dateObj != null ? dateObj.toString() : null;
        LocalDate date = parseSafeDate(dateStr);

        Object slotObj = request.get("time_slot") != null ? request.get("time_slot") : request.get("timeSlot");
        String timeSlot = slotObj != null ? slotObj.toString() : null;

        Object notesObj = request.get("notes");
        String notes = notesObj != null ? notesObj.toString() : null;

        Object payObj = request.get("payment_method") != null ? request.get("payment_method") : request.get("paymentMethod");
        String paymentMethod = payObj != null ? payObj.toString() : null;

        Appointment booked = appointmentService.bookSlot(customerId, serviceId, date, timeSlot, notes, paymentMethod);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Time slot " + timeSlot + " booked successfully!");
        body.put("data", booked);
        body.put("wallet_balance", walletService.getBalance(customerId));
        return new ResponseEntity<>(body, HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAll(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String status,
            @RequestParam(name = "customer_id", required = false) Long customerId) {
        
        LocalDate localDate = (date != null && !date.trim().isEmpty()) ? parseSafeDate(date) : null;
        List<Appointment> appts = appointmentService.getAllAppointments(localDate, status, customerId);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", appts);
        return ResponseEntity.ok(body);
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable Long id) {
        Long customerId = null;
        if ("customer".equals(getAuthenticatedUserType())) {
            customerId = getAuthenticatedCustomerId();
        }

        Appointment cancelled = appointmentService.cancelAppointment(id, customerId);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Appointment cancelled successfully");
        body.put("data", cancelled);
        return ResponseEntity.ok(body);
    }

    @PutMapping("/{id}/complete")
    public ResponseEntity<Map<String, Object>> complete(@PathVariable Long id) {
        Appointment completed = appointmentService.completeAppointment(id);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Appointment completed");
        body.put("data", completed);
        return ResponseEntity.ok(body);
    }

    @PutMapping("/{id}/start")
    public ResponseEntity<Map<String, Object>> start(@PathVariable Long id) {
        Appointment started = appointmentService.startAppointment(id);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Appointment started");
        body.put("data", started);
        return ResponseEntity.ok(body);
    }
}
