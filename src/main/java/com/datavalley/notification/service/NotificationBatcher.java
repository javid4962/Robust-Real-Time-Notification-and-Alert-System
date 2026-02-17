package com.datavalley.notification.service;

import com.datavalley.notification.model.Notification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NotificationBatcher {

    @Autowired
    private WebSocketDispatcher webSocketDispatcher;

    // Buffer for targeted user notifications: Username -> List<Notification>
    private final Map<String, List<Notification>> userBuffers = new ConcurrentHashMap<>();

    // Buffer for broadcast notifications
    private final List<Notification> broadcastBuffer = new ArrayList<>();

    // Lock for thread safety
    private final Object lock = new Object();

    /**
     * Add a notification to the batch buffer.
     */
    public void addToBatch(Notification notification) {
        synchronized (lock) {
            if (notification.getRecipient() != null) {
                // Targeted notification
                String username = notification.getRecipient().getUsername();
                userBuffers.computeIfAbsent(username, k -> new ArrayList<>()).add(notification);
            } else {
                // Broadcast notification
                broadcastBuffer.add(notification);
            }
        }
    }

    /**
     * Flush the buffers every 5 seconds.
     * This reduces network chatter and prevents client overload.
     */
    @Scheduled(fixedRate = 5000)
    public void flush() {
        // Create local copies to minimize locking time
        Map<String, List<Notification>> usersToFlush;
        List<Notification> broadcastsToFlush;

        synchronized (lock) {
            if (userBuffers.isEmpty() && broadcastBuffer.isEmpty()) {
                return; // Nothing to do
            }

            usersToFlush = new ConcurrentHashMap<>(userBuffers);
            broadcastsToFlush = new ArrayList<>(broadcastBuffer);

            userBuffers.clear();
            broadcastBuffer.clear();
        }

        // Process broadcasts
        if (!broadcastsToFlush.isEmpty()) {
            System.out.println("Batcher: Flushing " + broadcastsToFlush.size() + " broadcast messages.");
            webSocketDispatcher.dispatchBatch(broadcastsToFlush, null);
        }

        // Process user-specific batches
        usersToFlush.forEach((username, notifications) -> {
            System.out.println("Batcher: Flushing " + notifications.size() + " messages for " + username);
            webSocketDispatcher.dispatchBatch(notifications, username);
        });
    }
}
