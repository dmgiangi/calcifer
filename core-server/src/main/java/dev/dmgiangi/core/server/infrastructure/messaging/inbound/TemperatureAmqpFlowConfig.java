package dev.dmgiangi.core.server.infrastructure.messaging.inbound;

import dev.dmgiangi.core.server.domain.temperature.Temperature;
import dev.dmgiangi.core.server.domain.temperature.event.TemperatureReceivedEvent;
import dev.dmgiangi.core.server.infrastructure.messaging.dlq.DeadLetterConfig;
import dev.dmgiangi.core.server.infrastructure.messaging.inbound.trasformer.AmqpToTemperatureTransformer;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
        return QueueBuilder.durable(QUEUE_NAME)
                .withArgument("x-dead-letter-exchange", DeadLetterConfig.DLX_EXCHANGE_NAME)
                .withArgument("x-dead-letter-routing-key", DeadLetterConfig.TEMPERATURE_DLQ)
                .build();
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
            ApplicationEventPublisher eventPublisher,
            @Qualifier("amqpRetryAdvice") Advice retryAdvice) {
        return IntegrationFlow
                .from(Amqp.inboundAdapter(connectionFactory, QUEUE_NAME)
                        .configureContainer(c -> c.adviceChain(retryAdvice)))
                .transform(temperatureTransformer)
                .handle(message -> {
                    final var temp = (Temperature) message.getPayload();
                    eventPublisher.publishEvent(new TemperatureReceivedEvent(message, temp));
                })
                .get();
    }
}