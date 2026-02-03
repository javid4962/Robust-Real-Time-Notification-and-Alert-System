package com.datavalley.notification.controller;

import com.datavalley.notification.model.Notification;
import com.datavalley.notification.model.NotificationDTO;
import com.datavalley.notification.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
@CrossOrigin(origins = "*")
public class NotificationController {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private com.datavalley.notification.repository.UserRepository userRepository;

    @Autowired
    private com.datavalley.notification.repository.NotificationRepository notificationRepository;

    @PostMapping("/send")
    public ResponseEntity<Notification> sendNotification(@RequestBody NotificationDTO notificationDTO) {
        return ResponseEntity.ok(notificationService.sendNotification(notificationDTO));
    }

    @GetMapping
    public ResponseEntity<java.util.List<Notification>> getAllNotifications() {
        return ResponseEntity.ok(notificationService.getAllNotifications());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNotification(@PathVariable Long id) {
        notificationService.deleteNotification(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    public ResponseEntity<com.datavalley.notification.model.DashboardStats> getStats() {
        long userCount = userRepository.count();
        long notifCount = notificationService.getNotificationCount();
        return ResponseEntity.ok(new com.datavalley.notification.model.DashboardStats(userCount, notifCount));
    }

    @GetMapping("/user/{username}")
    public ResponseEntity<java.util.List<Notification>> getUserNotifications(@PathVariable String username) {
        // Get user
        com.datavalley.notification.model.User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new com.datavalley.notification.exception.UserNotFoundException("User not found: " + username));
        
        // Get all notifications for this user (targeted) + broadcast (null recipient)
        java.util.List<Notification> userNotifs = notificationRepository.findByRecipientId(user.getId());
        java.util.List<Notification> broadcasts = notificationRepository.findByRecipientIsNull();
        
        // Merge and sort
        java.util.List<Notification> all = new java.util.ArrayList<>();
        all.addAll(userNotifs);
        all.addAll(broadcasts);
        all.sort((a, b) -> b.getTimestamp().compareTo(a.getTimestamp())); // Newest first
        
        return ResponseEntity.ok(all);
    }
}
