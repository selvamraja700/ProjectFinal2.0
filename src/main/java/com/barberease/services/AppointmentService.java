package com.barberease.services;

import com.barberease.models.*;
import com.barberease.models.Queue;
import com.barberease.repositories.*;
import com.barberease.websocket.BarberWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Transactional
public class AppointmentService {

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private ChairRepository chairRepository;

    @Autowired
    private TokenRepository tokenRepository;

    @Autowired
    private QueueRepository queueRepository;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private QueueService queueService;

    @Autowired
    private BarberWebSocketHandler webSocketHandler;

    public synchronized String generateTokenNumber(LocalDate date) {
        Optional<Token> lastToken = tokenRepository.findFirstByTokenDateOrderByIdDesc(date);
        int nextNumber = 1;

        if (lastToken.isPresent() && lastToken.get().getTokenNumber() != null) {
            String lastNumStr = lastToken.get().getTokenNumber();
            if (lastNumStr.startsWith("T")) {
                try {
                    nextNumber = Integer.parseInt(lastNumStr.substring(1)) + 1;
                } catch (NumberFormatException e) {
                    // Ignore and use 1
                }
            }
        }

        return String.format("T%03d", nextNumber);
    }

    public Appointment createAppointment(Long customerId, Long serviceId, Long chairId, 
                                         LocalDate appointmentDate, String timeSlot, 
                                         String notes, String paymentMethod) {
        
        com.barberease.models.Service service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("Service not found or inactive"));

