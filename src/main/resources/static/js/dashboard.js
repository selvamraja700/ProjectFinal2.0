/**
 * BarberEase – Customer Dashboard client logic
 */

let services = [];
let chairs = [];
let activeSession = null;
let selectedChair = null;
let reservationTimer = null;
let reservationSeconds = 300; // 5 minutes

let socket = null;
let currentWalletBalance = 0;
let authenticatedProfile = null;
let pendingBookingData = null;

function showToast(message, type = 'danger') {
  const container = document.getElementById('toast-container');
  if (!container) return;

  const toast = document.createElement('div');
  toast.className = `toast align-items-center text-white bg-${type} border-0 show`;
  toast.setAttribute('role', 'alert');
  toast.setAttribute('aria-live', 'assertive');
  toast.setAttribute('aria-atomic', 'true');

  toast.innerHTML = `
    <div class="d-flex">
      <div class="toast-body">
        <i class="fa-solid fa-circle-info me-2"></i> ${message}
      </div>
      <button type="button" class="btn-close btn-close-white me-2 m-auto" data-bs-dismiss="toast" aria-label="Close"></button>
    </div>
  `;

  container.appendChild(toast);
  setTimeout(() => {
    toast.remove();
  }, 4000);
}

// Fetch general services
async function fetchServices() {
  try {
    const res = await fetch('/api/services');
    const data = await res.json();
    if (res.ok) {
      services = (data.data || []).sort((a, b) => a.id - b.id);
      populateServiceDropdowns();
    }
  } catch (error) {
    console.error('Error fetching services:', error);
  }
}

// Populate service options in forms
function populateServiceDropdowns() {
  if (!services || services.length === 0) {
    return;
  }

  const bookingSelect = document.getElementById('booking-service');
  const queueSelect = document.getElementById('queue-service');
  const queueSelectAlt = document.getElementById('queue-service-select');
  const slotSelect = document.getElementById('slot-service-select');

  const optionsHTML = services.map(s => `
    <option value="${s.id}" data-price="${s.price}" data-duration="${s.duration_minutes}">
      ${s.name} — \u20B9${parseFloat(s.price).toFixed(2)} (${s.duration_minutes}m)
    </option>
  `).join('');

  if (bookingSelect) {
    bookingSelect.innerHTML = optionsHTML;
    calculateBookingSummary();
  }
  if (queueSelect) {
    queueSelect.innerHTML = optionsHTML;
  }
  if (queueSelectAlt) {
    queueSelectAlt.innerHTML = optionsHTML;
  }
  if (slotSelect) {
    slotSelect.innerHTML = optionsHTML;
    if (typeof updateSlotModalPrice === 'function') updateSlotModalPrice();
  }
}

let activeQueue = [];

// Fetch all chairs and waiting queue, then render 2 main service stations
async function fetchChairs(date) {
  try {
    const targetDate = date || selectedDate;
    const token = localStorage.getItem('barber_token');
    const headers = token ? { 'Authorization': `Bearer ${token}` } : {};

    const [chairsRes, queueRes] = await Promise.all([
      fetch('/api/chairs' + (targetDate ? `?date=${targetDate}` : '')),
      fetch('/api/queue' + (targetDate ? `?date=${targetDate}` : ''), { headers }).catch(() => ({ ok: false }))
    ]);

    const chairsData = await chairsRes.json();
    if (chairsRes.ok && chairsData.data) {
      // Keep all 6 chairs (Chairs 1 & 2 main, Chairs 3-6 waiting)
      chairs = chairsData.data.filter(c => c.is_active !== false);
    }

    if (queueRes.ok) {
      const queueData = await queueRes.json();
      activeQueue = queueData.data || [];
    }

    renderChairsGrid();
    updateStats();
  } catch (error) {
    console.error('Error fetching chairs:', error);
  }
}

// Update stats header counters
function updateStats() {
  let available = 0;
  let reserved = 0;
  let occupied = 0;

  chairs.slice(0, 2).forEach(c => {
    if (c.status === 'available') available++;
    else if (c.status === 'reserved') reserved++;
    else if (c.status === 'occupied') occupied++;
  });

  const setEl = (id, val) => { const el = document.getElementById(id); if (el) el.textContent = val; };
  setEl('chairs-available-count', available);
  setEl('chairs-reserved-count', reserved);
  setEl('chairs-occupied-count', occupied);

  // If no available chairs, show queue helper alert
  const queuePanel = document.getElementById('queue-alert-panel');
  if (queuePanel) {
    queuePanel.style.display = available === 0 ? 'block' : 'none';
  }
}

// ═════════════════════════════════════════════════════════════════
// 3-SECTION HORIZONTAL BOOKING SYSTEM: 2 SEATS, TIME SLOTS, WAITING QUEUE
// ═════════════════════════════════════════════════════════════════

