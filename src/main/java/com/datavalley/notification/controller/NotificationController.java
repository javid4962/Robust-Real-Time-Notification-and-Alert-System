package com.datavalley.notification.controller;

import com.datavalley.notification.model.Notification;
import com.datavalley.notification.model.NotificationDTO;
import com.datavalley.notification.model.MultiTargetNotificationRequest;
import com.datavalley.notification.model.MultiTargetNotificationResult;
import com.datavalley.notification.service.NotificationAnalyticsService;
import com.datavalley.notification.service.RabbitMQCircuitBreakerService;
import com.datavalley.notification.service.NotificationService;
import com.datavalley.notification.service.RabbitMQModeService;
import com.datavalley.notification.service.SystemDifferentiationService;
import com.datavalley.notification.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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

    @Autowired
    private RabbitMQModeService rabbitMQModeService;

    @Autowired
    private SystemDifferentiationService systemDifferentiationService;

    @Autowired
    private RabbitMQCircuitBreakerService rabbitMQCircuitBreakerService;

    @Autowired
    private NotificationAnalyticsService notificationAnalyticsService;

    @Autowired
    private AuthService authService;

    @PostMapping("/send")
    public ResponseEntity<Notification> sendNotification(@RequestBody NotificationDTO notificationDTO) {
        return ResponseEntity.ok(notificationService.sendNotification(notificationDTO));
    }

    @PostMapping("/send/multi-target")
    public ResponseEntity<MultiTargetNotificationResult> sendMultiTargetNotifications(
            @RequestBody MultiTargetNotificationRequest request) {
        return ResponseEntity.ok(notificationService.sendMultiTargetNotifications(request));
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

    @GetMapping("/rabbitmq/status")
    public ResponseEntity<Map<String, Object>> getRabbitMqStatus() {
        return ResponseEntity.ok(Map.of("enabled", rabbitMQModeService.isRabbitMqEnabled()));
    }

    @PutMapping("/rabbitmq/status")
    public ResponseEntity<Map<String, Object>> updateRabbitMqStatus(@RequestBody RabbitMqStatusRequest request) {
        boolean enabled = rabbitMQModeService.setRabbitMqEnabled(request.enabled());
        return ResponseEntity.ok(Map.of("enabled", enabled));
    }

    @GetMapping("/system/comparison")
    public ResponseEntity<Map<String, Object>> getSystemComparison() {
        boolean rabbitEnabled = rabbitMQModeService.isRabbitMqEnabled();

        Map<String, Object> presentSystem = Map.of(
                "title", "Present Existing System",
                "deliveryFlow", "API -> RabbitMQ -> Rabbit Consumers -> WebSocket",
                "rabbitMqUsage", "Mandatory",
                "whenRabbitMqUnavailable", "Queue publish/listener path is impacted and delivery can fail or stall.",
                "uiControl", "No runtime toggle in admin dashboard.",
                "directModeImpact", "When queue mode is turned off, low-priority notifications can be dropped under burst load."
        );

        Map<String, Object> proposedSystem = Map.of(
                "title", "Proposed System (Updated)",
                "deliveryFlow", "API -> RabbitMQ (concurrent consumers + low-latency batching) OR API -> Direct Processor -> WebSocket",
                "rabbitMqUsage", "Optional at runtime",
                "whenRabbitMqUnavailable", "Circuit-breaker skips slow publish attempts and fallback keeps real-time delivery active.",
                "uiControl", "Admin toggle enables/disables RabbitMQ live with tuning + historical analytics.",
                "accuracy", "Idempotent processing prevents duplicate sends on retry scenarios."
        );

        Map<String, Object> circuitState = rabbitMQCircuitBreakerService.snapshot();

        Map<String, Object> currentRuntime = Map.of(
                "rabbitMqEnabled", rabbitEnabled,
                "rabbitMqCircuitOpen", circuitState.get("circuitOpen"),
                "activePath", rabbitEnabled ? "QUEUE_MODE" : "DIRECT_MODE",
                "summary", rabbitEnabled
                        ? "Proposed system running with queue path ON (RabbitMQ + consumers)."
                        : "Proposed system running with queue path OFF (direct mode): high priority delivers first, lower priority can be partially dropped."
        );

        return ResponseEntity.ok(Map.of(
                "presentSystem", presentSystem,
                "proposedSystem", proposedSystem,
                "currentRuntime", currentRuntime,
                "practicalRealtime", systemDifferentiationService.snapshot(),
                "rabbitMqCircuit", circuitState
        ));
    }

    @GetMapping("/analytics/history")
    public ResponseEntity<Map<String, Object>> getHistoricalTrends(
            @RequestParam(defaultValue = "180") int minutes,
            @RequestParam(defaultValue = "5") int bucketMinutes) {
        return ResponseEntity.ok(notificationAnalyticsService.getDeliveryHistory(minutes, bucketMinutes));
    }

    @GetMapping("/user/{username}")
    public ResponseEntity<java.util.List<Notification>> getUserNotifications(@PathVariable String username) {
        // Get user
        com.datavalley.notification.model.User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new com.datavalley.notification.exception.UserNotFoundException("User not found: " + username));

        return ResponseEntity.ok(buildUserFeed(user));
    }

    @GetMapping("/user/me")
    public ResponseEntity<java.util.List<Notification>> getCurrentUserNotifications(
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        com.datavalley.notification.model.User user = authService.requireUserFromAuthorizationHeader(authorizationHeader);
        return ResponseEntity.ok(buildUserFeed(user));
    }

    private java.util.List<Notification> buildUserFeed(com.datavalley.notification.model.User user) {
        java.util.List<Notification> userNotifs = notificationRepository.findByRecipientId(user.getId());
        java.util.List<Notification> broadcasts = notificationRepository.findByRecipientIsNull();

        java.util.List<Notification> all = new java.util.ArrayList<>();
        all.addAll(userNotifs);
        all.addAll(broadcasts);
        all.sort((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()));
        return all;
    }

    public record RabbitMqStatusRequest(boolean enabled) {}
}
