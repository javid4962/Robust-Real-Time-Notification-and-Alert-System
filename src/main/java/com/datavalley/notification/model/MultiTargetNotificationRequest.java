package com.datavalley.notification.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class MultiTargetNotificationRequest {

    private List<DeliveryItem> items = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeliveryItem {
        private String username;
        private String message;
        private String priorityMode;
        private Notification.Priority singlePriority;
        private Notification.Priority priority;
        private Integer count;
    }
}