function formatLocalDate(d = new Date()) {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

let selectedDate = formatLocalDate();
let currentSlots = [];
let currentWaitingQueue = [];

// 1. LEFT: Render Exactly 2 Service Seats
function renderChairsGrid() {
  renderSeats();
}

function renderSeats() {
  const container = document.getElementById('seats-container');
  if (!container) return;

  const seat1 = chairs.find(c => c.chair_number === 1) || { id: 1, chair_number: 1, name: 'Seat 1', chair_type: 'main', status: 'available' };
  const seat2 = chairs.find(c => c.chair_number === 2) || { id: 2, chair_number: 2, name: 'Seat 2', chair_type: 'main', status: 'available' };

  const waitChairs = [3, 4, 5, 6].map((num, i) => {
    return chairs.find(c => c.chair_number === num) || {
      id: num,
      chair_number: num,
      name: `Waiting Chair ${i + 1}`,
      chair_type: 'waiting',
      status: 'available',
      customer_name: '---',
      time: '---',
      token_number: '---'
    };
  });

  const renderMainSeatCard = (seat, seatNum) => {
    const custName = seat.customer_name && seat.customer_name !== '---' 
      ? seat.customer_name 
      : (seat.reserved_by_customer ? seat.reserved_by_customer.name : '---');

    const timeStr = seat.time && seat.time !== '---' ? seat.time : '---';

    const isServing = (seat.status === 'occupied') || (custName !== '---' && seat.status !== 'waiting');
    const statusClass = isServing ? 'occupied' : 'available';
    const statusBadge = isServing ? 'CURRENTLY SERVING' : 'AVAILABLE';

    return `
      <div class="seat-card-compact ${statusClass}">
        <div class="seat-title-row">
          <div class="seat-name">
            <i class="fa-solid fa-scissors me-1 text-primary"></i> Seat ${seatNum}
          </div>
          <span class="seat-status-badge ${statusClass}">${statusBadge}</span>
        </div>
        <div class="seat-meta-row">
          <span class="seat-meta-label">Customer:</span>
          <span class="seat-meta-val">${isServing ? custName : '---'}</span>
        </div>
        <div class="seat-meta-row">
          <span class="seat-meta-label">Time:</span>
          <span class="seat-meta-val">${isServing ? timeStr : '---'}</span>
        </div>
        <div class="seat-meta-row">
          <span class="seat-meta-label">Status:</span>
          <span class="seat-meta-val text-uppercase">${isServing ? 'CURRENTLY SERVING' : 'AVAILABLE'}</span>
        </div>
      </div>
    `;
  };

  const renderWaitChairCard = (wc, idx) => {
    const custName = wc.customer_name && wc.customer_name !== '---'
      ? wc.customer_name
      : (wc.reserved_by_customer ? wc.reserved_by_customer.name : '---');
    const isOccupied = wc.status === 'occupied' || wc.status === 'waiting' || (custName !== '---');
    const statusClass = isOccupied ? 'waiting' : 'available';
    const statusBadge = isOccupied ? 'BOOKED' : 'AVAILABLE';
    const tok = wc.token_number && wc.token_number !== '---' ? wc.token_number : '';
    const timeDisplay = isOccupied && wc.time && wc.time !== '---' 
      ? `<div class="text-secondary" style="font-size:0.68rem;">${wc.time}</div>` 
      : '';

    return `
      <div class="waiting-chair-card ${statusClass}">
        <div class="d-flex justify-content-between align-items-center mb-1">
          <span class="fw-bold text-dark" style="font-size:0.82rem;">
            <i class="fa-solid fa-chair me-1 text-warning"></i> Waiting ${idx + 1}
          </span>
          <span class="badge ${isOccupied ? 'bg-warning text-dark' : 'bg-success text-white'}" style="font-size:0.62rem;">
            ${statusBadge}
          </span>
        </div>
        <div class="small text-truncate ${isOccupied ? 'fw-bold text-dark' : 'text-muted'}" style="font-size:0.76rem;">
          ${isOccupied ? custName : 'Ready'}
        </div>
        ${timeDisplay}
        ${tok ? `<div class="text-primary fw-bold" style="font-size:0.68rem;">Token: ${tok}</div>` : `<div class="text-muted" style="font-size:0.68rem;">Free Seat</div>`}
      </div>
    `;
  };

  const newHtml = `
    <div class="seat-section-heading mb-2">
      <span class="fw-bold small text-secondary text-uppercase"><i class="fa-solid fa-scissors me-1 text-primary"></i> Main Service Seats (2)</span>
    </div>
    ${renderMainSeatCard(seat1, 1)}
    ${renderMainSeatCard(seat2, 2)}

    <div class="seat-section-heading mt-3 mb-2 d-flex justify-content-between align-items-center">
      <span class="fw-bold small text-secondary text-uppercase"><i class="fa-solid fa-chair me-1 text-warning"></i> Waiting Chairs (4)</span>
      <span class="badge bg-light text-muted border" style="font-size:0.65rem;">Auto-Promotes to Main</span>
    </div>
    <div class="waiting-chairs-grid">
      ${waitChairs.map((wc, i) => renderWaitChairCard(wc, i)).join('')}
    </div>
  `;

  if (container.dataset.renderedHtml !== newHtml) {
    container.dataset.renderedHtml = newHtml;
    container.innerHTML = newHtml;
  }
}
window.renderSeats = renderSeats;
window.fetchSeats = fetchChairs;

// Complete seat service handler
async function handleCompleteSeat(chairId) {
  if (!confirm(`Complete service for Seat ${chairId}? This will free the seat and automatically rotate waiting chairs & promote from queue.`)) {
    return;
  }
  try {
    const adminToken = localStorage.getItem('barber_admin_token');
    const custToken = localStorage.getItem('barber_token');
    const token = adminToken || custToken;
    const headers = token ? { 'Authorization': `Bearer ${token}` } : {};

    const url = `/api/chairs/${chairId}/complete-service` + (selectedDate ? `?date=${encodeURIComponent(selectedDate)}` : '');
    const res = await fetch(url, {
      method: 'POST',
      headers: headers
    });
    const data = await res.json();
    if (!res.ok) {
      throw new Error(data.message || 'Failed to complete service on seat.');
    }
    showToast(data.message || 'Seat service completed and waiting pipeline rotated!', 'success');
    fetchChairs(selectedDate);
    fetchTimeSlots(selectedDate);
    fetchWaitingQueue(selectedDate);
    if (typeof fetchUserSession === 'function') fetchUserSession();
  } catch (err) {
    console.error('Error completing seat service:', err);
    showToast(err.message, 'danger');
  }
}
window.handleCompleteSeat = handleCompleteSeat;

// 2. CENTER: Fetch & Render Available & Booked Time Slots with Dynamic 2-Seat Capacity
async function fetchTimeSlots(date) {
  if (!date) date = selectedDate;
  const listEl = document.getElementById('time-slots-list');
  if (!listEl) return;

  let loadedSlots = null;
  let errorMsg = null;

  try {
    const token = localStorage.getItem('barber_token');
    const headers = token ? { 'Authorization': `Bearer ${token}` } : {};

    const res = await fetch(`/api/appointments/slots/detailed?date=${encodeURIComponent(date)}`, { headers });
    let data = null;
    try {
      data = await res.json();
    } catch (parseErr) {}

    if (res.ok && data && data.data) {
      loadedSlots = data.data;
    } else {
      errorMsg = (data && data.message) ? data.message : 'No slots found for this date.';
    }
  } catch (err) {
    console.error('Error fetching time slots:', err);
    errorMsg = err.message || 'Network connection issue';
  }

  if (loadedSlots) {
    currentSlots = loadedSlots;
    renderTimeSlots();
  } else {
    // Only show error placeholder if no slots are currently displayed
    if (!currentSlots || currentSlots.length === 0) {
      const errHtml = `
        <div class="text-center py-4 text-danger small bg-light rounded border p-3">
          <div class="fw-semibold mb-1"><i class="fa-solid fa-triangle-exclamation me-1"></i> Failed to load time slots.</div>
          <div class="text-muted mb-2" style="font-size:0.75rem;">${errorMsg || 'Server is connecting.'}</div>
          <button type="button" class="btn btn-sm btn-outline-danger px-3" onclick="fetchTimeSlots()" style="font-size:0.75rem;">
            <i class="fa-solid fa-rotate-right me-1"></i> Retry
          </button>
        </div>`;
      if (listEl.dataset.renderedHtml !== errHtml) {
        listEl.dataset.renderedHtml = errHtml;
        listEl.innerHTML = errHtml;
      }
    }
  }
}
window.fetchTimeSlots = fetchTimeSlots;

function renderTimeSlots() {
  const listEl = document.getElementById('time-slots-list');
  if (!listEl) return;

  if (!currentSlots || currentSlots.length === 0) {
    const emptyHtml = `
      <div class="text-center py-5 text-muted small bg-light rounded border p-4">
        <i class="fa-regular fa-clock fs-2 text-secondary mb-2 d-block"></i>
        <div class="fw-bold text-dark">No more slots for today</div>
        <div class="text-muted mt-1" style="font-size:0.75rem;">All daytime service windows have elapsed. Select <strong>Tomorrow</strong> for upcoming slots.</div>
      </div>
    `;
    if (listEl.dataset.renderedHtml !== emptyHtml) {
      listEl.dataset.renderedHtml = emptyHtml;
      listEl.innerHTML = emptyHtml;
    }
    return;
  }

  const newHtml = currentSlots.map(s => {
    const assignedCount = s.assignedCount != null ? s.assignedCount : 0;
    const isFull = s.isFull || assignedCount >= 6;

    let capacityLabel = '';
    let statusPill = '';
    let actionBtn = '';

    if (assignedCount === 0) {
      capacityLabel = `0 / 2 Main Seats Filled`;
      statusPill = `<span class="slot-badge-pill available-2">MAIN SEAT AVAILABLE</span>`;
      actionBtn = `
        <button type="button" class="btn btn-sm btn-primary fw-bold py-1 px-3" onclick="openSlotBookingModal('${s.timeSlot}')" style="font-size:0.75rem;">
          Book Seat
        </button>
      `;
    } else if (assignedCount === 1) {
      capacityLabel = `1 / 2 Main Seats Filled`;
      statusPill = `<span class="slot-badge-pill available-1">1 MAIN SEAT AVAILABLE</span>`;
      actionBtn = `
        <button type="button" class="btn btn-sm btn-primary fw-bold py-1 px-3" onclick="openSlotBookingModal('${s.timeSlot}')" style="font-size:0.75rem;">
          Book Seat
        </button>
      `;
    } else if (assignedCount < 6) {
      const waitFilled = assignedCount - 2;
      const waitRemaining = 4 - waitFilled;
      capacityLabel = `2/2 Main Full • ${waitFilled}/4 Waiting Seats Filled`;
      statusPill = `<span class="slot-badge-pill text-dark bg-warning" style="background:#fef3c7;border:1px solid #fcd34d;">${waitRemaining} WAITING SEATS OPEN</span>`;
      actionBtn = `
        <button type="button" class="btn btn-sm btn-warning text-dark fw-bold py-1 px-2.5" onclick="openSlotBookingModal('${s.timeSlot}')" style="font-size:0.75rem;" title="2 Main Seats Full: Book into Waiting Seat">
          Book (Waiting Seat)
        </button>
      `;
    } else {
      capacityLabel = `All 6 Seats Full (2 Main + 4 Waiting)`;
      statusPill = `<span class="slot-badge-pill full">SEATS FULL</span>`;
      actionBtn = `
        <div class="d-flex gap-1 align-items-center">
          <button type="button" class="btn btn-sm btn-secondary fw-bold py-1 px-2.5" disabled style="font-size:0.72rem;" title="6 / 6 Seats Filled">
            Seats Full
          </button>
          <button type="button" class="btn btn-sm btn-outline-primary fw-bold py-1 px-2" onclick="openQueueModal('${s.timeSlot}')" style="font-size:0.72rem;" title="Join FIFO Waiting List">
            Join Waiting List
          </button>
        </div>
      `;
    }

    if (s.isCurrentCustomer) {
      const label = s.isCurrentWaitingList ? 'On Waiting List' : 'Your Booking';
      actionBtn = `
        <button type="button" class="btn btn-sm btn-outline-success fw-bold py-1 px-2.5" disabled style="font-size:0.75rem;">
          ${label}
        </button>
      `;
    }

    return `
      <div class="slot-capacity-card ${isFull ? 'full' : ''}">
        <div>
          <div class="slot-time-title">${s.timeSlot}</div>
          <div class="d-flex align-items-center gap-2 mt-1">
            <span class="text-secondary fw-semibold small" style="font-size:0.78rem;">${capacityLabel}</span>
            ${statusPill}
          </div>
        </div>
        <div>
          ${actionBtn}
        </div>
      </div>
    `;
  }).join('');

  if (listEl.dataset.renderedHtml !== newHtml) {
    listEl.dataset.renderedHtml = newHtml;
    listEl.innerHTML = newHtml;
  }
}

// 3. RIGHT: Fetch & Render FIFO Waiting Queue
async function fetchWaitingQueue(date) {
  try {
    const targetDate = date || selectedDate;
    const url = targetDate ? `/api/queue/waiting?date=${targetDate}` : '/api/queue/waiting';
    const res = await fetch(url);
    const data = await res.json();
    if (res.ok && data.data) {
      currentWaitingQueue = data.data;
      renderWaitingQueue();
    }
  } catch (err) {
    console.error('Error fetching waiting queue:', err);
  }
}
window.fetchWaitingQueue = fetchWaitingQueue;
window.fetchWaitingList = fetchWaitingQueue;

function renderWaitingQueue() {
  const countEl = document.getElementById('waiting-queue-count');
  const listEl = document.getElementById('waiting-queue-list');
  // Count total entries in waiting queue
  const count = currentWaitingQueue.length;
  if (countEl) countEl.textContent = count;
  if (!listEl) return;

  const newHtml = (currentWaitingQueue.length === 0)
    ? `
      <div class="text-center py-4 text-muted small bg-light rounded border p-3">
        <div class="fw-bold text-dark fs-6 mb-1">No customers waiting</div>
        <div class="text-muted" style="font-size:0.75rem;">New bookings receive direct seat allocation (Main Seats or Waiting Chairs).</div>
      </div>
    `
    : currentWaitingQueue.map((q, idx) => {
        const custName = q.customer_name || (q.customer ? q.customer.name : 'Customer');
        const tokNum = q.token_number || ('Q-' + (idx + 1));
        const isSeatedWaiting = q.waiting_type === 'waiting_chair' || q.is_seated_waiting;
        const badgeLabel = isSeatedWaiting 
          ? (q.chair_name || 'Waiting Chair') 
          : `Waiting List #${q.virtual_position || (idx + 1)}`;
        const badgeClass = isSeatedWaiting ? 'bg-warning text-dark' : 'bg-primary text-white';

        return `
          <div class="queue-list-entry">
            <div class="d-flex align-items-center min-w-0">
              <div class="queue-rank-badge">${idx + 1}</div>
              <div class="text-truncate">
                <div class="fw-bold text-dark text-truncate" style="font-size:0.84rem;">${idx + 1}. ${custName}</div>
                <div class="d-flex align-items-center gap-2 text-muted" style="font-size:0.72rem;">
                  <span>Token: <strong>${tokNum}</strong></span>
                  ${q.estimated_time && q.estimated_time !== '---' && q.estimated_time !== 'Next Available' ? `<span class="text-primary fw-semibold"><i class="fa-regular fa-clock me-1"></i>${q.estimated_time}</span>` : ''}
                </div>
              </div>
            </div>
            <span class="badge ${badgeClass}" style="font-size:0.65rem;">${badgeLabel}</span>
          </div>
        `;
      }).join('');

  if (listEl.dataset.renderedHtml !== newHtml) {
    listEl.dataset.renderedHtml = newHtml;
    listEl.innerHTML = newHtml;
  }
}

// Date Filter Helpers
function selectDateFilter(type) {
  document.querySelectorAll('.slot-date-btn').forEach(b => b.classList.remove('active'));
  const btn = document.getElementById(`btn-date-${type}`);
  if (btn) btn.classList.add('active');

  const today = new Date();
  let target = new Date(today);
  if (type === 'tomorrow') {
    target.setDate(today.getDate() + 1);
  } else if (type === 'dayafter') {
    target.setDate(today.getDate() + 2);
  }

  selectedDate = formatLocalDate(target);
  const picker = document.getElementById('slot-picker-date');
  if (picker) picker.value = selectedDate;

  scheduleRefresh(0);
}
window.selectDateFilter = selectDateFilter;

function onDateChanged(val) {
  if (!val) return;
  selectedDate = val;
  document.querySelectorAll('.slot-date-btn').forEach(b => b.classList.remove('active'));
  scheduleRefresh(0);
}
window.onDateChanged = onDateChanged;

function formatMinutesToTime(totalMin) {
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  const dh = h % 12 === 0 ? 12 : h % 12;
  const period = h < 12 ? 'AM' : 'PM';
  return `${dh}:${m < 10 ? '0' : ''}${m} ${period}`;
}

function parseTimeToMinutes(str) {
  if (!str) return -1;
  let clean = str.trim().toUpperCase();
  if (clean.includes('-')) clean = clean.split('-')[0].trim();
  const isPm = clean.includes('PM');
  clean = clean.replace('AM', '').replace('PM', '').trim();
  const parts = clean.split(':');
  let h = parseInt(parts[0], 10);
  const m = parts.length > 1 ? parseInt(parts[1], 10) : 0;
  if (isPm && h !== 12) h += 12;
  else if (!isPm && h === 12) h = 0;
  return h * 60 + m;
}

// Open Direct Slot Booking Modal
function openSlotBookingModal(timeSlot) {
  document.getElementById('slot-modal-time').value = timeSlot;
  document.getElementById('slot-modal-date').value = selectedDate;
  document.getElementById('slot-modal-display-time').textContent = timeSlot;

  const todayStr = new Date().toISOString().split('T')[0];
  let dateBadge = selectedDate === todayStr ? 'Today' : selectedDate;
  document.getElementById('slot-modal-display-date').textContent = dateBadge;

  // Update current wallet balance in modal
  const slotBalEl = document.getElementById('slot-modal-wallet-balance');
  if (slotBalEl) slotBalEl.textContent = `₹${currentWalletBalance.toFixed(2)}`;
  if (typeof fetchWalletBalance === 'function') fetchWalletBalance();

  // Populate services dropdown
  const srvSelect = document.getElementById('slot-service-select');
  if (srvSelect && services.length > 0) {
    srvSelect.innerHTML = services.map(s => `
      <option value="${s.id}" data-price="${s.price}" data-duration="${s.duration_minutes}">
        ${s.name} — \u20B9${parseFloat(s.price).toFixed(2)} (${s.duration_minutes}m)
      </option>
    `).join('');
    updateSlotModalPrice();
  }

  const modal = new bootstrap.Modal(document.getElementById('slotBookingModal'));
  modal.show();
}
window.openSlotBookingModal = openSlotBookingModal;

function updateSlotModalPrice() {
  const srvSelect = document.getElementById('slot-service-select');
  const opt = srvSelect ? srvSelect.options[srvSelect.selectedIndex] : null;
  if (opt) {
    const price = opt.getAttribute('data-price') || '120.00';
    const dur = parseInt(opt.getAttribute('data-duration') || '30', 10);
    const priceEl = document.getElementById('slot-modal-price');
    if (priceEl) priceEl.textContent = `\u20B9${parseFloat(price).toFixed(2)}`;

    const slotTimeInput = document.getElementById('slot-modal-time');
    const displayTimeEl = document.getElementById('slot-modal-display-time');
    if (slotTimeInput && displayTimeEl) {
      const slotTime = slotTimeInput.value;
      const startMin = parseTimeToMinutes(slotTime);
      if (startMin >= 0) {
        const endMin = startMin + dur;
        const rangeStr = `${formatMinutesToTime(startMin)} - ${formatMinutesToTime(endMin)}`;
        displayTimeEl.innerHTML = `${rangeStr} <span class="badge bg-primary-subtle text-primary border border-primary-subtle ms-1" style="font-size:0.7rem;">${dur}m duration</span>`;
      }
    }
  }
}
window.updateSlotModalPrice = updateSlotModalPrice;

function selectSlotPayment(btn) {
  document.querySelectorAll('.slot-pay-btn').forEach(b => {
    b.classList.remove('active', 'btn-primary');
    b.classList.add('btn-outline-secondary');
  });
  btn.classList.add('active', 'btn-primary');
  btn.classList.remove('btn-outline-secondary');
  document.getElementById('slot-payment-method').value = btn.getAttribute('data-pay');
}
window.selectSlotPayment = selectSlotPayment;

// Submit Slot Booking
async function handleConfirmSlotBooking(event) {
  event.preventDefault();
  const token = localStorage.getItem('barber_token');
  if (!token) {
    showToast('Please log in to book a time slot.', 'danger');
    return;
  }

  const timeSlot = document.getElementById('slot-modal-time').value;
  const appointmentDate = document.getElementById('slot-modal-date').value;
  const srvSelect = document.getElementById('slot-service-select');
  const serviceId = srvSelect ? srvSelect.value : 1;
  const opt = srvSelect ? srvSelect.options[srvSelect.selectedIndex] : null;
  const price = opt ? parseFloat(opt.getAttribute('data-price') || '0') : 0;
  const notesEl = document.getElementById('slot-notes');
  const notes = notesEl ? notesEl.value : '';

  // Preserve booking details for retry after recharge
  pendingBookingData = {
    serviceId: serviceId,
    timeSlot: timeSlot,
    appointmentDate: appointmentDate,
    notes: notes,
    price: price
  };

  const submitBtn = document.getElementById('btn-submit-slot-booking');
  submitBtn.disabled = true;
  submitBtn.innerHTML = `<span class="spinner-border spinner-border-sm me-2"></span>Booking slot…`;

  try {
    const res = await fetch('/api/appointments/book-slot', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify({
        time_slot: timeSlot,
        appointment_date: appointmentDate,
        service_id: serviceId,
        notes: notes,
        payment_method: 'wallet'
      })
    });

    const data = await res.json();

    if (res.status === 402 || data.insufficient_balance || (data.message && data.message.includes('Insufficient'))) {
      // Close slot modal
      const slotModalEl = document.getElementById('slotBookingModal');
      const slotModal = slotModalEl ? bootstrap.Modal.getInstance(slotModalEl) : null;
      if (slotModal) slotModal.hide();

      // Show insufficient balance modal
      const reqAmt = data.required_amount != null ? parseFloat(data.required_amount) : price;
      const curBal = data.current_balance != null ? parseFloat(data.current_balance) : currentWalletBalance;

      const reqEl = document.getElementById('insufficient-required-amt');
      const curEl = document.getElementById('insufficient-current-amt');
      if (reqEl) reqEl.textContent = `₹${reqAmt.toFixed(2)}`;
      if (curEl) curEl.textContent = `₹${curBal.toFixed(2)}`;

      const insuffModalEl = document.getElementById('insufficientWalletModal');
      if (insuffModalEl) {
        const insuffModal = bootstrap.Modal.getInstance(insuffModalEl) || new bootstrap.Modal(insuffModalEl);
        insuffModal.show();
      }
      return;
    }

    if (!res.ok) {
      throw new Error(data.message || 'Double booking collision: This slot was just booked.');
    }

    const modal = bootstrap.Modal.getInstance(document.getElementById('slotBookingModal'));
    if (modal) modal.hide();

    // Clear pending booking data on success
    pendingBookingData = null;

    // Immediately update balance without reload
    if (data.wallet_balance !== undefined) {
      currentWalletBalance = parseFloat(data.wallet_balance);
      updateAllWalletDisplays(currentWalletBalance);
    } else {
      fetchWalletBalance();
    }

    const assignedSeat = data.data?.chair ? `Seat ${data.data.chair.chair_number}` : 'Waiting Queue';
    showToast(`🎉 Booked ${timeSlot}! Assigned to ${assignedSeat}. ₹${price.toFixed(2)} paid from wallet.`, 'success');

    // Refresh all 3 panels immediately
    fetchChairs();
    fetchTimeSlots(selectedDate);
    fetchWaitingQueue();
    fetchUserSession();

  } catch (err) {
    showToast(err.message, 'danger');
    fetchTimeSlots(selectedDate);
  } finally {
    submitBtn.disabled = false;
    submitBtn.innerHTML = `Confirm &amp; Book Slot`;
  }
}
window.handleConfirmSlotBooking = handleConfirmSlotBooking;


