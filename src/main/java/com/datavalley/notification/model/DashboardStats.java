package com.datavalley.notification.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DashboardStats {
    private long totalUsers;
    private long totalNotifications;
}
