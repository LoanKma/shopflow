package com.shopflow.inventory.listener;

import com.shopflow.inventory.event.OrderEvent;
import com.shopflow.inventory.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Met à jour le stock à chaque événement de commande. */
@Component
@RequiredArgsConstructor
public class OrderEventsListener {

    private final StockService stockService;

    // Groupe "inventory" : ce service reçoit tous les messages, indépendamment de notification-service
    @KafkaListener(topics = "order-events", groupId = "inventory")
    public void on(OrderEvent event) {
        stockService.apply(event);
    }
}
