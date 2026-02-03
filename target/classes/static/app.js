const API_URL = 'http://localhost:8080/api';
let stompClient = null;
let isAdmin = false;

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
            
            // Subscribe to public to get real-time updates
            stompClient.subscribe('/topic/public', function (notification) {
                // Refresh stats and history when new message arrives
                setTimeout(() => {
                    loadStats();
                    loadNotificationHistory();
                }, 500);
            });
        } else {
            // USER PORTAL: Subscribe to topics
            stompClient.subscribe('/topic/public', function (notification) {
                showNotification(JSON.parse(notification.body));
            });

            if (currentUser !== 'guest') {
                stompClient.subscribe('/topic/user/' + currentUser, function (notification) {
                    showNotification(JSON.parse(notification.body));
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

async function createUser(event) {
    event.preventDefault();
    const username = document.getElementById('user-name').value;
    
    try {
        const response = await fetch(`${API_URL}/users`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username, notificationsEnabled: true })
        });

        if (response.ok) {
            showToast(`User ${username} created!`);
            document.getElementById('user-form').reset();
            loadUsers(); // Refresh list
            if (isAdmin) loadStats(); // Update user count
        } else {
            showToast('Failed to create user', true);
        }
    } catch (e) {
        showToast('Error: ' + e.message, true);
    }
}

// ===== USER PORTAL FUNCTIONS =====

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

// Load Users
async function loadUsers() {
    try {
        const response = await fetch(`${API_URL}/users`);
        if (response.ok) {
            const users = await response.json();
            const userList = document.getElementById('user-list');
            userList.innerHTML = ''; // Clear current
            
            users.forEach(user => {
                const badge = document.createElement('span');
                badge.className = 'badge';
                badge.style.backgroundColor = '#444';
                badge.style.fontSize = '0.9em';
                badge.style.padding = '5px 10px';
                badge.innerText = user.username;
                userList.appendChild(badge);
            });
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

if (document.getElementById('user-form')) {
    document.getElementById('user-form').addEventListener('submit', createUser);
}
