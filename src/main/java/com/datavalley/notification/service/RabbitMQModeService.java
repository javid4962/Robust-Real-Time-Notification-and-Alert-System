package com.datavalley.notification.service;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RabbitMQModeService {

    private final AtomicBoolean rabbitMqEnabled = new AtomicBoolean(true);

    @Autowired
    private RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;

    public boolean isRabbitMqEnabled() {
        return rabbitMqEnabled.get();
    }

    public synchronized boolean setRabbitMqEnabled(boolean enabled) {
        boolean previous = rabbitMqEnabled.getAndSet(enabled);
        if (previous == enabled) {
            return enabled;
        }

        rabbitListenerEndpointRegistry.getListenerContainers().forEach(container -> {
            if (enabled && !container.isRunning()) {
                container.start();
            } else if (!enabled && container.isRunning()) {
                container.stop();
            }
        });

        System.out.println("RabbitMQ mode changed to: " + (enabled ? "ENABLED" : "DISABLED"));
        return enabled;
    }
}
