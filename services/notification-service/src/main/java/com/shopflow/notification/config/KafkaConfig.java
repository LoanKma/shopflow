package com.shopflow.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.converter.JacksonJsonMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;

@Configuration
public class KafkaConfig {

    /**
     * Le message arrive en texte (StringDeserializer) : ce convertisseur le transforme en JSON
     * dans le type du paramètre de la méthode @KafkaListener (ici OrderEvent).
     * Spring Boot le branche tout seul sur les listeners.
     */
    @Bean
    RecordMessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
