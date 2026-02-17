package com.datavalley.notification.service;

import com.datavalley.notification.model.Notification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class WebSocketDispatcher {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    public void dispatch(Notification notification) {
        if (notification.getRecipient() != null) {
            String target = "/topic/user/" + notification.getRecipient().getUsername();
            System.out.println("WebSocketDispatcher: Sending targeted message to " + target);
            messagingTemplate.convertAndSend(target, notification);
        } else {
            System.out.println("WebSocketDispatcher: Broadcasting message to /topic/public");
            messagingTemplate.convertAndSend("/topic/public", notification);
        }
    }

    public void dispatchBatch(java.util.List<Notification> notifications, String username) {
        if (username != null) {
            String target = "/topic/user/" + username;
            messagingTemplate.convertAndSend(target, notifications);
        } else {
            messagingTemplate.convertAndSend("/topic/public", notifications);
        }
    }
}
