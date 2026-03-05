package com.datavalley.notification.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MultiTargetNotificationResult {

    private int requested;
    private int sent;
    private int failed;
    private List<ItemResult> itemResults = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemResult {
        private String username;
        private String message;
        private String priorityMode;
        private String priorityDetails;
        private int requested;
        private int sent;
        private int failed;
        private String lastError;
    }
}
