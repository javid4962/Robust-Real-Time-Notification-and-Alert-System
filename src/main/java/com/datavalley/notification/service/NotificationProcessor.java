package com.datavalley.notification.service;

import com.datavalley.notification.model.Notification;
import com.datavalley.notification.repository.NotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class NotificationProcessor {

    @Autowired
    private WebSocketDispatcher webSocketDispatcher;

    @Autowired
    private NotificationBatcher notificationBatcher;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private SystemDifferentiationService systemDifferentiationService;

    public void process(Notification notification, boolean batch, DeliveryPath deliveryPath) {
        Notification current = loadCurrent(notification);
        System.out.println("Processing Notification: " + current.getId() + " Priority: " + current.getPriority());

        // Idempotency guard for retry scenarios.
        if (current.getStatus() == Notification.NotificationStatus.SENT) {
            System.out.println("Skipping already SENT notification: " + current.getId());
            return;
        }

        // Respect user-level preference before dispatch.
        if (current.getRecipient() != null && !current.getRecipient().isNotificationsEnabled()) {
            System.out.println("Skipping WebSocket dispatch for user " + current.getRecipient().getUsername() + " (Notifications Disabled)");
            markAsSent(current, deliveryPath);
            return;
        }

        current.setDeliveryAttempts((current.getDeliveryAttempts() == null ? 0 : current.getDeliveryAttempts()) + 1);
        current.setStatus(Notification.NotificationStatus.PROCESSING);
        notificationRepository.save(current);

        try {
            if (batch) {
                notificationBatcher.addToBatch(current, deliveryPath);
            } else {
                webSocketDispatcher.dispatch(current);
                markAsSent(current, deliveryPath);
            }
        } catch (Exception e) {
            System.err.println("Failed to dispatch to WebSocket: " + e.getMessage());
            markAsFailed(current);
            throw new RuntimeException("WebSocket Dispatch Failed", e);
        }
    }

    public void markAsSent(Notification notification, DeliveryPath deliveryPath) {
        Notification current = loadCurrent(notification);

        current.setStatus(Notification.NotificationStatus.SENT);
        current.setProcessedAt(LocalDateTime.now());
        current.setDeliveryPath(deliveryPath.name());
        current.setDeliveryLatencyMs(calculateLatencyMs(current));
        notificationRepository.save(current);

        systemDifferentiationService.record(current, deliveryPath);
    }

    public void markAsFailed(Notification notification) {
        markAsFailed(notification, null);
    }

    public void markAsFailed(Notification notification, DeliveryPath deliveryPath) {
        Notification current = loadCurrent(notification);
        current.setStatus(Notification.NotificationStatus.FAILED);
        current.setProcessedAt(LocalDateTime.now());
        if (deliveryPath != null) {
            current.setDeliveryPath(deliveryPath.name());
            current.setDeliveryLatencyMs(calculateLatencyMs(current));
        }
        notificationRepository.save(current);
    }

    private Notification loadCurrent(Notification notification) {
        if (notification.getId() == null) {
            return notification;
        }
        return notificationRepository.findById(notification.getId()).orElse(notification);
    }

    private long calculateLatencyMs(Notification notification) {
        if (notification.getTimestamp() == null || notification.getProcessedAt() == null) {
            return 0L;
        }
        long latency = Duration.between(notification.getTimestamp(), notification.getProcessedAt()).toMillis();
        return Math.max(latency, 0L);
    }
}
