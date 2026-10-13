/**
 * Beauty Salon — Complete Real-Time Admin Panel
 * Manages 2 Service Seats, Time Slots / Bookings, and Strict FIFO Waiting Queue
 */

let adminSocket = null;
let selectedAdminDate = new Date().toISOString().split('T')[0];
let pendingFinishChairId = null;
let pendingFinishSeatName = '';
let pendingFinishCustomerName = '';
let isFinishingService = false;
let lastSeatsRenderSignature = '';
let lastSlotsRenderSignature = '';
let lastQueueRenderSignature = '';
let pollTimer = null;

// Show Admin Toast Notification (No icons)
function showAdminToast(message, type = 'danger') {
  const container = document.getElementById('toast-container');
  if (!container) return;

  const toast = document.createElement('div');
  const bgClass = type === 'success' ? 'bg-success' : (type === 'warning' ? 'bg-warning text-dark' : 'bg-danger');
  toast.className = `toast align-items-center text-white ${bgClass} border-0 show shadow-sm mb-2`;
  toast.setAttribute('role', 'alert');
  toast.style.minWidth = '280px';

  toast.innerHTML = `
    <div class="d-flex">
      <div class="toast-body small fw-semibold">
        ${message}
      </div>
      <button type="button" class="btn-close ${type === 'warning' ? '' : 'btn-close-white'} me-2 m-auto" data-bs-dismiss="toast" aria-label="Close"></button>
    </div>
  `;

  container.appendChild(toast);
  setTimeout(() => {
    toast.classList.remove('show');
    setTimeout(() => toast.remove(), 300);
  }, 4000);
}

// Check Admin Authorization
function checkAdminAuth() {
  const token = localStorage.getItem('barber_token');
  const userType = localStorage.getItem('barber_user_type');

  if (!token || userType !== 'admin') {
    window.location.href = '/auth/admin-login.html';
    return false;
  }

  const user = JSON.parse(localStorage.getItem('barber_user') || '{}');
  const adminNameDisplay = document.getElementById('admin-username-display');
  if (adminNameDisplay) {
    adminNameDisplay.textContent = user.full_name || user.username || 'Administrator';
  }

  return true;
}