        if (!service.isActive()) {
            throw new IllegalArgumentException("Service not found or inactive");
        }

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));

        BigDecimal price = service.getPrice();
        boolean isWalletPayment = paymentMethod == null || "redeem".equals(paymentMethod);

        if (isWalletPayment) {
            BigDecimal balance = customer.getWalletBalance();
            if (balance.compareTo(price) < 0) {
                throw new IllegalStateException(String.format(
                        "Insufficient wallet balance. Service costs $%.2f but your wallet has $%.2f. Please top up!",
                        price, balance));
            }
            customer.setWalletBalance(balance.subtract(price));
            customerRepository.save(customer);
        }

        Chair chair = chairRepository.findById(chairId)
                .orElseThrow(() -> new IllegalArgumentException("Chair not found. Please select a valid chair."));

        if (!chair.isActive() || "maintenance".equals(chair.getStatus())) {
            throw new IllegalStateException("Chair station is currently out of service. Please select another chair.");
        }

        // Check if the chair has an overlapping appointment for THIS specific time slot and duration
        List<String> activeStatuses = Arrays.asList("pending", "confirmed", "in_progress");
        int newStartMin = parseTimeToMinutes(timeSlot);
        int newDuration = (service != null && service.getDurationMinutes() > 0) ? service.getDurationMinutes() : 30;
        int newEndMin = newStartMin + newDuration;

        List<Appointment> chairAppts = appointmentRepository.findByChairId(chairId);
        for (Appointment exist : chairAppts) {
            if (exist.getAppointmentDate() != null && exist.getAppointmentDate().equals(appointmentDate)
                    && activeStatuses.contains(exist.getStatus())) {
                int existStartMin = parseTimeToMinutes(exist.getTimeSlot());
                if (existStartMin >= 0) {
                    int existDuration = (exist.getService() != null && exist.getService().getDurationMinutes() > 0)
                            ? exist.getService().getDurationMinutes() : 30;
                    int existEndMin = existStartMin + existDuration;

                    // Overlap check: max(newStartMin, existStartMin) < min(newEndMin, existEndMin)
                    if (Math.max(newStartMin, existStartMin) < Math.min(newEndMin, existEndMin)) {
                        String existStartStr = formatMinutesToTime(existStartMin);
                        String existEndStr = formatMinutesToTime(existEndMin);
                        throw new IllegalStateException(String.format(
                                "Time slot %s (%d min) conflicts with an existing appointment (%s to %s) on %s. The next booking is available after %s.",
                                timeSlot, newDuration, existStartStr, existEndStr, chair.getName(), existEndStr));
                    }
                }
            }
        }

        // Check if customer already has an appointment booked for the exact same time slot on this date
        boolean customerAlreadyBooked = appointmentRepository.existsByCustomerIdAndAppointmentDateAndTimeSlotAndStatusIn(
                customerId, appointmentDate, timeSlot, activeStatuses);

        if (customerAlreadyBooked) {
            throw new IllegalStateException("You already have an appointment booked for " + timeSlot + " on this date.");
        }

        String tokenNumber = generateTokenNumber(appointmentDate);

        Appointment appointment = new Appointment();
        appointment.setCustomer(customer);
        appointment.setService(service);
        appointment.setChair(chair);
        appointment.setTokenNumber(tokenNumber);
        appointment.setAppointmentDate(appointmentDate);
        appointment.setTimeSlot(timeSlot);
        appointment.setStatus("confirmed");
        appointment.setNotes(notes);
        Appointment saved = appointmentRepository.save(appointment);

        // Clear temporary checkout hold so other customers can book remaining time slots on this chair
        chair.setStatus("available");
        chair.setReservedByCustomer(null);
        chair.setReservedAt(null);
        chairRepository.save(chair);

        // Create token record
        Token token = new Token();
        token.setTokenNumber(tokenNumber);
        token.setCustomer(customer);
        token.setAppointment(saved);
        token.setType("appointment");
        token.setTokenDate(appointmentDate);
        token.setStatus("active");
        tokenRepository.save(token);

        webSocketHandler.broadcast("chair-update");
        webSocketHandler.broadcast("appointment-update");

        return saved;
    }

    public Appointment getAppointmentById(Long id) {
        return appointmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Appointment not found"));
    }

    public List<Appointment> getCustomerAppointments(Long customerId) {
        return appointmentRepository.findByCustomerIdOrderByAppointmentDateDescCreatedAtDesc(customerId);
    }

    public List<Appointment> getAllAppointments(LocalDate date, String status, Long customerId) {
        // Find all and filter in memory or via customized repository calls (for simplicity, do a custom filter or query)
        List<Appointment> all = appointmentRepository.findAll();
        List<Appointment> filtered = new ArrayList<>();
        
        for (Appointment a : all) {
            if (date != null && !a.getAppointmentDate().equals(date)) continue;
            if (status != null && !a.getStatus().equals(status)) continue;
            if (customerId != null && !a.getCustomer().getId().equals(customerId)) continue;
            filtered.add(a);
        }
        
        // Sort: Date DESC, TimeSlot ASC
        filtered.sort((a, b) -> {
            int dateCompare = b.getAppointmentDate().compareTo(a.getAppointmentDate());
            if (dateCompare != 0) return dateCompare;
            return a.getTimeSlot().compareTo(b.getTimeSlot());
        });
        
        return filtered;
    }

    public List<Appointment> getTodayAppointments() {
        return appointmentRepository.findByAppointmentDateOrderByTimeSlotAsc(LocalDate.now());
    }

    public static int parseTimeToMinutes(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) {
            return -1;
        }
        try {
            String clean = timeStr.trim().toUpperCase();
            if (clean.contains("-")) {
                clean = clean.split("-")[0].trim();
            }
            boolean isPm = clean.contains("PM");
            clean = clean.replace("AM", "").replace("PM", "").trim();
            String[] parts = clean.split(":");
            int hour = Integer.parseInt(parts[0].trim());
            int min = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            if (isPm && hour != 12) {
                hour += 12;
            } else if (!isPm && hour == 12) {
                hour = 0;
            }
            return hour * 60 + min;
        } catch (Exception e) {
            return -1;
        }
    }

    public static int parseEndTimeToMinutes(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) {
            return -1;
        }
        try {
            String clean = timeStr.trim().toUpperCase();
            if (clean.contains("-")) {
                String[] parts = clean.split("-");
                if (parts.length > 1) {
                    return parseTimeToMinutes(parts[1].trim());
                }
            }
            return parseTimeToMinutes(clean);
        } catch (Exception e) {
            return -1;
        }
    }

    public static String formatMinutesToTime(int totalMinutes) {
        int hour = totalMinutes / 60;
        int min = totalMinutes % 60;
        int displayHour = hour % 12 == 0 ? 12 : hour % 12;
        String period = hour < 12 ? "AM" : "PM";
        return String.format("%d:%02d %s", displayHour, min, period);
    }

    public List<String> getBookedSlots(Long chairId, LocalDate date) {
        List<Appointment> appts;
        if (chairId != null) {
            appts = appointmentRepository.findByChairId(chairId);
        } else {
            appts = appointmentRepository.findByAppointmentDateOrderByTimeSlotAsc(date);
        }
        Set<String> booked = new LinkedHashSet<>();
        List<String> activeStatuses = Arrays.asList("pending", "confirmed", "in_progress");

        for (Appointment a : appts) {
            if (a.getAppointmentDate() != null && a.getAppointmentDate().equals(date) && activeStatuses.contains(a.getStatus())) {
                if (a.getTimeSlot() != null) {
                    int startMin = parseTimeToMinutes(a.getTimeSlot());
                    if (startMin >= 0) {
                        int duration = 30;
                        if (a.getService() != null && a.getService().getDurationMinutes() > 0) {
                            duration = a.getService().getDurationMinutes();
                        }
                        int endMin = startMin + duration;

                        // Add all standard 30-minute intervals that overlap with [startMin, endMin)
                        // Standard salon hours: 8:00 AM (480 min) to 10:00 PM (1320 min)
                        for (int t = 480; t < 1320; t += 30) {
                            if (t < endMin && (t + 30) > startMin) {
                                booked.add(formatMinutesToTime(t));
                            }
                        }
                    } else {
                        booked.add(a.getTimeSlot());
                    }
                }
            }
        }
        return new ArrayList<>(booked);
    }

    public static final List<String> STANDARD_TIME_SLOTS = Arrays.asList(
            "09:00 AM - 10:00 AM",
            "10:00 AM - 11:00 AM",
            "11:00 AM - 12:00 PM",
            "12:00 PM - 01:00 PM",
            "01:00 PM - 02:00 PM",
            "02:00 PM - 03:00 PM",
            "03:00 PM - 04:00 PM",
            "04:00 PM - 05:00 PM",
            "05:00 PM - 06:00 PM",
            "06:00 PM - 07:00 PM",
            "07:00 PM - 08:00 PM",
            "08:00 PM - 09:00 PM",
            "09:00 PM - 10:00 PM"
    );

    public List<String> generateDynamicSlots(LocalDate date) {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        int openHour = 9;   // 09:00 AM
        int closeHour = 22; // 10:00 PM

        List<String> slots = new ArrayList<>();
        if (date.isBefore(today)) {
            return slots;
        }

        int startHour = openHour;
        if (date.isEqual(today)) {
            if (now.getHour() >= openHour) {
                if (now.getMinute() == 0) {
                    startHour = now.getHour();
                } else {
                    startHour = now.getHour() + 1;
                }
            }
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("hh:mm a");
        for (int h = startHour; h < closeHour; h++) {
            LocalTime start = LocalTime.of(h, 0);
            LocalTime end = start.plusMinutes(60);
            String slotStr = start.format(fmt) + " - " + end.format(fmt);
            slots.add(slotStr);
        }

        return slots;
    }

    public static boolean slotMatches(String apptSlot, String standardSlot) {
        if (apptSlot == null || standardSlot == null) return false;
        String a = apptSlot.trim().toUpperCase();
        String s = standardSlot.trim().toUpperCase();
        if (a.equals(s)) return true;
        if (s.startsWith(a)) return true;
        if (a.startsWith(s)) return true;
        String aStart = a.split("-")[0].trim();
        String sStart = s.split("-")[0].trim();
        if (aStart.equals(sStart)) return true;

        // Check time interval overlap
        int aStartMin = parseTimeToMinutes(a);
        int aEndMin = parseEndTimeToMinutes(a);
        if (aEndMin <= aStartMin) aEndMin = aStartMin + 30;
        int sStartMin = parseTimeToMinutes(s);
        int sEndMin = parseEndTimeToMinutes(s);
        if (sEndMin <= sStartMin) sEndMin = sStartMin + 60;
        if (aStartMin >= 0 && sStartMin >= 0) {
            return Math.max(aStartMin, sStartMin) < Math.min(aEndMin, sEndMin);
        }
        return false;
    }

    public List<Map<String, Object>> getEnrichedChairs(LocalDate queryDate) {
        if (queryDate == null) {
            queryDate = LocalDate.now();
        }
        LocalDate date = queryDate;

        List<Chair> chairs = chairRepository.findAll();
        chairs.removeIf(c -> !c.isActive() || c.getChairNumber() > 6);
        chairs.sort(Comparator.comparingInt(Chair::getChairNumber));

        List<Appointment> allAppts = appointmentRepository.findByAppointmentDateOrderByTimeSlotAsc(date);
        List<String> activeStatuses = Arrays.asList("in_progress", "confirmed", "pending");
        List<Appointment> dateAppts = new ArrayList<>();
        for (Appointment a : allAppts) {
            if (activeStatuses.contains(a.getStatus())) {
                dateAppts.add(a);
            }
        }

        Appointment main1Appt = null;
        Appointment main2Appt = null;
        List<Appointment> main1Future = new ArrayList<>();
        List<Appointment> main2Future = new ArrayList<>();
        List<Appointment> waitingAppts = new ArrayList<>();

        for (Appointment a : dateAppts) {
            if (a.getChair() != null) {
                int cNum = a.getChair().getChairNumber();
                if (cNum == 1) {
                    if ("in_progress".equalsIgnoreCase(a.getStatus())) {
                        main1Appt = a;
                    } else if (main1Appt == null && "confirmed".equalsIgnoreCase(a.getStatus())) {
                        main1Appt = a;
                    } else {
                        main1Future.add(a);
                    }
                } else if (cNum == 2) {
                    if ("in_progress".equalsIgnoreCase(a.getStatus())) {
                        main2Appt = a;
                    } else if (main2Appt == null && "confirmed".equalsIgnoreCase(a.getStatus())) {
                        main2Appt = a;
                    } else {
                        main2Future.add(a);
                    }
                } else if (cNum >= 3 && cNum <= 6) {
                    waitingAppts.add(a);
                }
            } else {
                waitingAppts.add(a);
            }
        }

        // Include any chairs occupied by queue entries if not in appointments
        for (Chair wc : chairs) {
            if ("waiting".equalsIgnoreCase(wc.getChairType())) {
                boolean hasAppt = waitingAppts.stream().anyMatch(a -> a.getChair() != null && a.getChair().getId().equals(wc.getId()));
                if (!hasAppt) {
                    Optional<Queue> qOpt = queueRepository.findByChairIdAndStatusInOrderByPositionAsc(
                            wc.getId(), Arrays.asList("waiting", "called")).stream().findFirst();
                    if (qOpt.isPresent()) {
                        Queue q = qOpt.get();
                        Appointment dummy = new Appointment();
                        dummy.setCustomer(q.getCustomer());
                        dummy.setChair(wc);
                        dummy.setService(q.getService());
                        dummy.setTokenNumber(q.getTokenNumber());
                        dummy.setStatus("waiting");
                        waitingAppts.add(dummy);
                    } else if (wc.getReservedByCustomer() != null) {
                        Optional<Queue> custQ = queueRepository.findByCustomerIdAndStatusIn(
                                wc.getReservedByCustomer().getId(), Arrays.asList("waiting", "called"));
                        Appointment dummy = new Appointment();
                        dummy.setCustomer(wc.getReservedByCustomer());
                        dummy.setChair(wc);
                        if (custQ.isPresent()) {
                            dummy.setService(custQ.get().getService());
                            dummy.setTokenNumber(custQ.get().getTokenNumber());
                        }
                        dummy.setStatus("waiting");
                        waitingAppts.add(dummy);
                    }
                }
            }
        }

        waitingAppts.sort((a, b) -> {
            int aChair = a.getChair() != null ? a.getChair().getChairNumber() : 999;
            int bChair = b.getChair() != null ? b.getChair().getChairNumber() : 999;
            if (aChair != bChair) return Integer.compare(aChair, bChair);
            return Long.compare(a.getId() != null ? a.getId() : 0, b.getId() != null ? b.getId() : 0);
        });

        class TimeInterval {
            int start;
            int end;
            String customerName;
            Long apptId;
            TimeInterval(int s, int e, String name, Long id) {
                this.start = s;
                this.end = e;
                this.customerName = name;
                this.apptId = id;
            }
        }

        List<TimeInterval> chair1Busy = new ArrayList<>();
        List<TimeInterval> chair2Busy = new ArrayList<>();

        int currentMinOfDay = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
        currentMinOfDay = ((currentMinOfDay + 4) / 5) * 5;
        int baseOpenTime = 540; // 09:00 AM
        int effectiveNow = date.equals(LocalDate.now()) ? Math.max(baseOpenTime, currentMinOfDay) : baseOpenTime;

        String main1Time = "---";
        int freeTime1 = effectiveNow;
        if (main1Appt != null) {
            int s1 = parseTimeToMinutes(main1Appt.getTimeSlot());
            if (s1 < 0) s1 = effectiveNow;
            int d1 = (main1Appt.getService() != null && main1Appt.getService().getDurationMinutes() > 0)
                    ? main1Appt.getService().getDurationMinutes() : 30;
            int e1 = s1 + d1;
            main1Time = formatMinutesToTime(s1).toLowerCase() + " - " + formatMinutesToTime(e1).toLowerCase();
            chair1Busy.add(new TimeInterval(s1, e1, main1Appt.getCustomer() != null ? main1Appt.getCustomer().getName() : "Customer", main1Appt.getId()));
            freeTime1 = Math.max(e1, effectiveNow);
        }

        String main2Time = "---";
        int freeTime2 = effectiveNow;
        if (main2Appt != null) {
            int s2 = parseTimeToMinutes(main2Appt.getTimeSlot());
            if (s2 < 0) s2 = effectiveNow;
            int d2 = (main2Appt.getService() != null && main2Appt.getService().getDurationMinutes() > 0)
                    ? main2Appt.getService().getDurationMinutes() : 30;
            int e2 = s2 + d2;
            main2Time = formatMinutesToTime(s2).toLowerCase() + " - " + formatMinutesToTime(e2).toLowerCase();
            chair2Busy.add(new TimeInterval(s2, e2, main2Appt.getCustomer() != null ? main2Appt.getCustomer().getName() : "Customer", main2Appt.getId()));
            freeTime2 = Math.max(e2, effectiveNow);
        }

        // Add pre-booked future appointments
        for (Appointment fa : main1Future) {
            int fs = parseTimeToMinutes(fa.getTimeSlot());
            if (fs >= 0) {
                int fd = (fa.getService() != null && fa.getService().getDurationMinutes() > 0) ? fa.getService().getDurationMinutes() : 30;
                chair1Busy.add(new TimeInterval(fs, fs + fd, fa.getCustomer() != null ? fa.getCustomer().getName() : "Customer", fa.getId()));
            }
        }
        for (Appointment fa : main2Future) {
            int fs = parseTimeToMinutes(fa.getTimeSlot());
            if (fs >= 0) {
                int fd = (fa.getService() != null && fa.getService().getDurationMinutes() > 0) ? fa.getService().getDurationMinutes() : 30;
                chair2Busy.add(new TimeInterval(fs, fs + fd, fa.getCustomer() != null ? fa.getCustomer().getName() : "Customer", fa.getId()));
            }
        }

        Map<Long, String> calculatedWaitingTimes = new HashMap<>();
        int cursor1 = freeTime1;
        int cursor2 = freeTime2;

        for (Appointment wa : waitingAppts) {
            int dur = (wa.getService() != null && wa.getService().getDurationMinutes() > 0)
                    ? wa.getService().getDurationMinutes() : 30;

            int t1 = cursor1;
            while (t1 < 1320) {
                int endT = t1 + dur;
                boolean conflict = false;
                for (TimeInterval ti : chair1Busy) {
                    if (Math.max(ti.start, t1) < Math.min(ti.end, endT)) {
                        t1 = ti.end;
                        conflict = true;
                        break;
                    }
                }
                if (!conflict) break;
            }

            int t2 = cursor2;
            while (t2 < 1320) {
                int endT = t2 + dur;
                boolean conflict = false;
                for (TimeInterval ti : chair2Busy) {
                    if (Math.max(ti.start, t2) < Math.min(ti.end, endT)) {
                        t2 = ti.end;
                        conflict = true;
                        break;
                    }
                }
                if (!conflict) break;
            }

            int scheduledStart, scheduledEnd;
            if (t1 <= t2) {
                scheduledStart = t1;
                scheduledEnd = t1 + dur;
                chair1Busy.add(new TimeInterval(scheduledStart, scheduledEnd, wa.getCustomer() != null ? wa.getCustomer().getName() : "Customer", wa.getId()));
                cursor1 = scheduledEnd;
            } else {
                scheduledStart = t2;
                scheduledEnd = t2 + dur;
                chair2Busy.add(new TimeInterval(scheduledStart, scheduledEnd, wa.getCustomer() != null ? wa.getCustomer().getName() : "Customer", wa.getId()));
                cursor2 = scheduledEnd;
            }

            String waitTime = formatMinutesToTime(scheduledStart).toLowerCase() + " - " + formatMinutesToTime(scheduledEnd).toLowerCase();
            if (wa.getChair() != null) {
                calculatedWaitingTimes.put(wa.getChair().getId(), waitTime);
            }
            if (wa.getId() != null) {
                calculatedWaitingTimes.put(wa.getId(), waitTime);
            }
        }

        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Chair chair : chairs) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", chair.getId());
            map.put("chair_number", chair.getChairNumber());
            map.put("chair_type", chair.getChairType());

            boolean isWaitingChair = "waiting".equalsIgnoreCase(chair.getChairType());
            String displayName = isWaitingChair 
                    ? "Waiting Chair " + (chair.getChairNumber() - 2) 
                    : "Seat " + chair.getChairNumber();
            map.put("name", displayName);

            String customerName = "---";
            String timeSlot = "---";
            String tokenNumber = "---";
            Long appointmentId = null;

            if (chair.getChairNumber() == 1) {
                if (main1Appt != null) {
                    customerName = main1Appt.getCustomer() != null ? main1Appt.getCustomer().getName() : "Customer";
                    timeSlot = main1Time;
                    tokenNumber = main1Appt.getTokenNumber() != null ? main1Appt.getTokenNumber() : "---";
                    appointmentId = main1Appt.getId();
                }
            } else if (chair.getChairNumber() == 2) {
                if (main2Appt != null) {
                    customerName = main2Appt.getCustomer() != null ? main2Appt.getCustomer().getName() : "Customer";
                    timeSlot = main2Time;
                    tokenNumber = main2Appt.getTokenNumber() != null ? main2Appt.getTokenNumber() : "---";
                    appointmentId = main2Appt.getId();
                }
            } else {
                Appointment matchedWait = waitingAppts.stream()
                        .filter(a -> a.getChair() != null && a.getChair().getId().equals(chair.getId()))
                        .findFirst().orElse(null);
                if (matchedWait != null) {
                    customerName = matchedWait.getCustomer() != null ? matchedWait.getCustomer().getName() : "Customer";
                    timeSlot = calculatedWaitingTimes.getOrDefault(chair.getId(), "---");
                    if ("---".equals(timeSlot) && matchedWait.getTimeSlot() != null && !matchedWait.getTimeSlot().trim().isEmpty()) {
                        timeSlot = matchedWait.getTimeSlot();
                    }
                    tokenNumber = matchedWait.getTokenNumber() != null ? matchedWait.getTokenNumber() : "---";
                    appointmentId = matchedWait.getId();
                }
            }

            if ("---".equals(customerName) && chair.getReservedByCustomer() != null && date.equals(LocalDate.now())) {
                customerName = chair.getReservedByCustomer().getName();
                if (calculatedWaitingTimes.containsKey(chair.getId())) {
                    timeSlot = calculatedWaitingTimes.get(chair.getId());
                } else if (chair.getReservedAt() != null) {
                    timeSlot = chair.getReservedAt().format(DateTimeFormatter.ofPattern("h:mm a"));
                }
            }

            String status = chair.getStatus();
            if (!"---".equals(customerName)) {
                status = isWaitingChair ? "waiting" : "occupied";
            }

            map.put("status", status);
            map.put("is_active", chair.isActive());
            map.put("customer_name", customerName);
            map.put("time", timeSlot);
            map.put("token_number", tokenNumber);
            map.put("appointment_id", appointmentId);
            map.put("reserved_by_customer", chair.getReservedByCustomer());
            enriched.add(map);
        }

        return enriched;
    }

    public List<Map<String, Object>> getDetailedSlots(LocalDate date, Long currentCustomerId) {
        if (date == null) date = LocalDate.now();
        List<Appointment> appts = appointmentRepository.findByAppointmentDateOrderByTimeSlotAsc(date);
        List<String> activeStatuses = Arrays.asList("pending", "confirmed", "in_progress");

        List<Map<String, Object>> result = new ArrayList<>();
        int mainCapacity = 2;
        int waitingCapacity = 4;
        int totalCapacity = mainCapacity + waitingCapacity;

        List<String> slotsToDisplay = generateDynamicSlots(date);
        if (slotsToDisplay.isEmpty() && date.isAfter(LocalDate.now())) {
            slotsToDisplay = STANDARD_TIME_SLOTS;
        }

        List<Map<String, Object>> enrichedChairs = getEnrichedChairs(date);

        for (String slot : slotsToDisplay) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("timeSlot", slot);
            map.put("capacity", mainCapacity);
            map.put("mainCapacity", mainCapacity);
            map.put("waitingCapacity", waitingCapacity);
            map.put("totalCapacity", totalCapacity);

            int slotStartMin = parseTimeToMinutes(slot);
            int slotEndMin = slotStartMin + 60;

            List<Appointment> slotAppts = new ArrayList<>();
            for (Appointment a : appts) {
                if (activeStatuses.contains(a.getStatus()) && slotMatches(a.getTimeSlot(), slot)) {
                    slotAppts.add(a);
                }
            }

            // Check chair occupancy during this slot window
            boolean seat1Occupied = false;
            boolean seat2Occupied = false;
            int waitingInSlotCount = 0;
            List<Map<String, Object>> slotOccupants = new ArrayList<>();

            for (Map<String, Object> ec : enrichedChairs) {
                Object cNumObj = ec.get("chair_number");
                int cNum = (cNumObj instanceof Number) ? ((Number) cNumObj).intValue() : 0;
                String chairTime = (String) ec.get("time");
                if (chairTime != null && !"---".equals(chairTime)) {
                    int cStart = parseTimeToMinutes(chairTime);
                    int cEnd = parseEndTimeToMinutes(chairTime);
                    if (cEnd <= cStart) cEnd = cStart + 30;

                    if (Math.max(cStart, slotStartMin) < Math.min(cEnd, slotEndMin)) {
                        if (cNum == 1) {
                            seat1Occupied = true;
                        } else if (cNum == 2) {
                            seat2Occupied = true;
                        } else if (cNum >= 3 && cNum <= 6) {
                            waitingInSlotCount++;
                        }
                        Map<String, Object> occ = new HashMap<>();
                        occ.put("customerName", ec.get("customer_name"));
                        occ.put("tokenNumber", ec.get("token_number"));
                        occ.put("chairNumber", cNum);
                        occ.put("chairName", ec.get("name"));
                        slotOccupants.add(occ);
                    }
                }
            }

            int mainOccupiedCount = (seat1Occupied ? 1 : 0) + (seat2Occupied ? 1 : 0);
            int seatedCount = (int) slotAppts.stream().filter(a -> a.getChair() != null).count();
            int mainAssigned = Math.max(mainOccupiedCount, Math.min(mainCapacity, seatedCount));
            int waitingAssigned = Math.max(waitingInSlotCount, Math.max(0, Math.min(seatedCount - mainCapacity, waitingCapacity)));
            int assignedCount = mainAssigned + waitingAssigned;

            int unseatedCount = (int) slotAppts.stream().filter(a -> a.getChair() == null).count();
            boolean isMainFull = mainAssigned >= mainCapacity;
            boolean isWaitingFull = waitingAssigned >= waitingCapacity;
            boolean isFull = assignedCount >= totalCapacity;

            map.put("assignedCount", assignedCount);
            map.put("unseatedCount", unseatedCount);
            map.put("mainAssigned", mainAssigned);
            map.put("waitingAssigned", waitingAssigned);
            map.put("availableCount", Math.max(0, totalCapacity - assignedCount));
            map.put("isMainFull", isMainFull);
            map.put("isWaitingFull", isWaitingFull);
            map.put("isFull", isFull);

            String statusStr = "available";
            if (isFull) {
                statusStr = "full";
            } else if (isMainFull) {
                statusStr = "waiting_available";
            }
            map.put("status", statusStr);

            List<Map<String, Object>> bookedList = new ArrayList<>();
            boolean isCurrent = false;
            boolean isCurrentWaitingList = false;

            for (Appointment sa : slotAppts) {
                Map<String, Object> b = new HashMap<>();
                String customerName = sa.getCustomer() != null ? sa.getCustomer().getName() : "Customer";
                b.put("customerName", customerName);
                b.put("appointmentId", sa.getId());
                b.put("tokenNumber", sa.getTokenNumber());
                if (sa.getChair() != null) {
                    b.put("chairNumber", sa.getChair().getChairNumber());
                    b.put("chairName", sa.getChair().getName());
                } else {
                    b.put("isWaitingList", true);
                }
                bookedList.add(b);
                if (currentCustomerId != null && sa.getCustomer() != null
                        && currentCustomerId.equals(sa.getCustomer().getId())) {
                    isCurrent = true;
                    if (sa.getChair() == null) {
                        isCurrentWaitingList = true;
                    }
                }
            }

            for (Map<String, Object> occ : slotOccupants) {
                String occName = (String) occ.get("customerName");
                if (occName != null && !"---".equals(occName) && bookedList.stream().noneMatch(b -> occName.equals(b.get("customerName")))) {
                    bookedList.add(occ);
                }
            }

            map.put("bookedCustomers", bookedList);
            map.put("isCurrentCustomer", isCurrent);
            map.put("isCurrentWaitingList", isCurrentWaitingList);
            result.add(map);
        }
        return result;
    }

    private static final Object BOOKING_LOCK = new Object();

    public Appointment bookSlot(Long customerId, Long serviceId, LocalDate date, String timeSlot,
                                String notes, String paymentMethod) {
        synchronized (BOOKING_LOCK) {
            List<String> activeStatuses = Arrays.asList("pending", "confirmed", "in_progress");

            Customer customer = customerRepository.findById(customerId)
                    .orElseThrow(() -> new IllegalArgumentException("Customer not found"));

            List<Appointment> allDateAppts = appointmentRepository.findByAppointmentDateOrderByTimeSlotAsc(date);

            // Check if customer already has an active booking for this time slot on this date
            for (Appointment a : allDateAppts) {
                if (activeStatuses.contains(a.getStatus()) && a.getCustomer() != null && a.getCustomer().getId().equals(customerId)) {
                    if (slotMatches(a.getTimeSlot(), timeSlot)) {
                        throw new IllegalStateException("You already have a booking for " + timeSlot + " on this date.");
                    }
                }
            }

            // Find existing bookings for this specific slot
            List<Appointment> existingForSlot = new ArrayList<>();
            for (Appointment a : allDateAppts) {
                if (activeStatuses.contains(a.getStatus()) && slotMatches(a.getTimeSlot(), timeSlot)) {
                    existingForSlot.add(a);
                }
            }

            com.barberease.models.Service service = null;
            if (serviceId != null) {
                service = serviceRepository.findById(serviceId).orElse(null);
            }
            if (service == null) {
                service = serviceRepository.findAll().stream()
                        .filter(com.barberease.models.Service::isActive)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("No active services available"));
            }

            // CAPACITY PROGRESSION:
            // 1. If existing < 2: Goes to Main Service Seat (Seat 1 or Seat 2)
            // 2. If 2 <= existing < 6: Main seats are full -> Goes to Waiting Chairs (Chairs 3, 4, 5, 6)
            // 3. If existing >= 6: Waiting seats are full -> Goes to Waiting List!
            if (existingForSlot.size() >= 6) {
                Queue queued = queueService.addToQueue(customerId, service.getId(), null, timeSlot, date.toString());
                String tokenNumber = queued.getTokenNumber();
                Appointment saved = appointmentRepository.findByTokenNumber(tokenNumber).stream().findFirst().orElse(null);
                if (saved == null) {
                    Appointment queueAppt = new Appointment();
                    queueAppt.setCustomer(customer);
                    queueAppt.setService(service);
                    queueAppt.setChair(null);
                    queueAppt.setTokenNumber(tokenNumber);
                    queueAppt.setAppointmentDate(date);
                    queueAppt.setTimeSlot(timeSlot);
                    queueAppt.setStatus("pending");
                    queueAppt.setNotes(notes != null ? notes + " (Queued: Waiting List)" : "(Queued: Waiting List)");
                    saved = appointmentRepository.save(queueAppt);
                }

                webSocketHandler.broadcast("slot-update");
                webSocketHandler.broadcast("queue-update");
                webSocketHandler.broadcast("appointment-update");
                return saved;
            }

            boolean isWalletPayment = "wallet".equalsIgnoreCase(paymentMethod) || "redeem".equalsIgnoreCase(paymentMethod);
            if (isWalletPayment) {
                BigDecimal price = service.getPrice() != null ? service.getPrice() : BigDecimal.ZERO;
                BigDecimal balance = customer.getWalletBalance() != null ? customer.getWalletBalance() : BigDecimal.ZERO;
                if (balance.compareTo(price) < 0) {
                    throw new IllegalStateException(String.format(
                            "Insufficient wallet balance. Service costs $%.2f but your wallet has $%.2f.", price, balance));
                }
                customer.setWalletBalance(balance.subtract(price));
                customerRepository.save(customer);
            }

            Set<Integer> takenChairs = new HashSet<>();
            for (Appointment a : existingForSlot) {
                if (a.getChair() != null) {
                    takenChairs.add(a.getChair().getChairNumber());
                }
            }

            Chair assignedSeat = null;
            boolean isWaitingChair = false;

            if (existingForSlot.size() < 2) {
                if (!takenChairs.contains(1)) {
                    assignedSeat = chairRepository.findByChairNumber(1).orElse(null);
                } else if (!takenChairs.contains(2)) {
                    assignedSeat = chairRepository.findByChairNumber(2).orElse(null);
                }
            } else {
                // Main seats are full -> Go to Waiting Chairs (Chairs 3, 4, 5, 6)
                isWaitingChair = true;
                for (int w = 3; w <= 6; w++) {
                    if (!takenChairs.contains(w)) {
                        assignedSeat = chairRepository.findByChairNumber(w).orElse(null);
                        break;
                    }
                }
            }

            if (assignedSeat == null) {
                assignedSeat = chairRepository.findByStatusAndIsActiveTrueOrderByChairNumberAsc("available")
                        .stream().findFirst().orElse(null);
            }

            if (assignedSeat != null && date.equals(LocalDate.now())) {
                assignedSeat.setStatus(isWaitingChair ? "waiting" : "occupied");
                assignedSeat.setReservedByCustomer(customer);
                assignedSeat.setReservedAt(LocalDateTime.now());
                chairRepository.save(assignedSeat);
            }

            String tokenNumber = generateTokenNumber(date);

            int startMin = parseTimeToMinutes(timeSlot);
            int dur = (service != null && service.getDurationMinutes() > 0) ? service.getDurationMinutes() : 30;
            int endMin = startMin >= 0 ? startMin + dur : -1;
            String serviceDurationSlot = (startMin >= 0 && endMin >= 0)
                    ? formatMinutesToTime(startMin).toLowerCase() + " - " + formatMinutesToTime(endMin).toLowerCase()
                    : timeSlot;

            Appointment appointment = new Appointment();
            appointment.setCustomer(customer);
            appointment.setService(service);
            appointment.setChair(assignedSeat);
            appointment.setTokenNumber(tokenNumber);
            appointment.setAppointmentDate(date);
            appointment.setTimeSlot(serviceDurationSlot);
            appointment.setStatus("confirmed");
            String chairNote = isWaitingChair 
                    ? " (Waiting Chair " + (assignedSeat != null ? (assignedSeat.getChairNumber() - 2) : "") + ")" 
                    : "";
            appointment.setNotes(notes != null ? notes + chairNote : chairNote);
            Appointment saved = appointmentRepository.save(appointment);

            Token token = new Token();
            token.setTokenNumber(tokenNumber);
            token.setCustomer(customer);
            token.setAppointment(saved);
            token.setType("appointment");
            token.setTokenDate(date);
            token.setStatus("active");
            tokenRepository.save(token);

            if (isWaitingChair && assignedSeat != null) {
                Queue waitQueue = new Queue();
                waitQueue.setCustomer(customer);
                waitQueue.setService(service);
                waitQueue.setChair(assignedSeat);
                waitQueue.setTokenNumber(tokenNumber);
                waitQueue.setPosition(assignedSeat.getChairNumber() - 2);
                waitQueue.setStatus("waiting");
                waitQueue.setEstimatedWaitMinutes(Math.max(0, (assignedSeat.getChairNumber() - 2) * 30));
                queueRepository.save(waitQueue);
            }

            webSocketHandler.broadcast("slot-update");
            webSocketHandler.broadcast("chair-update");
            webSocketHandler.broadcast("queue-update");
            webSocketHandler.broadcast("appointment-update");

            return saved;
        }
    }

    public Chair completeSeatService(Long chairId) {
        return completeSeatService(chairId, null);
    }

    @org.springframework.transaction.annotation.Transactional
    public Chair completeSeatService(Long chairId, LocalDate targetDate) {
        synchronized (BOOKING_LOCK) {
            Chair chair = chairRepository.findById(chairId)
                    .orElseThrow(() -> new IllegalArgumentException("Seat not found"));

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime startOfDay = LocalDate.now().atStartOfDay();

            // 1. Mark active appointment on this chair as completed
            List<Appointment> activeAppts = appointmentRepository.findByChairId(chairId);
            Appointment completedAppt = null;
            for (Appointment a : activeAppts) {
                if (("in_progress".equalsIgnoreCase(a.getStatus()) || "confirmed".equalsIgnoreCase(a.getStatus()))
                        && (targetDate == null || targetDate.equals(a.getAppointmentDate()))) {
                    completedAppt = a;
                    break;
                }
            }
            if (completedAppt == null && !activeAppts.isEmpty()) {
                completedAppt = activeAppts.get(0);
            }

            LocalDate date = targetDate != null ? targetDate 
                    : (completedAppt != null && completedAppt.getAppointmentDate() != null ? completedAppt.getAppointmentDate() : LocalDate.now());
            String timeSlot = completedAppt != null ? completedAppt.getTimeSlot() : null;

            if (completedAppt != null) {
                completedAppt.setStatus("completed");
                completedAppt.setCompletedAt(now);
                appointmentRepository.save(completedAppt);

                if (completedAppt.getTokenNumber() != null) {
                    tokenRepository.findByTokenNumber(completedAppt.getTokenNumber()).forEach(t -> {
                        t.setStatus("used");
                        tokenRepository.save(t);
                    });
                }
            }

            // 2. Mark active queue entry assigned to this chair as completed
            List<Queue> servingQueues = queueRepository.findByChairIdAndStatusInAndCreatedAtAfterOrderByPositionAsc(
                    chairId, Arrays.asList("called", "serving"), startOfDay);
            for (Queue q : servingQueues) {
                q.setStatus("completed");
                q.setCompletedAt(now);
                queueRepository.save(q);
            }

            if (chair.getReservedByCustomer() != null) {
                Long prevCustId = chair.getReservedByCustomer().getId();
                List<Queue> prevCustQueues = queueRepository.findAllByCustomerIdAndStatusIn(
                        prevCustId, Arrays.asList("waiting", "called", "serving"));
                for (Queue q : prevCustQueues) {
                    q.setStatus("completed");
                    q.setCompletedAt(now);
                    queueRepository.save(q);
                }
            }

            // 3. Free the seat initially
            chair.setStatus("available");
            chair.setReservedByCustomer(null);
            chair.setReservedAt(null);
            Chair savedChair = chairRepository.save(chair);

            // 4. Trigger Conveyor Pipeline Rotation:
            // - Waiting Seat 1 -> Main Seat
            // - Waiting Seat 2 -> Waiting Seat 1
            // - Waiting Seat 3 -> Waiting Seat 2
            // - Waiting Seat 4 -> Waiting Seat 3
            // - First Join Waiting List -> Waiting Seat 4!
            rotateAndPromoteWaitingChairs(chairId, date, null);

            return chairRepository.findById(chairId).orElse(savedChair);
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public void autoPromoteIfMainSeatsAvailable(LocalDate targetDate) {
        LocalDate date = targetDate != null ? targetDate : LocalDate.now();
        Chair main1 = chairRepository.findByChairNumber(1).orElse(null);
        Chair main2 = chairRepository.findByChairNumber(2).orElse(null);
        boolean hasAvailMain = (main1 != null && "available".equalsIgnoreCase(main1.getStatus())) ||
                              (main2 != null && "available".equalsIgnoreCase(main2.getStatus()));
        if (!hasAvailMain) {
            return;
        }
        rotateAndPromoteWaitingChairs(null, date, null);
    }

    @org.springframework.transaction.annotation.Transactional
    public void compactAndReorganizeWaitingChairs(LocalDate targetDate, Integer fromWaitNum, Integer toWaitNum) {
        LocalDate date = targetDate != null ? targetDate : LocalDate.now();
        if (fromWaitNum != null && toWaitNum != null) {
            int fromChairNum = fromWaitNum + 2;
            int toChairNum = toWaitNum + 2;
            Chair fromChair = chairRepository.findByChairNumber(fromChairNum).orElse(null);
            Chair toChair = chairRepository.findByChairNumber(toChairNum).orElse(null);

            if (fromChair != null && toChair != null) {
                List<Appointment> allDateAppts = appointmentRepository.findByAppointmentDateOrderByTimeSlotAsc(date);
                Appointment fromAppt = allDateAppts.stream()
                        .filter(a -> a.getChair() != null && a.getChair().getId().equals(fromChair.getId()) && "confirmed".equalsIgnoreCase(a.getStatus()))
                        .findFirst().orElse(null);

                if (fromAppt != null) {
                    fromAppt.setChair(toChair);
                    fromAppt.setNotes("(Waiting Chair " + toWaitNum + ")");
                    appointmentRepository.save(fromAppt);

                    toChair.setStatus("waiting");
                    toChair.setReservedByCustomer(fromAppt.getCustomer());
                    chairRepository.save(toChair);

                    fromChair.setStatus("available");
                    fromChair.setReservedByCustomer(null);
                    fromChair.setReservedAt(null);
                    chairRepository.save(fromChair);

                    Optional<Queue> qOpt = queueRepository.findByCustomerIdAndStatusIn(
                            fromAppt.getCustomer().getId(), Arrays.asList("waiting", "called"));
                    if (qOpt.isPresent()) {
                        Queue q = qOpt.get();
                        q.setChair(toChair);
                        q.setStatus("waiting");
                        q.setPosition(toWaitNum);
                        queueRepository.save(q);
                    }
                }
            }
        }
        rotateAndPromoteWaitingChairs(null, date, null);
    }

    @org.springframework.transaction.annotation.Transactional
    public void rotateAndPromoteWaitingChairs(Long freedMainChairId, LocalDate targetDate, String targetSlot) {
        synchronized (BOOKING_LOCK) {
            LocalDateTime now = LocalDateTime.now();
            LocalDate date = targetDate != null ? targetDate : LocalDate.now();
            LocalDateTime startOfDay = date.atStartOfDay();
            boolean stateChanged = (freedMainChairId != null);

            List<Chair> waitingChairs = chairRepository.findByChairTypeAndIsActiveTrueOrderByChairNumberAsc("waiting");
            waitingChairs.removeIf(c -> c.getChairNumber() < 3 || c.getChairNumber() > 6);
            waitingChairs.sort(Comparator.comparingInt(Chair::getChairNumber));

            Chair main1 = chairRepository.findByChairNumber(1).orElse(null);
            Chair main2 = chairRepository.findByChairNumber(2).orElse(null);
            List<Chair> mainChairs = new ArrayList<>();
            if (main1 != null) mainChairs.add(main1);
            if (main2 != null) mainChairs.add(main2);

            List<Appointment> allDateAppts = appointmentRepository.findByAppointmentDateOrderByTimeSlotAsc(date);
            List<String> activeApptStatuses = Arrays.asList("pending", "confirmed", "in_progress");

            // Build map of chairId -> active appointment
            Map<Long, Appointment> activeChairApptMap = new LinkedHashMap<>();
            List<Appointment> unseatedAppts = new ArrayList<>();

            for (Appointment a : allDateAppts) {
                if (activeApptStatuses.contains(a.getStatus())) {
                    if (a.getChair() != null) {
                        activeChairApptMap.put(a.getChair().getId(), a);
                    } else {
                        unseatedAppts.add(a);
                    }
                }
            }

            // Identify available main chairs
            List<Chair> availableMainChairs = new ArrayList<>();

            // 1. If a specific freed chair was provided (and it's a main chair), it comes first
            if (freedMainChairId != null) {
                Chair freed = chairRepository.findById(freedMainChairId).orElse(null);
                if (freed != null && freed.getChairNumber() <= 2) {
                    availableMainChairs.add(freed);
                }
            }

            // 2. Check each main chair: if it has no active appointment being served, it is available
            for (Chair mc : mainChairs) {
                if (availableMainChairs.stream().anyMatch(c -> c.getId().equals(mc.getId()))) {
                    continue;
                }
                Appointment apptOnMain = activeChairApptMap.get(mc.getId());
                boolean isServing = apptOnMain != null && ("in_progress".equalsIgnoreCase(apptOnMain.getStatus()) || "confirmed".equalsIgnoreCase(apptOnMain.getStatus()));
                if (!isServing || "available".equalsIgnoreCase(mc.getStatus())) {
                    availableMainChairs.add(mc);
                }
            }

            // Candidate holder for waiting line
            class WaitingPerson {
                Customer customer;
                com.barberease.models.Service service;
                String tokenNumber;
                Appointment appointment;
                Queue queue;
                String apptTimeSlot;
                int currentChairNumber = 999;
            }

            List<WaitingPerson> waitingLine = new ArrayList<>();
            Set<Long> seenCustomerIds = new HashSet<>();

            // Exclude customers currently being served on busy main chairs (not in availableMainChairs)
            Set<Long> busyMainCustomerIds = new HashSet<>();
            for (Chair mc : mainChairs) {
                if (!availableMainChairs.stream().anyMatch(c -> c.getId().equals(mc.getId()))) {
                    Appointment busyAppt = activeChairApptMap.get(mc.getId());
                    if (busyAppt != null && busyAppt.getCustomer() != null && "in_progress".equalsIgnoreCase(busyAppt.getStatus())) {
                        busyMainCustomerIds.add(busyAppt.getCustomer().getId());
                        mc.setReservedByCustomer(busyAppt.getCustomer());
                        mc.setStatus("occupied");
                        chairRepository.save(mc);
                    }
                }
            }

            // 4. Collect currently seated waiting customers in order: Waiting Chairs (Chairs 3, 4, 5, 6)
            for (Chair wc : waitingChairs) {
                Appointment a = activeChairApptMap.get(wc.getId());
                Customer c = (a != null && a.getCustomer() != null) ? a.getCustomer() : wc.getReservedByCustomer();

                if (c != null && !seenCustomerIds.contains(c.getId()) && !busyMainCustomerIds.contains(c.getId())) {
                    seenCustomerIds.add(c.getId());
                    WaitingPerson wp = new WaitingPerson();
                    wp.customer = c;
                    wp.service = a != null ? a.getService() : null;
                    wp.tokenNumber = a != null ? a.getTokenNumber() : null;
                    wp.appointment = a;
                    wp.apptTimeSlot = a != null ? a.getTimeSlot() : null;
                    wp.currentChairNumber = wc.getChairNumber();

                    Optional<Queue> qOpt = queueRepository.findByCustomerIdAndStatusIn(
                            c.getId(), Arrays.asList("waiting", "called"));
                    if (qOpt.isPresent()) {
                        wp.queue = qOpt.get();
                        if (wp.service == null) wp.service = wp.queue.getService();
                        if (wp.tokenNumber == null) wp.tokenNumber = wp.queue.getTokenNumber();
                    }
                    waitingLine.add(wp);
                }
            }

            // 5. Collect unseated active appointments (appointments with chair == null)
            for (Appointment a : unseatedAppts) {
                Customer c = a.getCustomer();
                if (c != null && !seenCustomerIds.contains(c.getId()) && !busyMainCustomerIds.contains(c.getId())) {
                    seenCustomerIds.add(c.getId());
                    WaitingPerson wp = new WaitingPerson();
                    wp.customer = c;
                    wp.service = a.getService();
                    wp.tokenNumber = a.getTokenNumber();
                    wp.appointment = a;
                    wp.apptTimeSlot = a.getTimeSlot();
                    wp.currentChairNumber = 999;

                    Optional<Queue> qOpt = queueRepository.findByCustomerIdAndStatusIn(
                            c.getId(), Arrays.asList("waiting", "called"));
                    if (qOpt.isPresent()) {
                        wp.queue = qOpt.get();
                    }
                    waitingLine.add(wp);
                }
            }

            // 6. Collect unseated queues from digital waiting list
            List<Queue> unseatedQueues = queueRepository.findByStatusInAndCreatedAtAfterOrderByCreatedAtAsc(
                    Arrays.asList("waiting", "called"), startOfDay);
            for (Queue q : unseatedQueues) {
                if (q.getChair() != null && q.getChair().getChairNumber() <= 2 &&
                        !availableMainChairs.stream().anyMatch(c -> c.getId().equals(q.getChair().getId()))) {
                    continue; // Serving on busy main chair
                }
                Customer c = q.getCustomer();
                if (c != null && !seenCustomerIds.contains(c.getId()) && !busyMainCustomerIds.contains(c.getId())) {
                    seenCustomerIds.add(c.getId());
                    WaitingPerson wp = new WaitingPerson();
                    wp.customer = c;
                    wp.service = q.getService();
                    wp.tokenNumber = q.getTokenNumber();
                    wp.queue = q;
                    wp.currentChairNumber = q.getChair() != null ? q.getChair().getChairNumber() : 999;

                    if (q.getTokenNumber() != null) {
                        List<Appointment> foundAppts = appointmentRepository.findByTokenNumber(q.getTokenNumber());
                        if (!foundAppts.isEmpty()) {
                            wp.appointment = foundAppts.get(0);
                            wp.apptTimeSlot = wp.appointment.getTimeSlot();
                        }
                    }
                    waitingLine.add(wp);
                }
            }

            // Sort waitingLine:
            // 1. Current chair number ascending (3 before 4 before 5 before 6 before 999)
            // 2. Unseated entries (999) sorted by appointment ID or queue ID
            waitingLine.sort((p1, p2) -> {
                if (p1.currentChairNumber != p2.currentChairNumber) {
                    return Integer.compare(p1.currentChairNumber, p2.currentChairNumber);
                }
                Long id1 = p1.appointment != null && p1.appointment.getId() != null ? p1.appointment.getId()
                        : (p1.queue != null && p1.queue.getId() != null ? p1.queue.getId() : 0L);
                Long id2 = p2.appointment != null && p2.appointment.getId() != null ? p2.appointment.getId()
                        : (p2.queue != null && p2.queue.getId() != null ? p2.queue.getId() : 0L);
                return Long.compare(id1, id2);
            });

            // 7. ROTATION PROCESS:
            // Step A: Promote candidates into available main chairs
            for (Chair mc : availableMainChairs) {
                // Determine whether the main chair has a future confirmed appointment
                // that has NOT yet started (i.e. the barber chair is specifically reserved for a later customer)
                boolean hasUpcomingConfirmed = false;
                for (Appointment a : allDateAppts) {
                    if (a.getChair() != null && a.getChair().getId().equals(mc.getId())
                            && "confirmed".equalsIgnoreCase(a.getStatus())) {
                        int sMin = parseTimeToMinutes(a.getTimeSlot());
                        int nowMin = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
                        // Only block if this future appointment starts more than 10 min from now
                        if (sMin >= 0 && sMin > nowMin + 10) {
                            hasUpcomingConfirmed = true;
                            break;
                        }
                    }
                }

                // Find the first eligible candidate:
                // - Walk-in queue (no specific slot) → always eligible
                // - Waiting chair customer → eligible if main seat is genuinely free (no upcoming confirmed booking)
                int candidateIdx = -1;
                for (int i = 0; i < waitingLine.size(); i++) {
                    WaitingPerson wp = waitingLine.get(i);
                    boolean isLiveQueue = (wp.apptTimeSlot == null || "Next Available".equalsIgnoreCase(wp.apptTimeSlot));
                    if (isLiveQueue) {
                        candidateIdx = i;
                        break;
                    }
                    // For slotted waiting-chair customers, promote if seat has no upcoming reserved appointment
                    if (!hasUpcomingConfirmed) {
                        candidateIdx = i;
                        break;
                    }
                }

                if (candidateIdx >= 0) {
                    WaitingPerson promoted = waitingLine.remove(candidateIdx);
                    stateChanged = true;

                    mc.setStatus("occupied");
                    mc.setReservedByCustomer(promoted.customer);
                    mc.setReservedAt(now);
                    chairRepository.save(mc);

                    int dur = (promoted.service != null && promoted.service.getDurationMinutes() > 0)
                            ? promoted.service.getDurationMinutes() : 30;
                    DateTimeFormatter tf = DateTimeFormatter.ofPattern("hh:mm a");
                    LocalTime sTime = LocalTime.now();
                    LocalTime eTime = sTime.plusMinutes(dur);
                    String calculatedServingSlot = sTime.format(tf).toLowerCase() + " - " + eTime.format(tf).toLowerCase();

                    if (promoted.appointment != null) {
                        promoted.appointment.setChair(mc);
                        promoted.appointment.setStatus(date.equals(LocalDate.now()) ? "in_progress" : "confirmed");
                        promoted.appointment.setStartedAt(now);
                        if (promoted.appointment.getTimeSlot() == null || "Next Available".equalsIgnoreCase(promoted.appointment.getTimeSlot())) {
                            promoted.appointment.setTimeSlot(calculatedServingSlot);
                        }
                        promoted.appointment.setNotes("(Seat " + mc.getChairNumber() + ")");
                        appointmentRepository.save(promoted.appointment);
                    } else {
                        Appointment newAppt = new Appointment();
                        newAppt.setCustomer(promoted.customer);
                        newAppt.setService(promoted.service);
                        newAppt.setChair(mc);
                        newAppt.setTokenNumber(promoted.tokenNumber != null ? promoted.tokenNumber : generateTokenNumber(date));
                        newAppt.setAppointmentDate(date);
                        newAppt.setTimeSlot(calculatedServingSlot);
                        newAppt.setStatus(date.equals(LocalDate.now()) ? "in_progress" : "confirmed");
                        newAppt.setStartedAt(now);
                        newAppt.setNotes("(Seat " + mc.getChairNumber() + ")");
                        appointmentRepository.save(newAppt);
                    }

                    if (promoted.queue != null) {
                        promoted.queue.setChair(mc);
                        promoted.queue.setStatus(date.equals(LocalDate.now()) ? "serving" : "called");
                        promoted.queue.setServedAt(now);
                        promoted.queue.setPosition(0);
                        queueRepository.save(promoted.queue);
                    }
                } else {
                    // No live waiting customers eligible right now for this main chair
                    mc.setStatus("available");
                    mc.setReservedByCustomer(null);
                    mc.setReservedAt(null);
                    chairRepository.save(mc);
                }
            }

            // Step B: Compact the remaining candidates into Waiting Chairs 1..4 (Chairs 3, 4, 5, 6)!
            // - Previous Waiting Chair 2 becomes Waiting Chair 1
            // - Previous Waiting Chair 3 becomes Waiting Chair 2
            // - Previous Waiting Chair 4 becomes Waiting Chair 3
            // - First Join Waiting List becomes Waiting Chair 4!
            for (int i = 0; i < waitingChairs.size(); i++) {
                Chair wc = waitingChairs.get(i);
                int waitChairNum = i + 1; // Waiting Chair 1, 2, 3, 4

                if (i < waitingLine.size()) {
                    WaitingPerson person = waitingLine.get(i);

                    wc.setStatus("waiting");
                    wc.setReservedByCustomer(person.customer);
                    wc.setReservedAt(now);
                    chairRepository.save(wc);

                    if (person.appointment != null) {
                        person.appointment.setChair(wc);
                        person.appointment.setStatus("confirmed");
                        person.appointment.setNotes("(Waiting Chair " + waitChairNum + ")");
                        appointmentRepository.save(person.appointment);
                    } else {
                        Appointment newAppt = new Appointment();
                        newAppt.setCustomer(person.customer);
                        newAppt.setService(person.service);
                        newAppt.setChair(wc);
                        newAppt.setTokenNumber(person.tokenNumber != null ? person.tokenNumber : generateTokenNumber(date));
                        newAppt.setAppointmentDate(date);
                        newAppt.setTimeSlot(person.apptTimeSlot != null ? person.apptTimeSlot : "Next Available");
                        newAppt.setStatus("confirmed");
                        newAppt.setNotes("(Waiting Chair " + waitChairNum + ")");
                        appointmentRepository.save(newAppt);
                    }

                    if (person.queue != null) {
                        person.queue.setChair(wc);
                        person.queue.setStatus("waiting");
                        person.queue.setPosition(waitChairNum);
                        person.queue.setEstimatedWaitMinutes(Math.max(0, waitChairNum * 30));
                        queueRepository.save(person.queue);
                    }
                } else {
                    // Empty waiting chair - FREE SEAT!
                    wc.setStatus("available");
                    wc.setReservedByCustomer(null);
                    wc.setReservedAt(null);
                    chairRepository.save(wc);
                }
            }

            // Step C: Any remaining people beyond Waiting Chair 4 remain on the Waiting List (chair == null)!
            for (int i = waitingChairs.size(); i < waitingLine.size(); i++) {
                WaitingPerson person = waitingLine.get(i);
                int virtualPos = (i - waitingChairs.size()) + 1;

                if (person.appointment != null) {
                    person.appointment.setChair(null);
                    person.appointment.setStatus("pending");
                    person.appointment.setNotes("(Queued: Waiting List #" + virtualPos + ")");
                    appointmentRepository.save(person.appointment);
                }

                if (person.queue != null) {
                    person.queue.setChair(null);
                    person.queue.setStatus("waiting");
                    person.queue.setPosition(virtualPos);
                    person.queue.setEstimatedWaitMinutes(Math.max(0, (waitingChairs.size() + virtualPos) * 30));
                    queueRepository.save(person.queue);
                }
            }

            if (stateChanged) {
                if (queueService != null) {
                    queueService.recalculatePositions();
                }
                webSocketHandler.broadcast("slot-update");
                webSocketHandler.broadcast("chair-update");
                webSocketHandler.broadcast("queue-update");
                webSocketHandler.broadcast("appointment-update");
            }
        }
    }

    public Appointment cancelAppointment(Long appointmentId, Long customerId) {
        Appointment appointment = getAppointmentById(appointmentId);

        if (customerId != null && !appointment.getCustomer().getId().equals(customerId)) {
            throw new IllegalArgumentException("Access denied. You do not own this appointment.");
        }

        if (Arrays.asList("completed", "cancelled").contains(appointment.getStatus())) {
            throw new IllegalStateException("Appointment is already " + appointment.getStatus());
        }

        appointment.setStatus("cancelled");
        Appointment saved = appointmentRepository.save(appointment);

        // Release the chair
        if (appointment.getChair() != null) {
            Chair chair = appointment.getChair();
            chair.setStatus("available");
            chair.setReservedByCustomer(null);
            chair.setReservedAt(null);
            chairRepository.save(chair);
            rotateAndPromoteWaitingChairs(chair.getId(), appointment.getAppointmentDate(), null);
        }

        // Cancel token and any associated queue
        boolean queueCancelled = false;
        List<Token> cancelTokens = tokenRepository.findByAppointmentId(appointment.getId());
        if (cancelTokens.isEmpty() && appointment.getTokenNumber() != null) {
            cancelTokens = tokenRepository.findByTokenNumber(appointment.getTokenNumber());
        }
        for (Token token : cancelTokens) {
            token.setStatus("cancelled");
            tokenRepository.save(token);
            if (token.getQueue() != null) {
                Queue q = token.getQueue();
                if (!"completed".equals(q.getStatus()) && !"cancelled".equals(q.getStatus())) {
                    q.setStatus("cancelled");
                    q.setCompletedAt(LocalDateTime.now());
                    queueRepository.save(q);
                    queueCancelled = true;
                }
            }
        }

        if (appointment.getTokenNumber() != null) {
            List<Queue> matching = queueRepository.findByTokenNumber(appointment.getTokenNumber());
            for (Queue q : matching) {
                if (!"completed".equals(q.getStatus()) && !"cancelled".equals(q.getStatus())) {
                    q.setStatus("cancelled");
                    q.setCompletedAt(LocalDateTime.now());
                    queueRepository.save(q);
                    queueCancelled = true;
                }
            }
        }

        if (queueCancelled && queueService != null) {
            queueService.recalculatePositions();
            webSocketHandler.broadcast("queue-update");
        }

        webSocketHandler.broadcast("slot-update");
        webSocketHandler.broadcast("chair-update");
        webSocketHandler.broadcast("appointment-update");

        return saved;
    }

    public Appointment completeAppointment(Long appointmentId) {
        Appointment appointment = getAppointmentById(appointmentId);

        if ("completed".equals(appointment.getStatus())) {
            throw new IllegalStateException("Appointment is already completed");
        }

        appointment.setStatus("completed");
        appointment.setCompletedAt(LocalDateTime.now());
        Appointment saved = appointmentRepository.save(appointment);

        // Release the chair
        Chair freedChair = null;
        if (appointment.getChair() != null) {
            freedChair = appointment.getChair();
            freedChair.setStatus("available");
            freedChair.setReservedByCustomer(null);
            freedChair.setReservedAt(null);
            chairRepository.save(freedChair);
        }

        // Complete token and any associated queue entries
        boolean queueCompleted = false;
        List<Token> completeTokens = tokenRepository.findByAppointmentId(appointment.getId());
        if (completeTokens.isEmpty() && appointment.getTokenNumber() != null) {
            completeTokens = tokenRepository.findByTokenNumber(appointment.getTokenNumber());
        }
        for (Token token : completeTokens) {
            token.setStatus("used");
            tokenRepository.save(token);
            if (token.getQueue() != null) {
                Queue q = token.getQueue();
                if (!"completed".equals(q.getStatus()) && !"cancelled".equals(q.getStatus())) {
                    q.setStatus("completed");
                    q.setCompletedAt(LocalDateTime.now());
                    queueRepository.save(q);
                    queueCompleted = true;
                }
            }
        }

        if (appointment.getTokenNumber() != null) {
            List<Queue> matching = queueRepository.findByTokenNumber(appointment.getTokenNumber());
            for (Queue q : matching) {
                if (!"completed".equals(q.getStatus()) && !"cancelled".equals(q.getStatus())) {
                    q.setStatus("completed");
                    q.setCompletedAt(LocalDateTime.now());
                    queueRepository.save(q);
                    queueCompleted = true;
                }
            }
        }

        if (appointment.getCustomer() != null) {
            List<Queue> custQueues = queueRepository.findAllByCustomerIdAndStatusIn(
                    appointment.getCustomer().getId(), Arrays.asList("waiting", "called", "serving"));
            for (Queue q : custQueues) {
                q.setStatus("completed");
                q.setCompletedAt(LocalDateTime.now());
                if (q.getChair() != null) {
                    Chair qc = q.getChair();
                    qc.setStatus("available");
                    qc.setReservedByCustomer(null);
                    qc.setReservedAt(null);
                    chairRepository.save(qc);
                }
                queueRepository.save(q);
                queueCompleted = true;
            }
        }

        if (queueCompleted && queueService != null) {
            queueService.recalculatePositions();
        }

        // ── Auto-promote from waiting chairs if main seat freed, or call next waiting customer ──
        if (freedChair != null && freedChair.getChairNumber() <= 2) {
            rotateAndPromoteWaitingChairs(freedChair.getId(), appointment.getAppointmentDate(), null);
        } else if (queueService != null) {
            queueService.callNextCustomer();
        }

        webSocketHandler.broadcast("slot-update");
        webSocketHandler.broadcast("queue-update");
        webSocketHandler.broadcast("chair-update");
        webSocketHandler.broadcast("appointment-update");

        return saved;
    }

    public Appointment startAppointment(Long appointmentId) {
        Appointment appointment = getAppointmentById(appointmentId);
        appointment.setStatus("in_progress");
        appointment.setStartedAt(LocalDateTime.now());

        // Assign chair if not assigned yet
        if (appointment.getChair() == null) {
            Chair availableChair = chairRepository.findByStatusAndIsActiveTrueOrderByChairNumberAsc("available")
                    .stream().findFirst().orElse(null);
            if (availableChair != null) {
                appointment.setChair(availableChair);
            }
        }

        Appointment saved = appointmentRepository.save(appointment);

        if (appointment.getChair() != null) {
            Chair chair = appointment.getChair();
            chair.setStatus("occupied");
            chair.setReservedByCustomer(appointment.getCustomer());
            chair.setReservedAt(LocalDateTime.now());
            chairRepository.save(chair);
        }

        // ── SYNC TO USER QUEUE: Advance status to 'serving' (SEATED) ──
        boolean queueUpdated = false;
        // 1. By token number
        if (appointment.getTokenNumber() != null) {
            List<Queue> matching = queueRepository.findByTokenNumber(appointment.getTokenNumber());
            for (Queue q : matching) {
                if (!"completed".equalsIgnoreCase(q.getStatus()) && !"cancelled".equalsIgnoreCase(q.getStatus())) {
                    q.setStatus("serving");
                    q.setChair(appointment.getChair());
                    q.setServedAt(LocalDateTime.now());
                    queueRepository.save(q);
                    queueUpdated = true;
                }
            }
        }
        // 2. By customer ID (active queue entries for this customer today)
        if (appointment.getCustomer() != null) {
            List<Queue> custQueues = queueRepository.findAllByCustomerIdAndStatusIn(
                    appointment.getCustomer().getId(), Arrays.asList("waiting", "called"));
            for (Queue q : custQueues) {
                q.setStatus("serving");
                q.setChair(appointment.getChair());
                q.setServedAt(LocalDateTime.now());
                queueRepository.save(q);
                queueUpdated = true;
            }
        }

        if (queueUpdated && queueService != null) {
            queueService.recalculatePositions();
        }

        webSocketHandler.broadcast("queue-update");
        webSocketHandler.broadcast("appointment-update");
        return saved;
    }
}