let pendingQueueTimeSlot = null;

// Open Join Waiting Queue Modal
async function openQueueModal(timeSlot) {
  pendingQueueTimeSlot = (typeof timeSlot === 'string' && timeSlot.trim()) ? timeSlot.trim() : null;

  if (!services || services.length === 0) {
    try {
      await fetchServices();
    } catch (e) {
      console.warn('Could not fetch services in openQueueModal:', e);
    }
  }

  const slotIndicator = document.getElementById('queue-slot-indicator');
  if (slotIndicator) {
    if (pendingQueueTimeSlot) {
      slotIndicator.innerHTML = `Joining Waiting List for: <strong>${pendingQueueTimeSlot}</strong> (${selectedDate})`;
      slotIndicator.className = 'alert alert-info py-2 px-3 small mb-3';
      slotIndicator.style.display = 'block';
    } else {
      slotIndicator.innerHTML = `Joining Waiting List for: <strong>${selectedDate}</strong> (Next Available)`;
      slotIndicator.className = 'alert alert-info py-2 px-3 small mb-3';
      slotIndicator.style.display = 'block';
    }
  }

  const selects = [
    document.getElementById('queue-service-select'),
    document.getElementById('queue-service')
  ].filter(Boolean);

  selects.forEach(sel => {
    if (services && services.length > 0) {
      sel.innerHTML = services.map(s => `
        <option value="${s.id}" data-price="${s.price}" data-duration="${s.duration_minutes}">
          ${s.name} — \u20B9${parseFloat(s.price).toFixed(2)} (${s.duration_minutes}m)
        </option>
      `).join('');
    } else if (sel.options.length === 0) {
      sel.innerHTML = `
        <option value="1" data-price="120.00" data-duration="30">Haircut — \u20B9120.00 (30m)</option>
        <option value="2" data-price="80.00" data-duration="20">Shaving — \u20B980.00 (20m)</option>
        <option value="3" data-price="200.00" data-duration="45">Both (Haircut &amp; Shaving) — \u20B9200.00 (45m)</option>
        <option value="4" data-price="300.00" data-duration="30">Facial — \u20B9300.00 (30m)</option>
      `;
    }
  });

  const modalEl = document.getElementById('queueModal');
  if (modalEl) {
    const modal = bootstrap.Modal.getInstance(modalEl) || new bootstrap.Modal(modalEl);
    modal.show();
  }
}
window.openQueueModal = openQueueModal;

