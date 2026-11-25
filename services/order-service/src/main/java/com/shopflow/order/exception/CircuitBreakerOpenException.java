package com.shopflow.order.exception;

/**
 * Le circuit breaker est ouvert : l'appel au catalogue est rejeté immédiatement (fail-fast) sans tentative réseau.
 * Hérite de CatalogUnavailableException pour être automatiquement traduit en HTTP 503 par ApiExceptionHandler.
 */
public class CircuitBreakerOpenException extends CatalogUnavailableException {

    public CircuitBreakerOpenException(String message, Throwable cause) {
        super(message, cause);
    }
}
