package dev.dmgiangi.core.server.infrastructure.messaging.inbound;

import dev.dmgiangi.core.server.domain.temperature.Temperature;
import dev.dmgiangi.core.server.domain.temperature.event.TemperatureReceivedEvent;
import dev.dmgiangi.core.server.infrastructure.messaging.inbound.trasformer.AmqpToTemperatureTransformer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.amqp.dsl.Amqp;
import org.springframework.integration.dsl.IntegrationFlow;


@Slf4j
@Configuration
public class TemperatureAmqpFlowConfig {

    public static final String QUEUE_NAME = "mqtt.temperature.queue";
    public static final String EXCHANGE_NAME = "amq.topic";
    public static final String ROUTING_KEY = "*.*.*.*.temperature";

    @Bean
    public Queue temperatureQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    @Bean
    public TopicExchange mqttExchange() {
        // amq.topic è un exchange predefinito, lo dichiariamo come passive o lo referenziamo
        return new TopicExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public Binding binding(Queue temperatureQueue, TopicExchange mqttExchange) {
        return BindingBuilder.bind(temperatureQueue).to(mqttExchange).with(ROUTING_KEY);
    }

    @Bean
    public IntegrationFlow mqttTemperatureFlow(
        ConnectionFactory connectionFactory,
        AmqpToTemperatureTransformer temperatureTransformer,
        ApplicationEventPublisher eventPublisher) {
        return IntegrationFlow
            .from(Amqp.inboundAdapter(connectionFactory, QUEUE_NAME))
            .transform(temperatureTransformer)
            .handle(message -> {
                final var temp = (Temperature) message.getPayload();
                eventPublisher.publishEvent(new TemperatureReceivedEvent(message, temp));
            })
            .get();
    }
}