// Submit Join Waiting Queue
async function handleJoinQueueSubmit(event) {
  event.preventDefault();
  const token = localStorage.getItem('barber_token');
  if (!token) {
    showToast('Please log in to join the waiting queue.', 'danger');
    return;
  }

  const srvSelect = document.getElementById('queue-service-select');
  const serviceId = srvSelect ? srvSelect.value : 1;
  const btn = document.getElementById('join-queue-submit-btn');
  btn.disabled = true;
  btn.innerHTML = `<span class="spinner-border spinner-border-sm me-2"></span>Joining queue…`;

  try {
    const res = await fetch('/api/queue/join', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify({
        service_id: serviceId,
        time_slot: pendingQueueTimeSlot,
        appointment_date: selectedDate
      })
    });

    const data = await res.json();
    if (!res.ok) {
      throw new Error(data.message || 'Failed to join waiting queue.');
    }

    const modal = bootstrap.Modal.getInstance(document.getElementById('queueModal'));
    if (modal) modal.hide();

    const tok = data.data?.token_number || 'Token Assigned';
    showToast(`🎟️ Successfully joined waiting queue! Token: ${tok}`, 'success');

    fetchWaitingQueue(selectedDate);
    fetchChairs(selectedDate);
    fetchTimeSlots(selectedDate);
    fetchUserSession();
  } catch (err) {
    showToast(err.message, 'danger');
  } finally {
    btn.disabled = false;
    btn.innerHTML = `Get Digital Token &amp; Join Queue`;
  }
}
window.handleJoinQueueSubmit = handleJoinQueueSubmit;



// Fetch current logged in customer's token & sessions
async function fetchUserSession() {
  const token = localStorage.getItem('barber_token');
  if (!token) return;

  fetchWalletBalance();

  try {
    // 1. Check direct appointments first
    const apptsRes = await fetch('/api/appointments/my', {
      headers: { 'Authorization': `Bearer ${token}` }
    });
    const apptsData = await apptsRes.json();

    let activeAppt = null;
    if (apptsRes.ok && apptsData.data) {
      // Find today's uncompleted/active appointments
      const todayStr = new Date().toISOString().split('T')[0];
      activeAppt = apptsData.data.find(a => 
        ['pending', 'confirmed', 'in_progress'].includes(a.status) && a.appointment_date === todayStr
      );
    }

    if (activeAppt) {
      activeSession = { type: 'appointment', data: activeAppt };
      renderSessionPanel();
      return;
    }

    // 2. Check Queue if no appointment
    const queueRes = await fetch('/api/queue/my-status', {
      headers: { 'Authorization': `Bearer ${token}` }
    });
    const queueData = await queueRes.json();

    if (queueRes.ok && queueData.data) {
      const q = queueData.data;
      const st = (q.status || '').toLowerCase();
      // Only active queue entries belong in an active session
      if (['waiting', 'called', 'serving', 'seated'].includes(st)) {
        activeSession = { type: 'queue', data: q };
      } else {
        activeSession = null;
      }
      renderSessionPanel();
      return;
    }

    // No active session
    activeSession = null;
    renderSessionPanel();

  } catch (error) {
    console.error('Error fetching user session:', error);
  }
}

