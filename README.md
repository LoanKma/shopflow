# ShopFlow

Les cinq microservices de ShopFlow. Une seule communication existe entre eux : **order-service appelle catalog-service en synchrone avec OpenFeign**, protégé par un circuit breaker Resilience4j (partie 1).

Depuis la partie 2, la configuration est centralisée dans un **Config Server**, les services se trouvent par leur nom grâce à **Eureka**, et le client passe par une seule porte d'entrée, la **Gateway** (port 8080).

Depuis la partie 3, order-service publie un événement **Kafka** à chaque commande créée ou annulée : inventory-service met le stock à jour et notification-service prévient le client, sans qu'order-service les connaisse. Docker arrive à la partie 4.

Pile technique : Java 25, Spring Boot 4.2.0, Spring Cloud 2026.0.0 (OpenFeign, Config, Eureka, Gateway), Resilience4j 2.4.0, Gradle Groovy 9.9.2, Lombok, springdoc-openapi 3.2.1, base H2 en mémoire.

## Les services

| Service | Port | Base | Rôle |
| --- | --- | --- | --- |
| catalog-service | 8081 | H2 `catalog_db` | Produits (10 produits chargés au démarrage) |
| order-service | 8082 | H2 `order_db` | Commandes, vérifie chaque produit auprès du catalogue |
| inventory-service | 8083 | H2 `inventory_db` | Stock de chaque produit |
| payment-service | 8084 | H2 `payment_db` | Paiement simulé, refusé au-delà de 1 000 EUR |
| notification-service | 8085 | aucune | E-mails écrits dans les logs |
| config-server | 8888 | — | Sert la configuration de `config-repo/` |
| discovery (Eureka) | 8761 | — | Annuaire des services, tableau de bord sur http://localhost:8761 |
| gateway | 8080 | — | Porte d'entrée unique, une route par service |

```
shopflow/
├── config-repo/                application.yml (commun), <service>.yml, gateway.yml (routes)
├── infrastructure/
│   ├── config-server/
│   ├── discovery/
│   └── gateway/
├── bruno/                      collection Bruno (environnements local et gateway)
└── services/
    ├── catalog-service/
    ├── order-service/          client/CatalogClient.java     interface OpenFeign
    │                           client/CatalogGateway.java    traduction des erreurs Feign
    │                           client/ProductView.java       vue du produit côté commande
    ├── inventory-service/
    ├── payment-service/
    └── notification-service/
```

Chaque service et chaque brique d'infrastructure est un projet Gradle autonome qui a son propre wrapper.

## Démarrer

Prérequis : un JDK 25 et Docker Desktop (pour Kafka). Chaque base H2 est créée en mémoire au démarrage du service.

Ouvrir un terminal par projet, **dans cet ordre** :

```bash
# 0. Kafka et Kafka UI (depuis la racine) : http://localhost:8090
docker compose up -d

# 1. L'infrastructure : le Config Server d'abord (les autres y lisent leur configuration)
cd infrastructure/config-server && ./gradlew bootRun
cd infrastructure/discovery && ./gradlew bootRun

# 2. Les services
cd services/catalog-service && ./gradlew bootRun
cd services/order-service && ./gradlew bootRun
cd services/inventory-service && ./gradlew bootRun
cd services/payment-service && ./gradlew bootRun
cd services/notification-service && ./gradlew bootRun

# 3. La Gateway
cd infrastructure/gateway && ./gradlew bootRun
```

