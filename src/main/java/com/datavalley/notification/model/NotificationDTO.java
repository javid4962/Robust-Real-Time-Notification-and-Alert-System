package com.datavalley.notification.model;

import lombok.Data;

@Data
public class NotificationDTO {
    private String message;
    private Notification.Priority priority;
    private String username; // Recipient username, if null -> broadcast
}