function dismissSessionAndGetNext(id) {
  if (id) sessionStorage.setItem('dismissed_queue_' + id, 'true');
  if (window._mySessionDismissTimer) {
    clearTimeout(window._mySessionDismissTimer);
    window._mySessionDismissTimer = null;
  }
  activeSession = null;
  renderSessionPanel();
  if (typeof openQueueModal === 'function') openQueueModal();
}
window.dismissSessionAndGetNext = dismissSessionAndGetNext;

// Render User's Active token / position details on the Right Panel
function renderSessionPanel() {
  const panel = document.getElementById('session-panel');
  if (!panel) return;

  if (!activeSession) {
    panel.innerHTML = `
      <div class="py-5 text-muted">
        <i class="fa-regular fa-calendar-check display-3 text-secondary mb-3"></i>
        <p class="fw-bold text-dark">No Active Bookings</p>
        <p class="small text-muted">Select an available station chair to book your slot, or join a waiting lounge.</p>
      </div>
    `;
    return;
  }

  if (activeSession.type === 'appointment') {
    const appt = activeSession.data;
    panel.innerHTML = `
      <div class="token-display py-4 shadow-sm mb-3">
        <span class="badge mb-2" style="background:rgba(37,99,235,0.12);color:#2563eb;border:1px solid rgba(37,99,235,0.3)">Station Appointment</span>
        <div class="token-number" style="color:var(--be-primary)">${appt.token_number}</div>
        <div class="token-label">Confirmed Token Code</div>
      </div>
      
      <div class="text-start p-3 rounded mb-3 small" style="background:#f8fafc;border:1px solid var(--be-border)">
        <div class="mb-1"><strong>Status:</strong> <span class="badge" style="background:rgba(5,150,105,0.12);color:#059669;border:1px solid rgba(5,150,105,0.3)">${appt.status.toUpperCase()}</span></div>
        <div class="mb-1"><strong>Chair:</strong> Main Service Chair ${appt.chair_id || '1'}</div>
        <div class="mb-1"><strong>Service:</strong> ${appt.service?.name || 'Grooming service'}</div>
        <div class="mb-1"><strong>Date:</strong> ${appt.appointment_date}</div>
        <div><strong>Time Slot:</strong> ${appt.time_slot}</div>
      </div>

      <button class="btn btn-outline-secondary w-100 fw-bold btn-sm" onclick="cancelActiveAppointment(${appt.id})">
        <i class="fa-solid fa-calendar-xmark me-2"></i>Cancel Appointment
      </button>
    `;
  } else if (activeSession.type === 'queue') {
    const q = activeSession.data;
    const st = (q.status || '').toLowerCase();

    // If completed / served, show completed card with "Get Next Token" button and auto-clear timer
    if (st === 'completed' || st === 'served') {
      panel.innerHTML = `
        <div class="token-display py-4 shadow-sm mb-3">
          <span class="badge mb-2" style="background:rgba(5,150,105,0.12);color:#059669;border:1px solid rgba(5,150,105,0.3)">Service Completed</span>
          <div class="token-number" style="color:#059669">${q.token_number}</div>
          <div class="token-label text-success fw-bold">✓ Successfully Served</div>
        </div>

        <div class="text-start p-3 rounded mb-3 small" style="background:#f8fafc;border:1px solid var(--be-border)">
          <div><strong>Status:</strong> <span class="badge bg-success text-white">SERVED</span></div>
          <div class="mt-1"><strong>Service:</strong> ${q.service?.name || 'Grooming service'}</div>
        </div>

        <button class="btn btn-primary w-100 fw-bold btn-sm shadow-sm" onclick="dismissSessionAndGetNext(${q.id})">
          <i class="fa-solid fa-ticket me-2"></i>Get Next Token
        </button>
      `;

      if (!window._mySessionDismissTimer) {
        window._mySessionDismissTimer = setTimeout(() => {
          window._mySessionDismissTimer = null;
          dismissSessionAndGetNext(q.id);
        }, 4000);
      }
      return;
    }

    panel.innerHTML = `
      <div class="token-display py-4 shadow-sm mb-3">
        <span class="badge mb-2" style="background:rgba(37,99,235,0.12);color:#2563eb;border:1px solid rgba(37,99,235,0.3)">Waiting Lounge Token</span>
        <div class="token-number" style="color:var(--be-primary)">${q.token_number}</div>
        <div class="token-label">Digital Queue Token</div>
      </div>

      <div class="row g-2 mb-3 text-center">
        <div class="col-6">
          <div class="p-2 rounded" style="background:#f8fafc;border:1px solid var(--be-border)">
            <div class="fw-bold text-dark fs-4">${q.position}</div>
            <small class="text-muted">Queue Position</small>
          </div>
        </div>
        <div class="col-6">
          <div class="p-2 rounded" style="background:#f8fafc;border:1px solid var(--be-border)">
            <div class="fw-bold text-primary fs-4">${q.estimated_wait_minutes}m</div>
            <small class="text-muted">Estimated Wait</small>
          </div>
        </div>
      </div>

      <div class="text-start p-3 rounded mb-3 small" style="background:#f8fafc;border:1px solid var(--be-border)">
        <div><strong>Status:</strong> <span class="badge" style="background:rgba(37,99,235,0.12);color:#2563eb;border:1px solid rgba(37,99,235,0.3)">${q.status.toUpperCase()}</span></div>
        <div class="mt-1"><strong>Service:</strong> ${q.service?.name || 'Grooming service'}</div>
      </div>

      <button class="btn btn-outline-secondary w-100 fw-bold btn-sm" onclick="cancelQueueEntry(${q.id})">
        <i class="fa-solid fa-circle-xmark me-2"></i>Leave Waiting Queue
      </button>
    `;
  }
}

// User selects a chair to book (triggers reservation)
async function selectChairToBook(chairId, chairNum) {
  const token = localStorage.getItem('barber_token');
  if (!token) return;

  try {
    const res = await fetch(`/api/chairs/${chairId}/reserve`, {
      method: 'PUT',
      headers: { 'Authorization': `Bearer ${token}` }
    });

    const data = await res.json();

    if (!res.ok) {
      throw new Error(data.message || 'Failed to hold chair reservation.');
    }

    selectedChair = data.data;

    // Show modal and start 5 minute countdown
    document.getElementById('booking-chair-id').value = selectedChair.id;
    document.getElementById('booking-chair-name').value = `Main Service Chair ${selectedChair.chair_number || chairNum}`;
    const titleEl = document.getElementById('booking-chair-name-title');
    if (titleEl) titleEl.textContent = `Main Service Chair ${selectedChair.chair_number || chairNum}`;
    
    // Set default date as today
    document.getElementById('booking-date').value = new Date().toISOString().split('T')[0];
    handleDateChange(); // Load time slots specifically for this selected chair

    // Start timer
    startReservationTimer();

    const myModal = new bootstrap.Modal(document.getElementById('bookingModal'));
    myModal.show();

    // Listen to modal close to release chair if not submitted
    document.getElementById('bookingModal').addEventListener('hidden.bs.modal', releaseChairOnClose);

  } catch (error) {
    showToast(error.message, 'danger');
  }
}

