package com.datavalley.notification.service;

import com.datavalley.notification.config.RabbitMQConfig;
import com.datavalley.notification.model.Notification;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class NotificationConsumer {

    @Autowired
    private NotificationProcessor notificationProcessor;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_CRITICAL)
    public void consumeCritical(Notification notification) {
        notificationProcessor.process(notification, false, DeliveryPath.QUEUE_CONSUMER);
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_NORMAL)
    public void consumeNormal(Notification notification) {
        notificationProcessor.process(notification, true, DeliveryPath.QUEUE_CONSUMER);
    }
}
