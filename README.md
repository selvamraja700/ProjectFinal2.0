# BarberEase – Smart Appointment & Digital Queue Management System

A production-quality, real-time web application designed for beauty salons and barber shops to manage physical chairs, appointment bookings, and virtual waiting queues. Built on **Spring Boot 3 (Java 21)**, **MySQL**, and real-time **WebSockets**.

---

## 🚀 Key Features

* **Real-Time Chair Grid Map**: Displays the status of chairs using live indicators:
  * 🟢 **Green (Available)**: Ready for instant customer reservation.
  * 🟡 **Yellow (Reserved)**: Temporarily held with a countdown timer.
  * ⚫ **Grey (Occupied)**: Active grooming session in progress.
* **Temporary Reservation Holds**: Secures a chair dynamically during checkout. Includes automatic cleanup to release expired holds back to the available pool.
* **Smart Digital Queue Tokens**: When seats are occupied, customers can join a virtual line to receive sequential daily token numbers (e.g., `T001`, `T002`) and track their queue position live.
* **Automatic Queue Seating**: When a chair service finishes, the system automatically seats the next customer from the FIFO waiting queue into the vacated chair.
* **Secure Web APIs**: JWT token authentication, input validation, and password encryption.

---

## 🛠️ Technology Stack

* **Frontend**: HTML5, Vanilla CSS3, Bootstrap 5, Modern JavaScript, WebSockets
* **Backend**: Java 21, Spring Boot 3, Spring Data JPA, Spring Security, JWT, WebSockets
* **Database**: MySQL

---

## 📂 Project Structure

```text
BarberEase/
│
├── src/main/java/com/barberease/
│   ├── config/              # SecurityConfig, DatabaseSeeder, WebSocketConfig
│   ├── controllers/         # AppointmentController, AuthController, ChairController, CustomerController, QueueController, ServiceController, TokenController
│   ├── exceptions/          # GlobalExceptionHandler
│   ├── models/              # Appointment, Chair, Customer, Queue, Service, Token
│   ├── repositories/        # JPA Repositories
│   ├── security/            # JwtUtils, JwtTokenFilter, UserPrincipal
│   ├── services/            # AppointmentService, AuthService, ChairService, CustomerService, QueueService, ServiceService
│   ├── websocket/           # WebSocket handlers & broadcaster
│   └── BarberEaseApplication.java
│
├── src/main/resources/
│   ├── static/
│   │   ├── auth/            # login.html, register.html
│   │   ├── customer/        # dashboard.html, profile.html, queue.html
│   │   ├── css/             # style.css
│   │   ├── js/              # auth.js, dashboard.js, profile.js
│   │   └── index.html       # Customer booking portal
│   └── application.properties
│
├── pom.xml
├── run.ps1
└── run.bat
```

---

## ⚙️ Setup & Running

### 1. Database Configuration
Make sure MySQL is running and configure `src/main/resources/application.properties`:
```properties
spring.datasource.url=jdbc:mysql://localhost:3306/barberease_db?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
spring.datasource.username=root
spring.datasource.password=raj@123
```

### 2. Default Seeded Admin Credentials
- **Username**: `admin`
- **Password**: `admin123`
- **Admin Portal**: [http://localhost:3000/auth/admin-login.html](http://localhost:3000/auth/admin-login.html)
- **Admin Console**: [http://localhost:3000/admin/dashboard.html](http://localhost:3000/admin/dashboard.html)

### 3. Run Application
Run using the provided script:
```powershell
.\run.ps1
```
Or via Maven:
```powershell
mvn spring-boot:run
```

Access the application at [http://localhost:3000](http://localhost:3000).

---

## 🧪 Testing Checklist

1. **Customer Registration & Login**:
   - Navigate to `http://localhost:3000/auth/register.html` and create a customer account.
   - Log in at `http://localhost:3000/auth/login.html`.
2. **Booking & Seating**:
   - On the homepage `http://localhost:3000/`, pick a service, chair, and time slot to confirm booking.
3. **Queue Management**:
   - When chairs are occupied, join the waiting queue to get a live FIFO token.
4. **Admin Management & Auto Handover**:
   - Log in at `http://localhost:3000/auth/admin-login.html` with `admin` / `admin123`.
   - Access the 3-column real-time operations console at `http://localhost:3000/admin/dashboard.html`.
   - Click **Finish Service** on an occupied seat: the system completes the session and automatically seats the first customer waiting in the FIFO queue.
   - Manage services (Add, Edit, Delete) and inspect real-time daily/service/chair reports.
5. **Automated E2E Verification**:
   - Run the automated test suite:
     ```powershell
     node test_admin_e2e.js
     ```
