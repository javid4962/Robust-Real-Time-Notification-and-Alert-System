const API_URL = 'http://localhost:8080/api';
let stompClient = null;
let isAdmin = false;
let isRabbitMqEnabled = true;
let systemComparisonIntervalId = null;
let historicalTrendsIntervalId = null;
let analysisTrendChart = null;
let analysisBarChart = null;

// Connect to WebSocket on Load
function connect() {
    const socket = new SockJS('http://localhost:8080/ws');
    stompClient = Stomp.over(socket);
    stompClient.debug = null; // Disable debug logs
    
    // Check if we are on the Admin page (no user input) or old simulated page
    const userElement = document.getElementById('current-user');
    const currentUser = userElement ? userElement.value : 'ADMIN';
    
    // Determine if this is admin dashboard
    isAdmin = (currentUser === 'ADMIN');
    
    stompClient.connect({}, function (frame) {
        setConnected(true, currentUser);
        console.log('Connected: ' + frame);
        
        if (isAdmin) {
            // ADMIN: Load stats and history from DB
            loadStats();
            loadNotificationHistory();
            loadRabbitMqStatus();
            loadSystemComparison();
            startSystemComparisonAutoRefresh();
            loadHistoricalTrends();
            startHistoricalTrendsAutoRefresh();
            
            // Subscribe to admin traffic stream (includes broadcast + targeted + batches).
            stompClient.subscribe('/topic/admin/traffic', function () {
                scheduleAdminRefresh();
            });
        } else {
            // USER PORTAL: Subscribe to topics
            stompClient.subscribe('/topic/public', function (notification) {
                handleIncomingMessage(JSON.parse(notification.body));
            });

            if (currentUser !== 'guest') {
                stompClient.subscribe('/topic/user/' + currentUser, function (notification) {
                    handleIncomingMessage(JSON.parse(notification.body));
                });
                showToast(`Subscribed to alerts for: ${currentUser}`);
            } else {
                showToast('Connected as Guest (Public alerts only)');
            }
        }

    }, function(error) {
        setConnected(false);
        console.error("STOMP error " + error);
        setTimeout(connect, 5000); // Retry after 5s
    });
}

function reconnect() {
    if (stompClient) {
        stompClient.disconnect();
    }
    setTimeout(connect, 500);
}

function setConnected(connected, user = '') {
    const status = document.getElementById('connection-status');
    if (connected) {
        if (isAdmin) {
            status.innerText = `● Online as: ADMIN`;
        } else {
            status.innerText = `● Online as: ${user}`;
        }
        status.className = 'status-connected';
    } else {
        status.innerText = '● System Offline';
        status.className = 'status-disconnected';
    }
}

function scheduleAdminRefresh() {
    if (!window.refreshPending) {
        window.refreshPending = true;
        setTimeout(() => {
            loadStats();
            loadNotificationHistory();
            window.refreshPending = false;
        }, 500);
    }
}

async function loadRabbitMqStatus() {
    const toggle = document.getElementById('rabbitmq-toggle');
    if (!toggle) return;

    try {
        const response = await fetch(`${API_URL}/notifications/rabbitmq/status`);
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const data = await response.json();
        isRabbitMqEnabled = data.enabled === true;
        updateRabbitMqUiState();
    } catch (e) {
        console.error("Failed to load RabbitMQ status", e);
    }
}

function renderSystemDetails(targetId, details) {
    const target = document.getElementById(targetId);
    if (!target || !details) return;

    target.innerHTML = `
        <div><strong>Flow:</strong> ${details.deliveryFlow}</div>
        <div><strong>RabbitMQ:</strong> ${details.rabbitMqUsage}</div>
        <div><strong>If RabbitMQ Down:</strong> ${details.whenRabbitMqUnavailable}</div>
        <div><strong>UI Control:</strong> ${details.uiControl}</div>
        ${details.directModeImpact ? `<div><strong>Direct Mode Impact:</strong> ${details.directModeImpact}</div>` : ''}
        ${details.accuracy ? `<div><strong>Accuracy:</strong> ${details.accuracy}</div>` : ''}
    `;
}