// Release reserved chair if modal was closed without confirming
async function releaseChairOnClose() {
  clearInterval(reservationTimer);
  if (selectedChair) {
    const token = localStorage.getItem('barber_token');
    try {
      // Releasing chair sets status back to 'available'
      await fetch(`/api/chairs/${selectedChair.id}/release`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`
        },
        body: JSON.stringify({ status: 'available' })
      });
      selectedChair = null;
      fetchChairs();
    } catch (e) {
      console.error(e);
    }
  }
}

// Start timer function (with SVG countdown ring)
function startReservationTimer() {
  clearInterval(reservationTimer);
  reservationSeconds = 300;
  const TOTAL = 300;
  const CIRCUMFERENCE = 213.6; // 2π × 34

  const display = document.getElementById('timer-display');
  const circle = document.getElementById('countdown-circle');

  reservationTimer = setInterval(() => {
    const minutes = Math.floor(reservationSeconds / 60);
    let seconds = reservationSeconds % 60;
    seconds = seconds < 10 ? '0' + seconds : seconds;

    if (display) display.textContent = `${minutes}:${seconds}`;

    // Update SVG ring
    if (circle) {
      const progress = reservationSeconds / TOTAL;
      const offset = CIRCUMFERENCE * (1 - progress);
      circle.style.strokeDashoffset = offset;
      // Color shifts smoothly in sapphire/electric blue palette
      if (progress > 0.5) circle.style.stroke = '#38bdf8';
      else if (progress > 0.2) circle.style.stroke = '#3b82f6';
      else circle.style.stroke = '#1d4ed8';
    }

    if (--reservationSeconds < 0) {
      clearInterval(reservationTimer);
      showToast('⏰ Chair hold expired! The seat has been released.', 'warning');
      const modal = bootstrap.Modal.getInstance(document.getElementById('bookingModal'));
      if (modal) modal.hide();
    }
  }, 1000);
}

// Calculate duration & price live summary inside booking form
function calculateBookingSummary() {
  const select = document.getElementById('booking-service');
  const selectedOption = select.options[select.selectedIndex];
  if (!selectedOption) return;

  const duration = selectedOption.getAttribute('data-duration');
  const price = selectedOption.getAttribute('data-price');
  const name = selectedOption.textContent.split('(')[0].trim();

  const setEl = (id, val) => { const el = document.getElementById(id); if (el) el.textContent = val; };
  setEl('summary-duration', `${duration} min`);
  setEl('summary-price', `\u20B9${parseFloat(price).toFixed(2)}`);
  setEl('summary-service-name', name);

  // Update datetime summary
  const dateVal = document.getElementById('booking-date')?.value;
  const timeVal = document.getElementById('booking-time')?.value;
  if (dateVal && timeVal) setEl('summary-datetime', `${dateVal} ${timeVal}`);

  // Reload slots based on service duration
  handleDateChange();
}

// Load dynamic time-slot chips when date changes (strictly for selected chair)
async function handleDateChange() {
  const dateEl = document.getElementById('booking-date');
  const slotsGrid = document.getElementById('time-slots-grid');
  const hiddenInput = document.getElementById('booking-time');
  const dayLabel = document.getElementById('slot-day-label');
  const chairId = document.getElementById('booking-chair-id')?.value;

  if (!slotsGrid) return;

  // User requirement: time slots are strictly chair-driven!
  if (!chairId) {
    slotsGrid.innerHTML = '<span class="text-warning small"><i class="fa-solid fa-hand me-1"></i>Please select Main Service Chair 1 or Chair 2 first to view available time slots.</span>';
    return;
  }

  if (!dateEl || !dateEl.value) {
    slotsGrid.innerHTML = '<span class="text-muted small">Select a date to view available time slots for this chair…</span>';
    return;
  }
  const dateVal = dateEl.value;
  const slots = getSlotsByDate(dateVal);

  // Mark peak hours (11am-2pm)
  const PEAK_START = 11, PEAK_END = 14;

  // Fetch booked slots strictly for this selected chair and date
  let bookedSlots = [];
  try {
    const token = localStorage.getItem('barber_token');
    const res = await fetch(`/api/appointments/slots?chairId=${chairId}&date=${dateVal}`, {
      headers: { 'Authorization': `Bearer ${token}` }
    });
    if (res.ok) {
      const d = await res.json();
      bookedSlots = d.data?.bookedSlots || [];
    }
  } catch (e) {
    console.warn('Could not fetch booked slots:', e);
  }

  // Day label
  const dayNames = ['Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday'];
  const d = new Date(dateVal + 'T00:00:00');
  if (dayLabel) dayLabel.textContent = dayNames[d.getDay()];

  // Get selected service duration to verify that consecutive slots are available
  const serviceSelect = document.getElementById('booking-service');
  const selectedOpt = serviceSelect?.options[serviceSelect.selectedIndex];
  const reqDuration = parseInt(selectedOpt?.getAttribute('data-duration') || '30', 10);

  function timeStrToMin(timeStr) {
    if (!timeStr) return -1;
    const isPM = /PM/i.test(timeStr);
    const clean = timeStr.replace(/AM|PM/i, '').trim();
    const parts = clean.split(':');
    let h = parseInt(parts[0], 10);
    const m = parts.length > 1 ? parseInt(parts[1], 10) : 0;
    if (isPM && h !== 12) h += 12;
    else if (!isPM && h === 12) h = 0;
    return h * 60 + m;
  }

  const bookedMinList = bookedSlots.map(timeStrToMin).filter(m => m >= 0);

  // Build chip HTML
  slotsGrid.innerHTML = slots.map(s => {
    const slotMin = timeStrToMin(s);
    let isBooked = bookedSlots.includes(s);
    let reason = "Already booked on this chair";

    // If slot itself isn't booked, check if booking this service [slotMin, slotMin + reqDuration) overlaps with any booked 30-min slot [b, b + 30)
    if (!isBooked && reqDuration > 30) {
      const slotEndMin = slotMin + reqDuration;
      for (const b of bookedMinList) {
        if (Math.max(slotMin, b) < Math.min(slotEndMin, b + 30)) {
          isBooked = true;
          reason = `Conflicts with existing booking (needs ${reqDuration} min)`;
          break;
        }
      }
    }

    const hMatch = s.match(/^(\d+)/);
    const displayH = hMatch ? parseInt(hMatch[1]) : 0;
    const isPM = s.includes('PM');
    const hour24 = isPM && displayH !== 12 ? displayH + 12 : (!isPM && displayH === 12 ? 0 : displayH);
    const isPeak = hour24 >= PEAK_START && hour24 < PEAK_END;
    const cls = ['time-slot-chip', isBooked ? 'disabled' : '', isPeak && !isBooked ? 'peak' : ''].filter(Boolean).join(' ');
    return `<div class="${cls}" data-val="${s}"
      ${isBooked ? `title="${reason}"` : `onclick="selectTimeSlot(this, '${s}')"`}>${s}</div>`;
  }).join('');

  // Auto-select first available
  const firstAvail = slotsGrid.querySelector('.time-slot-chip:not(.disabled)');
  if (firstAvail) {
    selectTimeSlot(firstAvail, firstAvail.dataset.val);
  } else {
    if (hiddenInput) hiddenInput.value = '';
    const setEl = (id, v) => { const e = document.getElementById(id); if (e) e.textContent = v; };
    setEl('summary-datetime', `${dateVal} · No free slots on this chair`);
  }
}

function selectTimeSlot(el, val) {
  document.querySelectorAll('.time-slot-chip').forEach(c => c.classList.remove('selected'));
  el.classList.add('selected');
  const hiddenInput = document.getElementById('booking-time');
  if (hiddenInput) hiddenInput.value = val;

  // Update summary datetime
  const dateVal = document.getElementById('booking-date')?.value;
  const setEl = (id, v) => { const e = document.getElementById(id); if (e) e.textContent = v; };
  if (dateVal) setEl('summary-datetime', `${dateVal} · ${val}`);
}

function getSlotsByDate(dateStr) {
  const date = new Date(dateStr + 'T00:00:00');
  const day = date.getDay();
  let start = 9, end = 20;
  if (day === 0) { start = 10; end = 18; }
  else if (day === 6) { start = 8; end = 21; }

  const list = [];
  for (let h = start; h < end; h++) {
    for (let m = 0; m < 60; m += 30) {
      const displayHour = h % 12 || 12;
      const period = h < 12 ? 'AM' : 'PM';
      const displayMin = String(m).padStart(2, '0');
      list.push(`${displayHour}:${displayMin} ${period}`);
    }
  }
  return list;
}

// Confirm booking submit
async function confirmBooking(event) {
  event.preventDefault();
  const token = localStorage.getItem('barber_token');
  if (!token || !selectedChair) return;

  const chairId = document.getElementById('booking-chair-id').value;
  const serviceId = document.getElementById('booking-service').value;
  const dateVal = document.getElementById('booking-date').value;
  const timeSlot = document.getElementById('booking-time').value;
  const paymentMethod = document.getElementById('payment-method').value;
  const paymentKey = document.getElementById('payment-key').value;

  // Validate Hardcoded Passkey for Gateway
  if (paymentKey !== 'pass123') {
    showToast('❌ Payment Rejected! Invalid transaction key. Enter "pass123" to authorize pay.', 'danger');
    return;
  }

  try {
    const res = await fetch('/api/appointments', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify({
        chair_id: chairId,
        service_id: serviceId,
        appointment_date: dateVal,
        time_slot: timeSlot,
        payment_method: paymentMethod,
        notes: `Paid via ${paymentMethod.toUpperCase()}`
      })
    });

    const data = await res.json();

    if (res.status === 402 || data.insufficient_balance || (data.message && data.message.includes('Insufficient'))) {
      const modal = bootstrap.Modal.getInstance(document.getElementById('bookingModal'));
      if (modal) modal.hide();

      const reqEl = document.getElementById('insufficient-required-amt');
      const curEl = document.getElementById('insufficient-current-amt');
      if (reqEl) reqEl.textContent = data.required_amount != null ? `₹${parseFloat(data.required_amount).toFixed(2)}` : '₹0.00';
      if (curEl) curEl.textContent = data.current_balance != null ? `₹${parseFloat(data.current_balance).toFixed(2)}` : `₹${currentWalletBalance.toFixed(2)}`;

      const insuffModalEl = document.getElementById('insufficientWalletModal');
      if (insuffModalEl) {
        const insuffModal = bootstrap.Modal.getInstance(insuffModalEl) || new bootstrap.Modal(insuffModalEl);
        insuffModal.show();
      }
      return;
    }

    if (!res.ok) {
      throw new Error(data.message || 'Failed to confirm appointment.');
    }

    // Immediately update balance without reload
    if (data.wallet_balance !== undefined) {
      currentWalletBalance = parseFloat(data.wallet_balance);
      updateAllWalletDisplays(currentWalletBalance);
    } else {
      fetchWalletBalance();
    }

    showToast(`🎉 Booking Confirmed! \u20B9${parseFloat(data.data.service.price).toFixed(2)} has been paid from your wallet.`, 'success');

    // Clean timer listeners & close modal
    clearInterval(reservationTimer);
    selectedChair = null; // Clear so release listener doesn't trigger API

    const modal = bootstrap.Modal.getInstance(document.getElementById('bookingModal'));
    if (modal) modal.hide();

    // Refresh dashboard stats
    fetchChairs();
    fetchUserSession();

  } catch (error) {
    showToast(error.message, 'danger');
  }
}


// openQueueModalForChair kept for backward compat — now just opens the global modal
function openQueueModalForChair(chairId, chairNum) {
  openQueueModal();
}
window.openQueueModalForChair = openQueueModalForChair;

// Submit queue joining (token-based, no chair preference)
async function confirmJoinQueue(event) {
  event.preventDefault();
  const token = localStorage.getItem('barber_token');
  if (!token) return;

  const srvEl = document.getElementById('queue-service-select') || document.getElementById('queue-service');
  const serviceId = srvEl ? srvEl.value : null;
  if (!serviceId) { showToast('Please select a service.', 'warning'); return; }

  try {
    const res = await fetch('/api/queue/join', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify({ service_id: serviceId })
    });

    const data = await res.json();

    if (!res.ok) {
      throw new Error(data.message || 'Failed to join queue.');
    }

    showToast(`🎟️ Joined Queue! Your token is ${data.data?.token_number || ''}`, 'success');
    
    const modal = bootstrap.Modal.getInstance(document.getElementById('queueModal'));
    if (modal) modal.hide();

    fetchChairs();
    fetchUserSession();
    if (typeof window.fetchWaitingList === 'function') window.fetchWaitingList();

  } catch (error) {
    showToast(error.message, 'danger');
  }
}

// Cancel booking
async function cancelActiveAppointment(apptId) {
  if (!confirm('Are you sure you want to cancel this appointment? This will release your chair.')) return;
  const token = localStorage.getItem('barber_token');

  try {
    const res = await fetch(`/api/appointments/${apptId}/cancel`, {
      method: 'PUT',
      headers: { 'Authorization': `Bearer ${token}` }
    });

    if (res.ok) {
      showToast('Appointment cancelled successfully.', 'success');
      fetchChairs();
      fetchUserSession();
      if (typeof window.fetchWaitingList === 'function') window.fetchWaitingList();
    } else {
      const data = await res.json();
      throw new Error(data.message);
    }
  } catch (error) {
    showToast(error.message, 'danger');
  }
}

// Cancel queue
async function cancelQueueEntry(queueId) {
  if (activeSession && activeSession.type === 'queue') {
    const st = (activeSession.data?.status || '').toLowerCase();
    if (st === 'completed' || st === 'served' || st === 'cancelled') {
      sessionStorage.setItem('dismissed_queue_' + queueId, 'true');
      activeSession = null;
      renderSessionPanel();
      if (typeof window.fetchWaitingList === 'function') window.fetchWaitingList();
      return;
    }
  }

  if (!confirm('Are you sure you want to leave the waiting queue?')) return;
  const token = localStorage.getItem('barber_token');

  try {
    const res = await fetch(`/api/queue/${queueId}/cancel`, {
      method: 'PUT',
      headers: { 'Authorization': `Bearer ${token}` }
    });

    if (res.ok) {
      sessionStorage.setItem('dismissed_queue_' + queueId, 'true');
      showToast('Successfully left the waiting queue.', 'success');
      activeSession = null;
      fetchChairs();
      fetchUserSession();
      if (typeof window.fetchWaitingList === 'function') window.fetchWaitingList();
    } else {
      const data = await res.json();
      throw new Error(data.message);
    }
  } catch (error) {
    // If backend reports it is already completed, dismiss it gracefully
    if (error.message && (error.message.includes('completed') || error.message.includes('cancelled'))) {
      sessionStorage.setItem('dismissed_queue_' + queueId, 'true');
      activeSession = null;
      renderSessionPanel();
      if (typeof window.fetchWaitingList === 'function') window.fetchWaitingList();
    } else {
      showToast(error.message, 'danger');
    }
  }
}

// WebSocket indicator helpers
function setWsConnected(connected) {
  const indicators = [
    { dot: 'ws-dot', label: 'ws-label' },
    { dot: 'chair-ws-dot', label: 'chair-ws-label' }
  ];
  indicators.forEach(({ dot, label }) => {
    const dotEl = document.getElementById(dot);
    const lblEl = document.getElementById(label);
    if (dotEl) { dotEl.classList.toggle('connected', connected); dotEl.classList.toggle('disconnected', !connected); }
    if (lblEl) lblEl.textContent = connected ? 'Live' : 'Offline';
  });
}

// Socket listener configuration & controlled debounced refresh
let refreshDebounceTimer = null;
let ajaxPollingTimer = null;

function scheduleRefresh(delay = 300) {
  if (refreshDebounceTimer) clearTimeout(refreshDebounceTimer);
  refreshDebounceTimer = setTimeout(() => {
    fetchChairs(selectedDate);
    fetchTimeSlots(selectedDate);
    fetchWaitingQueue(selectedDate);
  }, delay);
}
window.scheduleRefresh = scheduleRefresh;

function setupSockets() {
  try {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    socket = new WebSocket(`${protocol}//${window.location.host}/ws`);

    socket.onopen = () => {
      console.log('WebSocket connected.');
      setWsConnected(true);
      stopAjaxPolling();
    };

    socket.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (['chair-update', 'slot-update', 'queue-update', 'appointment-update'].includes(data.event)) {
          scheduleRefresh(300);
        } else if (data.event === 'your-turn') {
          showToast('🎉 It\'s your turn! Please proceed to your assigned chair.', 'success');
          scheduleRefresh(300);
        }
      } catch (e) {
        console.error('Socket parse error:', e);
      }
    };

    socket.onerror = () => {
      setWsConnected(false);
    };

    socket.onclose = () => {
      console.warn('WebSocket closed. Polling fallback active.');
      setWsConnected(false);
      startAjaxPolling();
    };

  } catch (err) {
    console.warn('WebSocket init failed, polling fallback.', err);
    setWsConnected(false);
    startAjaxPolling();
  }
}

