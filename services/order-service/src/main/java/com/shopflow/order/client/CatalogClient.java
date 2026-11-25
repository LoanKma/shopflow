package com.shopflow.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Client HTTP déclaratif vers catalog-service.
 * OpenFeign génère l'implémentation : on écrit seulement l'interface.
 *
 * Pas d'URL : le nom catalog-service est résolu dans Eureka (avec répartition de charge).
 */
@FeignClient(name = "catalog-service")
public interface CatalogClient {

    @GetMapping("/api/v1/products/{id}")
    ProductView getProduct(@PathVariable("id") Long id);
}