function escapeHtml(value) {
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function renderPathClass(path) {
    switch (path) {
        case 'QUEUE_CONSUMER':
            return 'queue';
        case 'DIRECT_MODE':
            return 'direct';
        case 'DIRECT_FALLBACK':
            return 'fallback';
        default:
            return 'unknown';
    }
}

function ensureAnalysisCharts() {
    if (analysisTrendChart && analysisBarChart) return true;
    if (typeof Chart === 'undefined') return false;

    const trendCanvas = document.getElementById('direct-fallback-analysis-chart');
    const barCanvas = document.getElementById('direct-fallback-bar-chart');
    if (!trendCanvas || !barCanvas) return false;

    const trendCtx = trendCanvas.getContext('2d');
    const barCtx = barCanvas.getContext('2d');
    if (!trendCtx || !barCtx) return false;

    analysisTrendChart = new Chart(trendCtx, {
        type: 'line',
        data: {
            labels: [],
            datasets: [
                {
                    label: 'Queue',
                    data: [],
                    borderColor: '#69f0ae',
                    backgroundColor: 'rgba(105, 240, 174, 0.15)',
                    tension: 0.3,
                    borderWidth: 2
                },
                {
                    label: 'Direct',
                    data: [],
                    borderColor: '#2cc9ff',
                    backgroundColor: 'rgba(44, 201, 255, 0.15)',
                    tension: 0.3,
                    borderWidth: 2
                },
                {
                    label: 'Fallback',
                    data: [],
                    borderColor: '#ffb347',
                    backgroundColor: 'rgba(255, 179, 71, 0.15)',
                    tension: 0.3,
                    borderWidth: 2
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { labels: { color: '#ddd' } }
            },
            scales: {
                x: { ticks: { color: '#aaa' }, grid: { color: 'rgba(255,255,255,0.08)' } },
                y: { beginAtZero: true, ticks: { color: '#aaa' }, grid: { color: 'rgba(255,255,255,0.08)' } }
            }
        }
    });

    analysisBarChart = new Chart(barCtx, {
        type: 'bar',
        data: {
            labels: ['Queue Total', 'Direct Total', 'Fallback Total'],
            datasets: [
                {
                    label: 'Deliveries',
                    data: [0, 0, 0],
                    backgroundColor: ['rgba(105, 240, 174, 0.6)', 'rgba(44, 201, 255, 0.6)', 'rgba(255, 179, 71, 0.6)'],
                    borderColor: ['#69f0ae', '#2cc9ff', '#ffb347'],
                    borderWidth: 1
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { labels: { color: '#ddd' } }
            },
            scales: {
                x: { ticks: { color: '#aaa' }, grid: { color: 'rgba(255,255,255,0.08)' } },
                y: { beginAtZero: true, ticks: { color: '#aaa' }, grid: { color: 'rgba(255,255,255,0.08)' } }
            }
        }
    });

    return true;
}

function getHistoricalTrendOptions() {
    const rangeElement = document.getElementById('history-range-select');
    const bucketElement = document.getElementById('history-bucket-select');

    return {
        minutes: rangeElement ? parseInt(rangeElement.value, 10) : 180,
        bucketMinutes: bucketElement ? parseInt(bucketElement.value, 10) : 5
    };
}

function applyHistoricalTrends(data) {
    if (!ensureAnalysisCharts()) return;

    const labels = Array.isArray(data.labels) ? data.labels : [];
    const queueSeries = Array.isArray(data.queueSeries) ? data.queueSeries : [];
    const directSeries = Array.isArray(data.directSeries) ? data.directSeries : [];
    const fallbackSeries = Array.isArray(data.fallbackSeries) ? data.fallbackSeries : [];
    const totals = data.totals || {};

    analysisTrendChart.data.labels = labels;
    analysisTrendChart.data.datasets[0].data = queueSeries;
    analysisTrendChart.data.datasets[1].data = directSeries;
    analysisTrendChart.data.datasets[2].data = fallbackSeries;
    analysisTrendChart.update('none');

    analysisBarChart.data.datasets[0].data = [
        totals.queue || 0,
        totals.direct || 0,
        totals.fallback || 0
    ];
    analysisBarChart.update('none');
}

async function loadHistoricalTrends() {
    const options = getHistoricalTrendOptions();
    try {
        const response = await fetch(`${API_URL}/notifications/analytics/history?minutes=${options.minutes}&bucketMinutes=${options.bucketMinutes}`);
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const data = await response.json();
        applyHistoricalTrends(data);
    } catch (e) {
        console.error("Failed to load historical trends", e);
    }
}

function startHistoricalTrendsAutoRefresh() {
    if (historicalTrendsIntervalId) return;
    historicalTrendsIntervalId = setInterval(loadHistoricalTrends, 10000);
}

function updatePracticalRealtime(practical) {
    if (!practical) return;

    const presentImpacted = practical.presentSystemImpacted || 0;
    const proposedSaved = practical.proposedSystemSaved || 0;
    const queueProcessed = practical.queueProcessed || 0;
    const directModeProcessed = practical.directModeProcessed || 0;
    const fallbackProcessed = practical.fallbackProcessed || 0;
    const avgQueueDelayMs = practical.avgQueueDelayMs || 0;
    const avgDirectDelayMs = practical.avgDirectDelayMs || 0;
    const recentEvents = Array.isArray(practical.recentEvents) ? practical.recentEvents : [];

    const presentImpactedEl = document.getElementById('present-impacted-count');
    const proposedSavedEl = document.getElementById('proposed-saved-count');
    const queueProcessedEl = document.getElementById('queue-processed-count');
    const directModeEl = document.getElementById('direct-mode-count');
    const fallbackEl = document.getElementById('fallback-count');
    const avgQueueEl = document.getElementById('avg-queue-delay');
    const avgDirectEl = document.getElementById('avg-direct-delay');
    const eventFeed = document.getElementById('practical-event-feed');

    if (presentImpactedEl) presentImpactedEl.innerText = presentImpacted;
    if (proposedSavedEl) proposedSavedEl.innerText = proposedSaved;
    if (queueProcessedEl) queueProcessedEl.innerText = queueProcessed;
    if (directModeEl) directModeEl.innerText = directModeProcessed;
    if (fallbackEl) fallbackEl.innerText = fallbackProcessed;
    if (avgQueueEl) avgQueueEl.innerText = avgQueueDelayMs;
    if (avgDirectEl) avgDirectEl.innerText = avgDirectDelayMs;

    if (eventFeed) {
        if (recentEvents.length === 0) {
            eventFeed.innerHTML = 'No flow events yet.';
        } else {
            eventFeed.innerHTML = recentEvents.map(event => {
                const path = event.path || 'UNKNOWN';
                const pathClass = renderPathClass(path);
                const id = event.notificationId ?? '-';
                const target = event.target || 'BROADCAST';
                const priority = event.priority || 'UNKNOWN';
                const delay = event.delayMs ?? 0;
                const time = event.processedAt ? new Date(event.processedAt).toLocaleTimeString() : '-';

                return `
                    <div class="practical-event-item">
                        <span class="path-badge ${pathClass}">${escapeHtml(path)}</span>
                        <span>#${escapeHtml(id)} ${escapeHtml(priority)} ${escapeHtml(target)}</span>
                        <span>${escapeHtml(delay)}ms</span>
                        <span>${escapeHtml(time)}</span>
                    </div>
                `;
            }).join('');
        }
    }

}

async function loadSystemComparison() {
    const runtimeSummary = document.getElementById('active-runtime-summary');
    if (!runtimeSummary) return;

    try {
        const response = await fetch(`${API_URL}/notifications/system/comparison`);
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const data = await response.json();
        renderSystemDetails('present-system-details', data.presentSystem);
        renderSystemDetails('proposed-system-details', data.proposedSystem);
        updatePracticalRealtime(data.practicalRealtime);

        const runtime = data.currentRuntime || {};
        runtimeSummary.className = `runtime-summary ${runtime.activePath === 'DIRECT_MODE' ? 'direct' : 'queue'}`;
        const circuitSuffix = runtime.rabbitMqCircuitOpen ? ' RabbitMQ circuit is OPEN; direct fallback is short-circuited for speed.' : '';
        runtimeSummary.innerText = (runtime.summary || 'Runtime mode information unavailable.') + circuitSuffix;
    } catch (e) {
        runtimeSummary.className = 'runtime-summary error';
        runtimeSummary.innerText = 'Unable to load system comparison.';
        console.error("Failed to load system comparison", e);
    }
}

function startSystemComparisonAutoRefresh() {
    if (systemComparisonIntervalId || !document.getElementById('active-runtime-summary')) return;
    systemComparisonIntervalId = setInterval(loadSystemComparison, 3000);
}

function updateRabbitMqUiState() {
    const toggle = document.getElementById('rabbitmq-toggle');
    const badge = document.getElementById('rabbitmq-mode-badge');
    const help = document.getElementById('rabbitmq-help-text');

    if (!toggle || !badge || !help) return;

    toggle.checked = isRabbitMqEnabled;
    badge.className = `rabbitmq-mode-badge ${isRabbitMqEnabled ? 'enabled' : 'disabled'}`;
    badge.innerText = isRabbitMqEnabled ? 'RabbitMQ Enabled' : 'RabbitMQ Disabled';
    help.innerText = isRabbitMqEnabled
        ? 'Queue mode is ON: notifications are routed through RabbitMQ consumers.'
        : 'Queue mode is OFF: direct path is active and low-priority messages can be partially dropped under load.';
}

async function setRabbitMqStatus(enabled) {
    const toggle = document.getElementById('rabbitmq-toggle');
    if (!toggle) return;

    toggle.disabled = true;
    try {
        const response = await fetch(`${API_URL}/notifications/rabbitmq/status`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ enabled })
        });

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const data = await response.json();
        isRabbitMqEnabled = data.enabled === true;
        updateRabbitMqUiState();
        await loadSystemComparison();
        showToast(isRabbitMqEnabled ? 'RabbitMQ enabled' : 'RabbitMQ disabled');
    } catch (e) {
        showToast('Failed to update RabbitMQ mode: ' + e.message, true);
        toggle.checked = isRabbitMqEnabled;
    } finally {
        toggle.disabled = false;
    }
}