// AJAX Polling fallback (single timer, 6 seconds)
function startAjaxPolling() {
  if (ajaxPollingTimer) return;
  ajaxPollingTimer = setInterval(() => {
    scheduleRefresh(0);
  }, 6000);
}

function stopAjaxPolling() {
  if (ajaxPollingTimer) {
    clearInterval(ajaxPollingTimer);
    ajaxPollingTimer = null;
  }
}

// Initialize Page Data
document.addEventListener('DOMContentLoaded', () => {
  const user = JSON.parse(localStorage.getItem('barber_user'));
  if (user) {
    const welcomeEl = document.getElementById('cust-welcome-name');
    if (welcomeEl) welcomeEl.textContent = user.name || 'Customer';
    const roleEl = document.getElementById('user-role-badge');
    if (roleEl) roleEl.textContent = (user.role || user.type || 'Customer').toUpperCase();
    const btnNameEl = document.getElementById('top-profile-btn-name');
    if (btnNameEl) btnNameEl.textContent = user.name || 'Profile';
  }

  // Ensure date input default matches selectedDate
  const picker = document.getElementById('slot-picker-date');
  if (picker && !picker.value) {
    picker.value = selectedDate;
  }

  fetchServices();
  fetchProfileDetails();
  fetchWalletBalance();
  scheduleRefresh(0);
  setupSockets();

  // Background sync every 30 seconds
  setInterval(() => {
    scheduleRefresh(0);
  }, 30000);
});

// ══════════════════════════════════════════════════════════
// TOP-RIGHT PROFILE & WALLET FULL-STACK MANAGEMENT
// ══════════════════════════════════════════════════════════

