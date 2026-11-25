package com.shopflow.order.client;

import com.shopflow.order.exception.CatalogUnavailableException;
import com.shopflow.order.exception.InvalidOrderException;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Adaptateur entre order-service et catalog-service.
 * Il traduit les erreurs techniques HTTP (FeignException) en exceptions métier :
 * le service métier ne manipule jamais Feign directement.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogGateway {

    private final CatalogClient client;

    /**
     * Renvoie le produit s'il existe ET s'il est actif.
     *
     * @throws InvalidOrderException       produit inconnu (404 du catalogue) ou inactif
     * @throws CatalogUnavailableException catalogue arrêté, trop lent ou en erreur 5xx
     */
    @Retry(name = "catalog")
    @CircuitBreaker(name = "catalog", fallbackMethod = "fallbackGetActiveProduct")
    public ProductView getActiveProduct(Long productId) {
        ProductView product;
        try {
            product = client.getProduct(productId);
        } catch (FeignException.NotFound e) {
            throw new InvalidOrderException("Produit inconnu : " + productId);
        } catch (FeignException e) {
            // RetryableException (connexion refusée, timeout) hérite aussi de FeignException
            log.error("Appel à catalog-service impossible pour le produit {} : {}", productId, e.getMessage());
            throw new CatalogUnavailableException(e);
        }
        if (!product.active()) {
            throw new InvalidOrderException("Produit inactif : " + productId);
        }
        return product;
    }

    /**
     * Méthode de repli appelée quand le circuit est ouvert.
     * Prend uniquement CallNotPermittedException pour ne pas intercepter les exceptions métier comme InvalidOrderException.
     */
    public ProductView fallbackGetActiveProduct(Long productId, CallNotPermittedException e) {
        log.warn("Circuit ouvert lors de l'appel au catalogue pour le produit {} : {}", productId, e.getMessage());
        throw new CatalogUnavailableException("Le circuit est ouvert : catalog-service est temporairement indisponible", e);
    }
}
