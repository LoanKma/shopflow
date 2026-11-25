package com.shopflow.notification.listener;

import com.shopflow.notification.dto.NotificationRequest;
import com.shopflow.notification.event.OrderEvent;
import com.shopflow.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Envoie un e-mail au client à chaque événement de commande. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventsListener {

    private final NotificationService notifications;

    // Groupe "notification" : ce service reçoit tous les messages, indépendamment d'inventory-service
    @KafkaListener(topics = "order-events", groupId = "notification")
    public void on(OrderEvent event) {
        log.info("Événement {} reçu pour la commande {}", event.type(), event.orderId());
        notifications.send(toEmail(event));
    }

    static NotificationRequest toEmail(OrderEvent event) {
        String to = event.customerId() + "@shopflow.example";
        return switch (event.type()) {
            case CREATED -> NotificationRequest.builder()
                    .to(to)
                    .subject("Commande n° " + event.orderId() + " reçue")
                    .body("Merci pour votre commande de " + event.total() + " EUR. "
                            + "Elle est en cours de préparation.")
                    .build();
            case CANCELLED -> NotificationRequest.builder()
                    .to(to)
                    .subject("Commande n° " + event.orderId() + " annulée")
                    .body("Votre commande de " + event.total() + " EUR a bien été annulée.")
                    .build();
        };
    }
}