// Fetch authenticated customer profile details
async function fetchProfileDetails() {
  const token = localStorage.getItem('barber_token');
  if (!token) return;

  try {
    const res = await fetch('/api/customers/profile', {
      headers: { 'Authorization': `Bearer ${token}` }
    });
    const data = await res.json();
    if (res.ok && data.data) {
      authenticatedProfile = data.data;
      const name = authenticatedProfile.name || 'Customer';
      const email = authenticatedProfile.email || 'Not provided';
      const mobile = authenticatedProfile.phone || authenticatedProfile.mobile || 'Not provided';

      const welcomeEl = document.getElementById('cust-welcome-name');
      if (welcomeEl) welcomeEl.textContent = name;

      const btnNameEl = document.getElementById('top-profile-btn-name');
      if (btnNameEl) btnNameEl.textContent = name;

      const profileNameEl = document.getElementById('top-profile-name');
      if (profileNameEl) profileNameEl.textContent = name;

      const profileEmailEl = document.getElementById('top-profile-email');
      if (profileEmailEl) profileEmailEl.textContent = email;

      const profileMobileEl = document.getElementById('top-profile-mobile');
      if (profileMobileEl) profileMobileEl.textContent = mobile;

      if (authenticatedProfile.wallet_balance != null) {
        currentWalletBalance = parseFloat(authenticatedProfile.wallet_balance);
        updateAllWalletDisplays(currentWalletBalance);
      }
    }
  } catch (err) {
    console.error('Error fetching profile:', err);
  }
}
window.fetchProfileDetails = fetchProfileDetails;

// Fetch current wallet balance
async function fetchWalletBalance() {
  const token = localStorage.getItem('barber_token');
  if (!token) return;

  try {
    const res = await fetch('/api/wallet/balance', {
      headers: { 'Authorization': `Bearer ${token}` }
    });
    const data = await res.json();
    if (res.ok && data.balance !== undefined) {
      currentWalletBalance = parseFloat(data.balance);
      updateAllWalletDisplays(currentWalletBalance);
    } else {
      fetchProfileDetails();
    }
  } catch (err) {
    fetchProfileDetails();
  }
}
window.fetchWalletBalance = fetchWalletBalance;

// Synchronize all wallet displays in DOM
function updateAllWalletDisplays(balance) {
  const num = parseFloat(balance || 0);
  const formatted = `₹${num.toFixed(2)}`;

  const topDisplay = document.getElementById('top-wallet-balance-display');
  if (topDisplay) topDisplay.textContent = formatted;

  const popupDisplay = document.getElementById('top-wallet-popup-balance');
  if (popupDisplay) popupDisplay.textContent = formatted;

  const slotModalBal = document.getElementById('slot-modal-wallet-balance');
  if (slotModalBal) slotModalBal.textContent = formatted;

  const custBal = document.getElementById('customer-wallet-balance');
  if (custBal) custBal.textContent = num.toFixed(2);
}
window.updateAllWalletDisplays = updateAllWalletDisplays;

// Toggle Wallet dropdown popup
function toggleTopWalletPanel(event, forceOpen = false) {
  if (event) {
    event.stopPropagation();
    event.preventDefault();
  }
  closeTopProfilePanel();

  const panel = document.getElementById('top-wallet-panel');
  if (!panel) return;

  const isDisplayed = panel.style.display === 'block';
  if (isDisplayed && !forceOpen) {
    panel.style.display = 'none';
  } else {
    panel.style.display = 'block';
    const feedback = document.getElementById('top-wallet-feedback');
    if (feedback) feedback.style.display = 'none';
    fetchWalletBalance();
    const input = document.getElementById('top-wallet-amount-input');
    if (input) input.focus();
  }
}
window.toggleTopWalletPanel = toggleTopWalletPanel;

function closeTopWalletPanel() {
  const panel = document.getElementById('top-wallet-panel');
  if (panel) panel.style.display = 'none';
}
window.closeTopWalletPanel = closeTopWalletPanel;

// Toggle Profile dropdown popup
function toggleTopProfilePanel(event) {
  if (event) {
    event.stopPropagation();
    event.preventDefault();
  }
  closeTopWalletPanel();

  const panel = document.getElementById('top-profile-panel');
  if (!panel) return;

  const isDisplayed = panel.style.display === 'block';
  if (isDisplayed) {
    panel.style.display = 'none';
  } else {
    panel.style.display = 'block';
    fetchProfileDetails();
  }
}
window.toggleTopProfilePanel = toggleTopProfilePanel;

function closeTopProfilePanel() {
  const panel = document.getElementById('top-profile-panel');
  if (panel) panel.style.display = 'none';
}
window.closeTopProfilePanel = closeTopProfilePanel;

// Quick amount pill selection
function setQuickRechargeAmount(amount) {
  const input = document.getElementById('top-wallet-amount-input');
  if (input) {
    input.value = amount;
    input.focus();
  }
}
window.setQuickRechargeAmount = setQuickRechargeAmount;

// Execute top-right wallet recharge
async function executeTopWalletRecharge(event) {
  event.preventDefault();
  const token = localStorage.getItem('barber_token');
  if (!token) {
    showToast('Please log in to recharge your wallet.', 'danger');
    return;
  }

  const input = document.getElementById('top-wallet-amount-input');
  const feedback = document.getElementById('top-wallet-feedback');
  const submitBtn = document.getElementById('top-wallet-recharge-btn');

  const valStr = input ? input.value.trim() : '';
  const amount = parseFloat(valStr);

  if (isNaN(amount) || valStr === '') {
    showFeedback('Please enter a valid numeric recharge amount.', 'danger');
    return;
  }
  if (amount < 100) {
    showFeedback('Minimum recharge amount is ₹100.', 'danger');
    return;
  }
  if (amount > 2000) {
    showFeedback('Maximum recharge amount is ₹2,000 per transaction.', 'danger');
    return;
  }

  submitBtn.disabled = true;
  submitBtn.innerHTML = `<span class="spinner-border spinner-border-sm me-1"></span> Processing…`;

  try {
    const res = await fetch('/api/wallet/recharge', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify({ amount: amount })
    });

    const data = await res.json();
    if (!res.ok) {
      throw new Error(data.message || 'Recharge failed.');
    }

    currentWalletBalance = parseFloat(data.balance);
    updateAllWalletDisplays(currentWalletBalance);
    showFeedback(`✓ Recharged ₹${amount.toFixed(2)} successfully! Balance: ₹${currentWalletBalance.toFixed(2)}`, 'success');
    showToast(`✓ Wallet recharged with ₹${amount.toFixed(2)}!`, 'success');

    if (input) input.value = '';

    // If there was a pending booking due to insufficient funds, reopen slot modal
    if (pendingBookingData) {
      setTimeout(() => {
        const slotModalEl = document.getElementById('slotBookingModal');
        if (slotModalEl) {
          const slotModal = bootstrap.Modal.getInstance(slotModalEl) || new bootstrap.Modal(slotModalEl);
          slotModal.show();
          showToast(`Now you have ₹${currentWalletBalance.toFixed(2)}. You can confirm your booking!`, 'info');
        }
      }, 700);
    }

  } catch (err) {
    showFeedback(err.message, 'danger');
  } finally {
    submitBtn.disabled = false;
    submitBtn.innerHTML = `<i class="fa-solid fa-plus-circle me-1"></i> Recharge Wallet`;
  }

  function showFeedback(msg, type) {
    if (!feedback) return;
    feedback.className = `small mt-2 p-1.5 rounded text-center alert alert-${type === 'danger' ? 'danger' : 'success'} py-1 px-2`;
    feedback.textContent = msg;
    feedback.style.display = 'block';
  }
}
window.executeTopWalletRecharge = executeTopWalletRecharge;

// Redirect from Insufficient Wallet Balance modal to top-right Wallet panel
function handleInsufficientRechargeRedirect() {
  const modalEl = document.getElementById('insufficientWalletModal');
  if (modalEl) {
    const modal = bootstrap.Modal.getInstance(modalEl);
    if (modal) modal.hide();
  }

  // Open top-right wallet panel
  toggleTopWalletPanel(null, true);

  // Pre-calculate suggested recharge
  if (pendingBookingData && pendingBookingData.price) {
    const diff = Math.max(100, Math.ceil(pendingBookingData.price - currentWalletBalance));
    const suggested = Math.min(2000, Math.max(100, diff));
    const amtInput = document.getElementById('top-wallet-amount-input');
    if (amtInput) {
      amtInput.value = suggested;
      amtInput.focus();
    }
  }
}
window.handleInsufficientRechargeRedirect = handleInsufficientRechargeRedirect;

// Close popups on click outside
document.addEventListener('click', (event) => {
  const walletWrapper = document.getElementById('top-wallet-dropdown-wrapper');
  if (walletWrapper && !walletWrapper.contains(event.target)) {
    closeTopWalletPanel();
  }
  const profileWrapper = document.getElementById('top-profile-dropdown-wrapper');
  if (profileWrapper && !profileWrapper.contains(event.target)) {
    closeTopProfilePanel();
  }
});
