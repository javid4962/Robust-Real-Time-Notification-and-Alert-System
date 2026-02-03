package com.datavalley.notification.service;

import com.datavalley.notification.config.RabbitMQConfig;
import com.datavalley.notification.model.Notification;
import com.datavalley.notification.repository.NotificationRepository;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class NotificationConsumer {

    @Autowired
    private WebSocketDispatcher webSocketDispatcher;

    @Autowired
    private NotificationRepository notificationRepository;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_CRITICAL)
    public void consumeCritical(Notification notification) {
        processNotification(notification);
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_NORMAL)
    public void consumeNormal(Notification notification) {
        processNotification(notification);
    }

    private void processNotification(Notification notification) {
        System.out.println("Processing Notification: " + notification.getId() + " Priority: " + notification.getPriority());

        // Check User Preferences
        if (notification.getRecipient() != null && !notification.getRecipient().isNotificationsEnabled()) {
            System.out.println("Skipping WebSocket dispatch for user " + notification.getRecipient().getUsername() + " (Notifications Disabled)");
            notification.setStatus(Notification.NotificationStatus.SENT); // Still mark as processed/sent in DB context
            notificationRepository.save(notification);
            return;
        }
        
        // Update status to SENT
        notification.setStatus(Notification.NotificationStatus.SENT);
        notificationRepository.save(notification);

        // Dispatch to WebSocket
        try {
            webSocketDispatcher.dispatch(notification);
        } catch (Exception e) {
            System.err.println("Failed to dispatch to WebSocket: " + e.getMessage());
            // Throwing exception will trigger RabbitMQ retry if configured
            throw new RuntimeException("WebSocket Dispatch Failed", e);
        }
    }
}