// Format local date for header
function updateAdminClock() {
  const now = new Date();
  const dateStr = now.toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' });
  const dateEl = document.getElementById('admin-date-label');
  if (dateEl) dateEl.textContent = dateStr;
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. LEFT COLUMN: 2 SERVICE SEATS (Matches Customer Layout)
// ─────────────────────────────────────────────────────────────────────────────

async function fetchAdminSeats(date) {
  const targetDate = date || selectedAdminDate;
  const token = localStorage.getItem('barber_token');
  const container = document.getElementById('admin-seats-container');
  if (!container) return;

  try {
    const res = await fetch(`/api/chairs?date=${targetDate}`, {
      headers: { 'Authorization': `Bearer ${token}` }
    });

    if (res.status === 401 || res.status === 403) {
      checkAdminAuth();
      return;
    }

    const json = await res.json();
    if (!res.ok) throw new Error(json.message || 'Failed to fetch seats');

    const chairs = json.data || [];
    renderAdminSeats(chairs);
  } catch (err) {
    console.error('Error fetching admin seats:', err);
    container.innerHTML = `
      <div class="alert alert-danger p-2 small shadow-sm">
        <div class="fw-semibold mb-1"><i class="fa-solid fa-triangle-exclamation me-1"></i> Failed to load seats.</div>
        <div class="text-muted small mb-2" style="font-size:0.75rem;">${err.message || 'Error communicating with server.'}</div>
        <button type="button" class="btn btn-sm btn-outline-danger px-2 py-0" style="font-size:0.75rem" onclick="lastSeatsRenderSignature=''; fetchAdminSeats();">
          <i class="fa-solid fa-rotate-right me-1"></i> Retry
        </button>
      </div>
    `;
  }
}

function renderAdminSeats(chairs) {
  const container = document.getElementById('admin-seats-container');
  if (!container) return;

  // Do not re-render DOM while completing a service or while confirmation modal is active
  if (isFinishingService) return;

  // Build a unique signature of the chairs data
  const currentSignature = (chairs || []).map(c => 
    `${c.chair_number}:${c.status}:${c.customer_name || ''}:${c.time || ''}:${c.token_number || ''}`
  ).join('|');

  // If data is identical and container already has elements, skip completely to prevent flicker
  if (currentSignature === lastSeatsRenderSignature && container.children.length > 0) {
    return;
  }
  lastSeatsRenderSignature = currentSignature;

  let seat1 = chairs.find(c => c.chair_number === 1) || { chair_number: 1, name: 'Seat 1', status: 'available', customer_name: '---', time: '---' };
  let seat2 = chairs.find(c => c.chair_number === 2) || { chair_number: 2, name: 'Seat 2', status: 'available', customer_name: '---', time: '---' };

  const waitChairs = [3, 4, 5, 6].map((num, i) => {
    return chairs.find(c => c.chair_number === num) || {
      chair_number: num,
      name: `Waiting Chair ${i + 1}`,
      chair_type: 'waiting',
      status: 'available',
      customer_name: '---',
      time: '---',
      token_number: '---'
    };
  });

  const seats = [seat1, seat2];

  let html = `
    <div class="mb-2">
      <span class="fw-bold small text-secondary text-uppercase"><i class="fa-solid fa-scissors me-1 text-primary"></i> Main Service Seats (2)</span>
    </div>
  `;

  seats.forEach(seat => {
    const isOccupied = seat.status === 'occupied' || (seat.customer_name && seat.customer_name !== '---');
    const statusClass = isOccupied ? 'occupied' : 'available';
    const statusBadge = isOccupied ? 'CURRENTLY SERVING' : 'AVAILABLE';

    const customerDisplay = isOccupied
      ? `<span class="fw-bold text-dark fs-6">${seat.customer_name}</span>`
      : `<span class="text-muted fst-italic">---</span>`;

    const timeDisplay = isOccupied && seat.time && seat.time !== '---'
      ? `<span class="fw-semibold text-primary">${seat.time}</span>`
      : `<span class="text-muted">---</span>`;

    const finishBtn = isOccupied
      ? `
        <button type="button" class="admin-finish-btn mt-3" onclick="promptFinishService(${seat.id || seat.chair_number}, 'Seat ${seat.chair_number}', '${escapeQuotes(seat.customer_name)}')">
          <i class="fa-solid fa-circle-check me-1"></i> Finish Service
        </button>
      `
      : `
        <button type="button" class="admin-finish-btn mt-3" disabled style="opacity:0.65;">
          <i class="fa-regular fa-circle me-1"></i> Available
        </button>
      `;

    html += `
      <div class="seat-card-compact ${statusClass}">
        <div class="seat-title-row">
          <div class="seat-name">
            <i class="fa-solid fa-scissors me-1 text-primary"></i> Seat ${seat.chair_number}
          </div>
          <span class="seat-status-badge ${statusClass}">${statusBadge}</span>
        </div>

        <div class="seat-meta-row mt-2">
          <span class="seat-meta-label">Customer:</span>
          <span class="seat-meta-val">${customerDisplay}</span>
        </div>

        <div class="seat-meta-row">
          <span class="seat-meta-label">Time:</span>
          <span class="seat-meta-val">${timeDisplay}</span>
        </div>

        <div class="seat-meta-row">
          <span class="seat-meta-label">Status:</span>
          <span class="seat-meta-val small fw-bold ${isOccupied ? 'text-danger' : 'text-success'}">
            ${isOccupied ? 'CURRENTLY SERVING' : 'AVAILABLE'}
          </span>
        </div>

        ${finishBtn}
      </div>
    `;
  });

  html += `
    <div class="mt-3 mb-2 d-flex justify-content-between align-items-center">
      <span class="fw-bold small text-secondary text-uppercase"><i class="fa-solid fa-chair me-1 text-warning"></i> Waiting Chairs (4)</span>
      <span class="badge bg-light text-muted border" style="font-size:0.65rem;">Auto-Promotes to Main Seat</span>
    </div>
    <div class="waiting-chairs-grid">
  `;

  waitChairs.forEach((wc, i) => {
    const custName = wc.customer_name && wc.customer_name !== '---'
      ? wc.customer_name
      : (wc.reserved_by_customer ? wc.reserved_by_customer.name : '---');
    const isOccupied = wc.status === 'occupied' || wc.status === 'waiting' || (custName !== '---');
    const tok = wc.token_number && wc.token_number !== '---' ? wc.token_number : '';

    html += `
      <div class="waiting-chair-card ${isOccupied ? 'waiting' : 'available'}">
        <div class="d-flex justify-content-between align-items-center mb-1">
          <span class="fw-bold text-dark" style="font-size:0.82rem;">
            <i class="fa-solid fa-chair me-1 text-warning"></i> W${i + 1}
          </span>
          <span class="badge ${isOccupied ? 'bg-warning text-dark' : 'bg-success text-white'}" style="font-size:0.62rem;">
            ${isOccupied ? 'SEATED' : 'FREE'}
          </span>
        </div>
        <div class="small text-truncate ${isOccupied ? 'fw-bold text-dark' : 'text-muted'}" style="font-size:0.76rem;">
          ${isOccupied ? custName : 'Ready'}
        </div>
        ${isOccupied && wc.time && wc.time !== '---' ? `<div class="text-secondary" style="font-size:0.68rem;"><i class="fa-regular fa-clock me-1"></i>${wc.time}</div>` : ''}
        ${tok ? `<div class="text-primary fw-bold" style="font-size:0.68rem;">Token: ${tok}</div>` : `<div class="text-muted" style="font-size:0.68rem;">Free Seat</div>`}
      </div>
    `;
  });

  html += `</div>`;
  container.innerHTML = html;
}

function escapeQuotes(str) {
  if (!str) return '';
  return str.replace(/'/g, "\\'").replace(/"/g, '&quot;');
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. FINISH SERVICE ACTION (ATOMIC AUTO-HANDOVER)
// ─────────────────────────────────────────────────────────────────────────────

function promptFinishService(chairId, seatName, customerName) {
  if (isFinishingService) return;
  pendingFinishChairId = chairId;
  pendingFinishSeatName = seatName;
  pendingFinishCustomerName = customerName;

  const custNameEl = document.getElementById('finish-customer-name');
  const seatNameEl = document.getElementById('finish-seat-name');
  if (custNameEl) custNameEl.textContent = customerName || 'Current Customer';
  if (seatNameEl) seatNameEl.textContent = seatName;

  const modalEl = document.getElementById('finishServiceModal');
  if (modalEl) {
    const modal = bootstrap.Modal.getOrCreateInstance(modalEl);
    modal.show();
  }
}

async function executeFinishService() {
  if (!pendingFinishChairId || isFinishingService) return;
  isFinishingService = true;

  const btn = document.getElementById('btn-confirm-finish');
  if (btn) {
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>Finishing…';
  }

  // Disable seat finish buttons for instant feedback
  document.querySelectorAll('.admin-finish-btn').forEach(b => {
    b.disabled = true;
    b.style.pointerEvents = 'none';
  });

  const token = localStorage.getItem('barber_token');

  try {
    const url = `/api/chairs/${pendingFinishChairId}/complete-service` + (selectedAdminDate ? `?date=${encodeURIComponent(selectedAdminDate)}` : '');
    const res = await fetch(url, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      }
    });

    const json = await res.json();

    if (!res.ok) {
      throw new Error(json.message || 'Failed to complete service');
    }

    // Close modal cleanly
    const modalEl = document.getElementById('finishServiceModal');
    if (modalEl) {
      const modal = bootstrap.Modal.getInstance(modalEl);
      if (modal) modal.hide();
    }

    showAdminToast(`Service finished for ${pendingFinishSeatName}. Queue updated!`, 'success');

    // Invalidate signatures so immediate re-render reflects changes cleanly
    lastSeatsRenderSignature = '';
    lastSlotsRenderSignature = '';
    lastQueueRenderSignature = '';
    refreshAllAdminPanels();

  } catch (err) {
    console.error('Error completing service:', err);
    showAdminToast(err.message || 'Error completing service', 'danger');
    // Restore button interactivity on error
    document.querySelectorAll('.admin-finish-btn').forEach(b => {
      b.disabled = false;
      b.style.pointerEvents = 'auto';
    });
  } finally {
    isFinishingService = false;
    pendingFinishChairId = null;
    if (btn) {
      btn.disabled = false;
      btn.innerHTML = 'Complete Service';
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. CENTER COLUMN: TIME SLOTS & BOOKINGS
// ─────────────────────────────────────────────────────────────────────────────

function selectAdminDateFilter(period) {
  document.querySelectorAll('.slot-date-btn').forEach(b => b.classList.remove('active'));

  const today = new Date();
  let target = new Date();

  if (period === 'today') {
    document.getElementById('admin-btn-date-today').classList.add('active');
  } else if (period === 'tomorrow') {
    target.setDate(today.getDate() + 1);
    document.getElementById('admin-btn-date-tomorrow').classList.add('active');
  } else if (period === 'dayafter') {
    target.setDate(today.getDate() + 2);
    document.getElementById('admin-btn-date-dayafter').classList.add('active');
  }

  selectedAdminDate = target.toISOString().split('T')[0];
  const dateInput = document.getElementById('admin-slot-picker-date');
  if (dateInput) dateInput.value = selectedAdminDate;

  lastSeatsRenderSignature = '';
  lastSlotsRenderSignature = '';
  lastQueueRenderSignature = '';
  fetchAdminSeats(selectedAdminDate);
  fetchAdminSlots();
}

function onAdminDateChanged(val) {
  if (!val) return;
  selectedAdminDate = val;
  document.querySelectorAll('.slot-date-btn').forEach(b => b.classList.remove('active'));
  lastSeatsRenderSignature = '';
  lastSlotsRenderSignature = '';
  lastQueueRenderSignature = '';
  fetchAdminSeats(selectedAdminDate);
  fetchAdminSlots();
}

async function fetchAdminSlots() {
  const token = localStorage.getItem('barber_token');
  const container = document.getElementById('admin-slots-list');
  if (!container) return;

  try {
    const res = await fetch(`/api/appointments/slots/detailed?date=${encodeURIComponent(selectedAdminDate)}`, {
      headers: { 'Authorization': `Bearer ${token}` }
    });

    if (res.status === 401 || res.status === 403) {
      checkAdminAuth();
      return;
    }

    let json = null;
    try {
      json = await res.json();
    } catch (parseErr) {
      // Non-JSON response (e.g., 500 HTML or connection error)
    }

    if (!res.ok || !json) {
      throw new Error((json && json.message) || `Server returned error (${res.status})`);
    }

    renderAdminSlots(json.data || []);
  } catch (err) {
    console.error('Error fetching admin slots:', err);
    container.innerHTML = `
      <div class="alert alert-danger p-3 small shadow-sm">
        <div class="fw-bold mb-1"><i class="fa-solid fa-triangle-exclamation me-1"></i> Failed to load time slots.</div>
        <div class="text-muted mb-2" style="font-size:0.78rem;">${err.message || 'Database connection error'}. Make sure MySQL service (MySQL80) is running.</div>
        <button type="button" class="btn btn-sm btn-outline-danger px-3" onclick="fetchAdminSlots()" style="font-size:0.75rem;">
          <i class="fa-solid fa-rotate-right me-1"></i> Retry
        </button>
      </div>
    `;
  }
}

function renderAdminSlots(slots) {
  const container = document.getElementById('admin-slots-list');
  if (!container) return;

  const signature = JSON.stringify(slots);
  if (signature === lastSlotsRenderSignature && container.children.length > 0) {
    return;
  }
  lastSlotsRenderSignature = signature;

  if (!slots || slots.length === 0) {
    container.innerHTML = `
      <div class="text-center py-5 text-muted small">
        No available time slots remaining for the selected date.
      </div>
    `;
    return;
  }

  let html = '';
  slots.forEach(slot => {
    const capacity = slot.capacity || 2;
    const assigned = slot.assignedCount || 0;
    const isFull = assigned >= capacity;

    let badgePill = '';
    if (assigned === 0) {
      badgePill = `<span class="slot-badge-pill available-2">0 / ${capacity} AVAILABLE</span>`;
    } else if (assigned < capacity) {
      badgePill = `<span class="slot-badge-pill available-1">${assigned} / ${capacity} AVAILABLE</span>`;
    } else {
      badgePill = `<span class="slot-badge-pill full">${assigned} / ${capacity} FULL</span>`;
    }

    // Booked customers info for this slot
    let customersHtml = '';
    if (slot.bookedCustomers && slot.bookedCustomers.length > 0) {
      customersHtml = `
        <div class="slot-customers-box">
          ${slot.bookedCustomers.map(c => `
            <span class="slot-booking-customer-chip">
              <strong>${c.customerName || 'Customer'}</strong>
              <span class="text-muted">(${c.chairNumber ? 'Seat ' + c.chairNumber : (c.tokenNumber || 'Booked')})</span>
            </span>
          `).join('')}
        </div>
      `;
    } else {
      customersHtml = `
        <div class="small text-muted mt-1 fst-italic" style="font-size:0.75rem;">
          No bookings yet for this slot
        </div>
      `;
    }

    html += `
      <div class="slot-capacity-card ${isFull ? 'full' : ''} flex-column align-items-stretch">
        <div class="d-flex align-items-center justify-content-between">
          <div class="slot-time-title">
            ${slot.timeSlot}
          </div>
          <div>${badgePill}</div>
        </div>
        ${customersHtml}
      </div>
    `;
  });

  container.innerHTML = html;
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. RIGHT COLUMN: FIFO WAITING LIST
// ─────────────────────────────────────────────────────────────────────────────

async function fetchAdminQueue() {
  const token = localStorage.getItem('barber_token');
  const container = document.getElementById('admin-waiting-queue-list');
  const countEl = document.getElementById('admin-waiting-queue-count');
  if (!container) return;

  try {
    const res = await fetch('/api/queue/waiting', {
      headers: { 'Authorization': `Bearer ${token}` }
    });

    if (res.status === 401 || res.status === 403) {
      checkAdminAuth();
      return;
    }

    const json = await res.json();
    if (!res.ok) throw new Error(json.message || 'Failed to fetch queue');

    const list = json.data || [];
    if (countEl) countEl.textContent = json.count !== undefined ? json.count : list.length;

    renderAdminQueue(list);
  } catch (err) {
    console.error('Error fetching admin queue:', err);
    container.innerHTML = `
      <div class="alert alert-danger p-2 small">
        Failed to load waiting list.
      </div>
    `;
  }
}

function renderAdminQueue(list) {
  const container = document.getElementById('admin-waiting-queue-list');
  if (!container) return;

  const signature = JSON.stringify(list);
  if (signature === lastQueueRenderSignature && container.children.length > 0) {
    return;
  }
  lastQueueRenderSignature = signature;

  if (!list || list.length === 0) {
    container.innerHTML = `
      <div class="text-center py-5 text-muted small">
        Queue is currently empty.<br>
        <span class="text-secondary" style="font-size:0.75rem;">Next customer who books a full slot will queue here.</span>
      </div>
    `;
    return;
  }

  let html = '';
  list.forEach((item, index) => {
    const rank = item.position || (index + 1);
    const customerName = item.customer_name || 'Customer';
    const requestedSlot = item.requested_slot || 'Next Available';
    const token = item.token_number || ('Q-' + rank);

    html += `
      <div class="queue-list-entry">
        <div class="d-flex align-items-center min-w-0">
          <div class="queue-rank-badge">#${rank}</div>
          <div class="text-truncate">
            <div class="fw-bold text-dark text-truncate" style="font-size:0.88rem;">${customerName}</div>
            <div class="text-muted" style="font-size:0.73rem;">
              <span class="text-primary fw-semibold">${token}</span> · Slot: ${requestedSlot}
            </div>
          </div>
        </div>
        <div class="ms-2 flex-shrink-0">
          <span class="badge bg-warning-subtle text-warning-emphasis border border-warning-subtle fw-semibold" style="font-size:0.7rem;">
            WAITING
          </span>
        </div>
      </div>
    `;
  });

  container.innerHTML = html;
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. REAL-TIME SYNCHRONIZATION & WEBSOCKET
// ─────────────────────────────────────────────────────────────────────────────

let adminDebounceTimer = null;
function scheduleAdminRefresh(delay = 800) {
  if (adminDebounceTimer) clearTimeout(adminDebounceTimer);
  adminDebounceTimer = setTimeout(refreshAllAdminPanels, delay);
}

function refreshAllAdminPanels() {
  fetchAdminSeats();
  fetchAdminSlots();
  fetchAdminQueue();
}

function startAdminPolling() {
  if (pollTimer) return;
  pollTimer = setInterval(refreshAllAdminPanels, 10000);
}

function stopAdminPolling() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

function setupAdminSockets() {
  try {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    adminSocket = new WebSocket(`${protocol}//${window.location.host}/ws`);

    adminSocket.onopen = () => {
      console.log('Admin WebSocket connected.');
      stopAdminPolling();
    };

    adminSocket.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (['chair-update', 'queue-update', 'slot-update', 'appointment-update'].includes(data.event)) {
          scheduleAdminRefresh(800);
        }
      } catch (e) {
        console.error('Error parsing socket message:', e);
      }
    };

    adminSocket.onerror = (err) => {
      console.warn('Admin WebSocket error:', err);
    };

    adminSocket.onclose = () => {
      startAdminPolling();
      setTimeout(setupAdminSockets, 5000);
    };

  } catch (err) {
    console.warn('Failed to initialize WebSocket:', err);
    startAdminPolling();
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. INITIALIZATION & LIFECYCLE
// ─────────────────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
  // Verify Admin Authentication
  if (!checkAdminAuth()) return;

  // Initialize date picker input
  const dateInput = document.getElementById('admin-slot-picker-date');
  if (dateInput) {
    dateInput.value = selectedAdminDate;
    dateInput.min = selectedAdminDate;
  }

  // Real-time Date
  updateAdminClock();

  // Initial Data Load
  refreshAllAdminPanels();

  // Connect WebSocket
  setupAdminSockets();

  // 30s background sync
  setInterval(refreshAllAdminPanels, 30000);
});