// ===== ADMIN FUNCTIONS =====

async function loadStats() {
    try {
        const response = await fetch(`${API_URL}/notifications/stats`);
        if (response.ok) {
            const stats = await response.json();
            document.getElementById('msg-count').innerText = stats.totalNotifications;
            document.getElementById('active-user-count').innerText = stats.totalUsers;
        }
    } catch (e) {
        console.error("Failed to load stats", e);
    }
}

async function loadNotificationHistory() {
    try {
        const response = await fetch(`${API_URL}/notifications`);
        if (response.ok) {
            const notifications = await response.json();
            const feed = document.getElementById('notification-feed');
            feed.innerHTML = ''; // Clear
            
            if (notifications.length === 0) {
                feed.innerHTML = '<div style="text-align: center; color: #555; padding-top: 100px;">No history found.</div>';
                return;
            }
            
            notifications.forEach(notification => {
                const div = document.createElement('div');
                div.className = `notification-item ${notification.priority}`;
                
                const time = new Date(notification.timestamp).toLocaleTimeString();
                const recipient = notification.recipient ? '@' + notification.recipient.username : '📢 Broadcast';
                
                div.innerHTML = `
                    <div style="display: flex; justify-content: space-between; align-items: start;">
                        <div style="flex: 1;">
                            <div>
                                <span class="badge">${notification.priority}</span>
                                <span style="font-size: 0.85em; color: #888; margin-left: 10px;">${recipient}</span>
                            </div>
                            <div style="margin-top: 8px; font-size: 1em;">${notification.message}</div>
                        </div>
                        <div style="text-align: right; min-width: 100px;">
                            <div class="timestamp">${time}</div>
                            <button onclick="deleteNotification(${notification.id})" 
                                    style="background: transparent; color: #ff5252; padding: 4px 10px; 
                                           font-size: 0.85em; margin-top: 8px; width: auto; 
                                           border: 1px solid #ff5252; cursor: pointer; border-radius: 4px;">
                                Delete
                            </button>
                        </div>
                    </div>
                `;
                feed.appendChild(div);
            });
        }
    } catch (e) {
        console.error("Failed to load notification history", e);
    }
}

