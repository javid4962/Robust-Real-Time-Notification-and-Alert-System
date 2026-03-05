package com.datavalley.notification.service;

import com.datavalley.notification.model.Notification;
import com.datavalley.notification.repository.NotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class NotificationBatcher {

    @Autowired
    private WebSocketDispatcher webSocketDispatcher;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private SystemDifferentiationService systemDifferentiationService;

    // Buffer for targeted user notifications: Username -> List<BatchItem>
    private final Map<String, List<BatchItem>> userBuffers = new ConcurrentHashMap<>();

    // Buffer for broadcast notifications.
    private final List<BatchItem> broadcastBuffer = new ArrayList<>();

    // Lock for thread safety
    private final Object lock = new Object();

    /**
     * Add a notification to the batch buffer.
     */
    public void addToBatch(Notification notification, DeliveryPath deliveryPath) {
        BatchItem item = new BatchItem(notification, deliveryPath);
        synchronized (lock) {
            if (notification.getRecipient() != null) {
                // Targeted notification
                String username = notification.getRecipient().getUsername();
                userBuffers.computeIfAbsent(username, k -> new ArrayList<>()).add(item);
            } else {
                // Broadcast notification
                broadcastBuffer.add(item);
            }
        }
    }

    /**
     * Flush the buffers on a short interval to keep queue mode close to direct mode latency.
     */
    @Scheduled(fixedRateString = "${notification.batch.flush-interval-ms:1000}")
    public void flush() {
        // Create local copies to minimize locking time
        Map<String, List<BatchItem>> usersToFlush;
        List<BatchItem> broadcastsToFlush;

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
            flushBucket(null, broadcastsToFlush);
        }

        // Process user-specific batches
        usersToFlush.forEach((username, items) -> {
            System.out.println("Batcher: Flushing " + items.size() + " messages for " + username);
            flushBucket(username, items);
        });
    }

    private void flushBucket(String username, List<BatchItem> items) {
        List<Notification> notifications = items.stream()
                .map(BatchItem::notification)
                .collect(Collectors.toList());

        try {
            webSocketDispatcher.dispatchBatch(notifications, username);
            items.forEach(item -> markAsSent(item.notification(), item.deliveryPath()));
        } catch (Exception ex) {
            System.err.println("Batch dispatch failed. Re-queueing " + items.size() + " messages. Reason: " + ex.getMessage());
            requeue(username, items);
        }
    }

    private void requeue(String username, List<BatchItem> items) {
        synchronized (lock) {
            if (username == null) {
                broadcastBuffer.addAll(items);
            } else {
                userBuffers.computeIfAbsent(username, key -> new ArrayList<>()).addAll(items);
            }
        }
    }

    private void markAsSent(Notification notification, DeliveryPath deliveryPath) {
        Notification current = notificationRepository.findById(notification.getId()).orElse(notification);
        current.setStatus(Notification.NotificationStatus.SENT);
        current.setProcessedAt(LocalDateTime.now());
        current.setDeliveryPath(deliveryPath.name());
        current.setDeliveryLatencyMs(calculateLatencyMs(current));
        notificationRepository.save(current);
        systemDifferentiationService.record(current, deliveryPath);
    }

    private long calculateLatencyMs(Notification notification) {
        if (notification.getTimestamp() == null || notification.getProcessedAt() == null) {
            return 0L;
        }
        long latency = Duration.between(notification.getTimestamp(), notification.getProcessedAt()).toMillis();
        return Math.max(latency, 0L);
    }

    private record BatchItem(Notification notification, DeliveryPath deliveryPath) {}
}
