package com.datavalley.notification.service;

import com.datavalley.notification.config.RabbitMQConfig;
import com.datavalley.notification.model.Notification;
import com.datavalley.notification.model.NotificationDTO;
import com.datavalley.notification.model.User;
import com.datavalley.notification.repository.NotificationRepository;
import com.datavalley.notification.repository.UserRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class NotificationService {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    // Simple In-Memory Rate Limiter (User -> Request Count)
    // In production, use Redis/Bucket4j
    private final java.util.concurrent.ConcurrentHashMap<String, Long> requestTimestamps = new java.util.concurrent.ConcurrentHashMap<>();

    public Notification sendNotification(NotificationDTO dto) {
        // Basic Rate Limit: 1 request per second globally for demo simplicity (or per user if we had auth context)
        // For this project, let's limit per client "username" if provided, else strictly limit broadcasts
        String key = dto.getUsername() != null ? dto.getUsername() : "GLOBAL_BROADCAST";
        
        long now = System.currentTimeMillis();
        long lastRequest = requestTimestamps.getOrDefault(key, 0L);
        
        if (now - lastRequest < 2000) { // 2 seconds delay required between notifications
            throw new RuntimeException("Rate limit exceeded! Please wait before sending another notification.");
        }
        requestTimestamps.put(key, now);

        Notification notification = new Notification();
        notification.setMessage(dto.getMessage());
        notification.setPriority(dto.getPriority());
        notification.setTimestamp(LocalDateTime.now());
        notification.setStatus(Notification.NotificationStatus.PENDING);

        if (dto.getUsername() != null && !dto.getUsername().isEmpty()) {
            User user = userRepository.findByUsername(dto.getUsername())
                    .orElseThrow(() -> new com.datavalley.notification.exception.UserNotFoundException("User not found: " + dto.getUsername()));
            notification.setRecipient(user);
        }

        // Save PENDING notification to DB
        Notification savedNotification = notificationRepository.save(notification);

        // Push to RabbitMQ based on Priority
        // Map CRITICAL and WARNING to the Critical Queue (High Priority)
        // Map NORMAL and INFO to the Normal Queue (Low Priority)
        String routingKey;
        switch (dto.getPriority()) {
            case CRITICAL:
            case WARNING:
                routingKey = RabbitMQConfig.ROUTING_KEY_CRITICAL;
                break;
            default:
                routingKey = RabbitMQConfig.ROUTING_KEY_NORMAL;
                break;
        }

        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, routingKey, savedNotification);
        
        System.out.println("Message sent to Queue: " + routingKey + " ID: " + savedNotification.getId());

        return savedNotification;
    }
    public java.util.List<Notification> getAllNotifications() {
        return notificationRepository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "timestamp"));
    }

    public void deleteNotification(Long id) {
        notificationRepository.deleteById(id);
    }

    public long getNotificationCount() {
        return notificationRepository.count();
    }
}