window.deleteNotification = async function(id) {
    if (!confirm('Delete this notification from the database?')) return;
    
    try {
        const response = await fetch(`${API_URL}/notifications/${id}`, {
            method: 'DELETE'
        });
        
        if (response.ok || response.status === 204) {
            showToast('Notification deleted');
            loadStats();
            loadNotificationHistory();
        } else {
            showToast('Failed to delete', true);
        }
    } catch (e) {
        showToast('Error: ' + e.message, true);
    }
};

// ===== REST API Calls =====

async function sendNotification(event) {
    event.preventDefault();
    const btn = event.target.querySelector('button');
    const originalText = btn.innerText;
    btn.innerText = 'Sending...';

    const message = document.getElementById('msg-content').value;
    const priority = document.getElementById('msg-priority').value;
    const username = document.getElementById('msg-recipient').value; // Optional

    const payload = { message, priority, username };

    try {
        const response = await fetch(`${API_URL}/notifications/send`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        if (response.ok) {
            showToast('Notification Sent Successfully');
            document.getElementById('notification-form').reset();
            
            // Refresh admin stats if admin
            if (isAdmin) {
                setTimeout(() => {
                    loadStats();
                    loadNotificationHistory();
                }, 500);
            }
        } else {
            const errorData = await response.json();
            showToast('Error: ' + (errorData.error || 'Failed to send'), true);
        }
    } catch (e) {
        showToast('Error: ' + e.message, true);
    } finally {
        btn.innerText = originalText;
    }
}

// ===== USER PORTAL FUNCTIONS =====

function handleIncomingMessage(data) {
    if (Array.isArray(data)) {
        console.log(`Received batch of ${data.length} messages`);
        data.forEach(notification => showNotification(notification));
        showToast(`Received ${data.length} new notifications`);
    } else {
        showNotification(data);
    }
}

function showNotification(notification) {
    const feed = document.getElementById('notification-feed');
    const div = document.createElement('div');
    div.className = `notification-item ${notification.priority}`;
    
    // Format timestamp
    const time = new Date(notification.timestamp).toLocaleTimeString();

    div.innerHTML = `
        <div class="timestamp">${time}</div>
        <div>
            <span class="badge">${notification.priority}</span>
            <strong>${notification.recipient ? '@' + notification.recipient.username : 'Broadcast'}</strong>
        </div>
        <div style="margin-top: 5px; font-size: 1.1em;">${notification.message}</div>
    `;
    
    feed.prepend(div);
}

function showToast(msg, isError = false) {
    console.log(msg);
    // You can implement a fancy toast notification here
}

async function approveUser(userId, username) {
    try {
        const response = await fetch(`${API_URL}/users/${userId}/approve`, {
            method: 'PUT'
        });

        if (response.ok) {
            showToast(`User ${username} approved.`);
            loadUsers();
            if (isAdmin) loadStats();
            return;
        }

        const error = await response.json();
        showToast(error.error || `Failed to approve ${username}.`, true);
    } catch (e) {
        showToast('Error: ' + e.message, true);
    }
}

// Load and render Users
async function loadUsers() {
    const userList = document.getElementById('user-list');
    const pendingList = document.getElementById('pending-user-list');
    if (!userList && !pendingList) return;

    try {
        const response = await fetch(`${API_URL}/users`);
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const users = await response.json();
        users.sort((a, b) => (a.username || '').localeCompare(b.username || ''));

        if (userList) {
            userList.innerHTML = '';
        }
        if (pendingList) {
            pendingList.innerHTML = '';
        }

        let pendingCount = 0;
        users.forEach(user => {
            if (userList) {
                const badge = document.createElement('span');
                badge.className = 'badge';
                badge.style.backgroundColor = user.approved ? '#244526' : '#4d3b12';
                badge.style.border = `1px solid ${user.approved ? '#4caf50' : '#ffb347'}`;
                badge.style.fontSize = '0.85em';
                badge.style.padding = '5px 10px';
                badge.innerText = `${user.username} ${user.approved ? '[APPROVED]' : '[PENDING]'}`;
                userList.appendChild(badge);
            }

            if (!user.approved && pendingList) {
                pendingCount += 1;
                const row = document.createElement('div');
                row.style.display = 'flex';
                row.style.justifyContent = 'space-between';
                row.style.alignItems = 'center';
                row.style.gap = '10px';
                row.style.padding = '8px 10px';
                row.style.border = '1px solid #3f3f3f';
                row.style.borderRadius = '8px';
                row.style.background = 'rgba(255, 179, 71, 0.05)';

                const details = document.createElement('div');
                details.innerHTML = `
                    <div style="font-weight: 600; color: #f4f4f4;">${user.username}</div>
                    <div style="font-size: 0.8em; color: #aaa;">${user.email || 'No email'}</div>
                `;

                const approveBtn = document.createElement('button');
                approveBtn.innerText = 'Approve';
                approveBtn.style.width = 'auto';
                approveBtn.style.padding = '8px 12px';
                approveBtn.style.fontSize = '0.8em';
                approveBtn.style.background = '#2e7d32';
                approveBtn.style.border = 'none';
                approveBtn.style.color = '#fff';
                approveBtn.style.cursor = 'pointer';
                approveBtn.onclick = () => approveUser(user.id, user.username);

                row.appendChild(details);
                row.appendChild(approveBtn);
                pendingList.appendChild(row);
            }
        });

        if (pendingList && pendingCount === 0) {
            pendingList.innerHTML = '<div style="color:#777; font-size:0.85em;">No users waiting for approval.</div>';
        }
    } catch (e) {
        console.error("Failed to load users", e);
    }
}

// Initial Load
connect();

// Load users if element exists (admin page)
if (document.getElementById('user-list')) {
    loadUsers();
}

// Attach event listeners if forms exist
if (document.getElementById('notification-form')) {
    document.getElementById('notification-form').addEventListener('submit', sendNotification);
}

if (document.getElementById('rabbitmq-toggle')) {
    document.getElementById('rabbitmq-toggle').addEventListener('change', (event) => {
        setRabbitMqStatus(event.target.checked);
    });
    loadRabbitMqStatus();
    loadSystemComparison();
}

const historyRangeSelect = document.getElementById('history-range-select');
const historyBucketSelect = document.getElementById('history-bucket-select');
if (historyRangeSelect) {
    historyRangeSelect.addEventListener('change', loadHistoricalTrends);
}
if (historyBucketSelect) {
    historyBucketSelect.addEventListener('change', loadHistoricalTrends);
}
if (historyRangeSelect || historyBucketSelect) {
    loadHistoricalTrends();
}
