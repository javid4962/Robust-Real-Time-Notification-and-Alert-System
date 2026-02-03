package com.datavalley.notification.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "notification.system.exchange";
    public static final String QUEUE_CRITICAL = "notification.critical";
    public static final String QUEUE_NORMAL = "notification.normal";
    public static final String ROUTING_KEY_CRITICAL = "notification.critical";
    public static final String ROUTING_KEY_NORMAL = "notification.normal";

    @Bean
    public TopicExchange exchange() {
        return new TopicExchange(EXCHANGE_NAME);
    }

    @Bean
    public Queue criticalQueue() {
        return new Queue(QUEUE_CRITICAL);
    }

    @Bean
    public Queue normalQueue() {
        return new Queue(QUEUE_NORMAL);
    }

    @Bean
    public Binding bindingCritical(Queue criticalQueue, TopicExchange exchange) {
        return BindingBuilder.bind(criticalQueue).to(exchange).with(ROUTING_KEY_CRITICAL);
    }

    @Bean
    public Binding bindingNormal(Queue normalQueue, TopicExchange exchange) {
        return BindingBuilder.bind(normalQueue).to(exchange).with(ROUTING_KEY_NORMAL);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public AmqpTemplate template(ConnectionFactory connectionFactory) {
        final RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter());
        return rabbitTemplate;
    }
}