Le config-server doit être lancé depuis son dossier : il lit `../../config-repo`. Les services démarrent aussi sans lui (import `optional:`), mais sans leur configuration (base, Feign, circuit breaker, adresse d'Eureka).

Après le démarrage, comptez environ 30 secondes pour que les services se voient dans Eureka : avant, la Gateway peut répondre 503 et une commande « Catalogue indisponible ».

Toutes les API sont accessibles par la Gateway sur `http://localhost:8080` (`/api/v1/products/**`, `/orders/**`, `/stock/**`, `/payments/**`, `/notifications/**`). Les endpoints `/actuator` ne sont pas routés : on les appelle directement sur le port du service.

Pour chaque service :

- Swagger UI : `http://localhost:<port>/swagger-ui.html`
- Santé : `http://localhost:<port>/actuator/health`
- Console H2 (sauf notification-service) : `http://localhost:<port>/h2-console`, avec la JDBC URL `jdbc:h2:mem:<base>`, l'utilisateur `sa` et sans mot de passe
- Tests : `./gradlew test`

Les données sont perdues à chaque arrêt du service. Pour utiliser un PostgreSQL installé sur la machine, il suffit de modifier les trois lignes `datasource` de `config-repo/<service>.yml` : le modèle est en commentaire dans le fichier, et le pilote PostgreSQL est déjà dans `build.gradle`.

## La communication synchrone order-service vers catalog-service

```
client ──POST /api/v1/orders──> order-service ──GET /api/v1/products/{id}──> catalog-service
                                     │ (OpenFeign, attend la réponse)
                                     └── copie le nom et le prix dans la ligne de commande
```

- Pas d'adresse en dur : `@FeignClient(name = "catalog-service")` est résolu dans Eureka au moment de l'appel. Si plusieurs instances du catalogue tournent, les appels sont répartis entre elles.
- Les délais d'attente sont limités (`config-repo/order-service.yml`) : 1 s pour se connecter, 2 s pour recevoir la réponse.
- `CatalogGateway` traduit les erreurs :
  - produit inconnu (404 du catalogue) : la commande est refusée en 422 ;
  - produit inactif : 422 ;
  - catalogue arrêté ou en erreur : 503 « Catalogue indisponible ».

## Contrat des API

### catalog-service (8081)

| Méthode | URL | Succès | Erreurs |
| --- | --- | --- | --- |
| GET | /api/v1/products?page=0&size=20&sort=name | 200 | |
| GET | /api/v1/products/{id} | 200 | 404 |
| POST | /api/v1/products | 201 + Location | 400, 409 (SKU déjà utilisé) |
| PUT | /api/v1/products/{id} | 200 | 400, 404, 422 (SKU modifié) |
| DELETE | /api/v1/products/{id} | 204 (désactivation) | 404 |

### order-service (8082)

| Méthode | URL | Succès | Erreurs |
| --- | --- | --- | --- |
| POST | /api/v1/orders | 201 + Location, statut PENDING | 400, 422 (produit inconnu ou inactif), 503 (catalogue indisponible) |
| GET | /api/v1/orders/{id} | 200 | 404 |
| GET | /api/v1/orders?customerId=c-1 | 200, paginé | 400 sans customerId |
| POST | /api/v1/orders/{id}/cancel | 200, statut CANCELLED | 404, 409 (déjà annulée) |

### inventory-service (8083)

| Méthode | URL | Succès | Erreurs |
| --- | --- | --- | --- |
| GET | /api/v1/stock | 200 | |
| GET | /api/v1/stock/{productId} | 200 | 404 |
| PUT | /api/v1/stock/{productId} | 200 (crée la ligne si besoin) | 400 |

Stock initial : 10 pour chaque produit, sauf le produit 3 (5), le produit 8 (2) et le produit 10 (3).

### payment-service (8084)

| Méthode | URL | Succès | Erreurs |
| --- | --- | --- | --- |
| POST | /api/v1/payments | 201, statut ACCEPTED ou REFUSED | 400, 409 (commande déjà payée) |
| GET | /api/v1/payments/{orderId} | 200 | 404 |

### notification-service (8085)

| Méthode | URL | Succès | Erreurs |
| --- | --- | --- | --- |
| POST | /api/v1/notifications | 202, e-mail écrit dans les logs | 400 |
| GET | /api/v1/notifications | 200, les 100 derniers e-mails | |

## Tester à la main

```bash
# Catalogue
curl -i http://localhost:8081/api/v1/products/1

# Commande valide : 2 claviers + 1 souris, total 229.70
curl -i -X POST http://localhost:8082/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"c-1","lines":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}]}'

# Produit inconnu : 422
curl -i -X POST http://localhost:8082/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"c-1","lines":[{"productId":999,"quantity":1}]}'

# Stock
curl -i http://localhost:8083/api/v1/stock/8
curl -i -X PUT http://localhost:8083/api/v1/stock/8 \
  -H "Content-Type: application/json" -d '{"quantity":20}'

# Paiement accepté, puis refusé (au-delà de 1 000 EUR)
curl -i -X POST http://localhost:8084/api/v1/payments \
  -H "Content-Type: application/json" \
  -d '{"orderId":1,"customerId":"c-1","amount":229.70}'
curl -i -X POST http://localhost:8084/api/v1/payments \
  -H "Content-Type: application/json" \
  -d '{"orderId":2,"customerId":"c-1","amount":1199.00}'

# Notification
curl -i -X POST http://localhost:8085/api/v1/notifications \
  -H "Content-Type: application/json" \
  -d '{"to":"c-1@shopflow.example","subject":"Commande n° 1 reçue","body":"Merci pour votre commande."}'
curl -i http://localhost:8085/api/v1/notifications
```

Pour faire tomber le catalogue, arrêter catalog-service (Ctrl+C dans son terminal), puis relancer la commande valide. On obtient alors un 503 immédiatement, alors qu'une commande existante se relit toujours (`GET /api/v1/orders/1`).

## Les événements Kafka

```
order-service ──OrderEvent (CREATED, CANCELLED), clé = orderId──> topic order-events
                                                                   ├─> inventory-service    (groupe inventory)    retire ou remet le stock
                                                                   └─> notification-service (groupe notification) e-mail au client dans les logs
```

- **Publier après le commit :** `OrderService` publie un événement Spring, et `OrderEventPublisher` ne l'envoie à Kafka qu'une fois la transaction validée (`@TransactionalEventListener(phase = AFTER_COMMIT)`).
- **Consommer une seule fois :** Kafka peut livrer un message deux fois. inventory-service note chaque `eventId` traité dans la table `processed_event`, dans la même transaction que le stock : un doublon est ignoré.
- **Stock insuffisant :** si une ligne manque de stock, aucune ligne n'est modifiée et un avertissement est écrit dans les logs. La commande reste acceptée côté order-service (la Saga traitera ce cas plus tard). Si cette commande est annulée ensuite, rien n'est remis en stock.
- Kafka UI (http://localhost:8090) affiche les messages du topic `order-events` et permet d'en republier un.

## Ce qui n'est pas encore branché

payment-service fonctionne seul : order-service ne l'appelle pas, et une commande reste au statut PENDING jusqu'à ce que le client l'annule. Un stock insuffisant n'annule pas non plus la commande : c'est le rôle de la Saga, dans un prochain TP.
