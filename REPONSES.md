# Réponses aux questions du TP

## Partie 0 - Mise en place

### Q0.1 Pourquoi interdit-on de pousser directement sur main ?

1. **Garder main toujours stable.** main est la version de référence : celle qu'on tague, qu'on livre et à partir de laquelle on crée les branches. Un push direct peut y mettre du code qui ne compile pas ou qui casse les tests, et tout le monde repart ensuite d'une base cassée.
2. **Travail en binôme : chaque modification est relue.** Avec une Pull Request obligatoire et une approbation, le binôme voit et teste le code avant la fusion. Il repère les erreurs, comprend ce qui a changé et reste au courant de tout le projet. Cela évite aussi qu'un push de l'un écrase ou casse le travail de l'autre sans prévenir.

En plus, l'historique de main ne contient que des fusions de PR : chaque changement est documenté (ce qui change, comment le tester) et facile à retrouver ou à annuler.

### Q0.2 La collection Bruno est faite de fichiers texte rangés dans le dépôt. Qu'est-ce que cela change pour la relecture d'une Pull Request ?

- **Les tests se relisent comme le code.** Une requête ajoutée ou modifiée apparaît dans le diff de la PR, ligne par ligne : on voit l'URL, le corps envoyé et le code attendu. Le relecteur vérifie que les tests correspondent bien à ce que la PR change (par exemple, une nouvelle route a sa requête et son test).
- **Le binôme peut tester sans rien installer de plus.** Il récupère la branche de la PR, ouvre le dossier `bruno/` et lance la collection : il a exactement les mêmes requêtes que l'auteur, dans la même version que le code. Pas d'export à s'envoyer, pas de collection qui diverge d'un poste à l'autre.
- **On repère une régression.** Si une PR modifie ou supprime un test existant (un code attendu qui passe de 201 à 200, par exemple), cela se voit dans le diff et doit être justifié.

## Partie 1 - Résilience avec Resilience4j

### Q1.1 Ici la connexion est refusée tout de suite, donc la réponse est rapide. Imaginez maintenant que le catalogue soit allumé mais très lent. Que se passe-t-il pour chaque commande, compte tenu du read-timeout de 2 secondes ? Et si 200 clients commandent en même temps ?

- **Pour chaque commande individuelle :** le thread de traitement d'order-service reste bloqué en attente pendant au moins 2 secondes (la durée du `read-timeout`) avant de recevoir une erreur ou de lever une exception.
- **Si 200 clients commandent simultanément :** le pool de threads de Tomcat (qui comporte par défaut 200 threads d'exécution) se retrouve entièrement saturé par des requêtes bloquées en attente du catalogue (*thread starvation*).
- **Conséquence :** `order-service` devient totalement indisponible pour tous les utilisateurs. Même les requêtes qui n'ont rien à voir avec le catalogue (comme consulter une commande existante `GET /api/v1/orders/{id}` ou les sondes de santé `/actuator/health`) sont bloquées ou rejetées. La défaillance de `catalog-service` se propage en cascade (*cascading failure*) à `order-service`.

### Q1.2 Expliquez avec vos mots l'image du disjoncteur électrique : que protège-t-il, et quand se réarme-t-il ?

- **Ce qu'il protège :** Dans une maison, un disjoncteur coupe le courant en cas de surcharge pour éviter que les fils ne brûlent ou ne provoquent un incendie. En microservices, le circuit breaker protège le service appelant (`order-service`) en coupant les appels vers le service défaillant dès qu'il détecte un taux d'échec critique (état **OPEN**). Les appels sont rejetés immédiatement (*fail-fast*), protégeant les ressources internes (threads, mémoire, connexions) d'order-service et évitant d'achever le catalogue sous une tempête d'appels inutiles.
- **Quand il se réarme :** Après avoir attendu une période définie (`wait-duration-in-open-state`, ici 10 secondes), le disjoncteur passe dans un état d'essai intermédiaire (**HALF_OPEN**). Il autorise un nombre limité d'appels d'essai (`permitted-number-of-calls-in-half-open-state`, ici 3). Si ces appels réussissent, le disjoncteur se réarme complètement (**CLOSED**) ; s'ils échouent à nouveau, il redécroche en **OPEN** pour un nouveau cycle de pause.

### Q1.3 Pourquoi pose-t-on l'annotation dans CatalogGateway et non dans OrderService.place ?

- **Séparation des responsabilités (SRP) :** `OrderService` contient la logique métier de création des commandes (validation des montants, enregistrement de la commande). `CatalogGateway` est le client d'infrastructure dont le rôle exclusif est de dialoguer avec le catalogue. Placer la résilience dans la passerelle maintient le code métier pur et sans couplage avec les aspects techniques de résilience.
- **Granularité et réutilisabilité :** Si une autre méthode métier a besoin d'appeler le catalogue plus tard, elle bénéficiera automatiquement de la même protection de circuit breaker. De plus, cela permet d'isoler précisément la méthode de repli (*fallback*) au niveau de l'appel HTTP sans masquer d'autres erreurs éventuelles de `place()`.

### Q1.4 L'annotation fonctionne parce que Spring entoure CatalogGateway d'un proxy. Que se passerait-il si getActiveProduct était appelée depuis une autre méthode de la même classe ?

- Les annotations Spring basées sur AOP (`@CircuitBreaker`, `@Retry`, `@Transactional`) fonctionnent grâce à un **proxy dynamique** qui enveloppe le bean Spring. Ce proxy n'intercepte que les appels provenant de l'**extérieur** du bean.
- Si `getActiveProduct` était appelée en interne par une autre méthode de la même classe (`this.getActiveProduct(...)`), l'appel contournerait complètement le proxy. En conséquence, l'aspect Resilience4j ne serait pas déclenché : ni le circuit breaker, ni le retry, ni le repli ne s'exécuteraient.

### Q1.5 Un client commande 10 fois de suite le produit 999. Le catalogue répond 404 à chaque fois. Doit-il ouvrir le circuit ? Quelle exception d'order-service mettez-vous dans ignore-exceptions (avec son nom complet, package compris) ?

- **Non, le circuit ne doit pas s'ouvrir.** Une erreur 404 indique que le client demande un produit inexistant. C'est une erreur client fonctionnelle (métier) et non une défaillance technique du catalogue (qui fonctionne très bien et a répondu normalement). Ouvrir le circuit pour cela pénaliserait à tort les clients qui commandent des produits valides.
- L'exception levée par `CatalogGateway` lorsqu'un produit est introuvable est :
  `com.shopflow.order.exception.InvalidOrderException`
- C'est donc cette exception que l'on configure dans `ignore-exceptions`.

### Q1.6 Retenter un GET sur un produit ne pose pas de problème. Pourquoi faudrait-il être beaucoup plus prudent avec un POST qui crée un paiement ?

- Une requête `GET` est par définition **idempotente** et sans effet de bord : lire une information plusieurs fois n'altère pas l'état du système.
- En revanche, un `POST` créant un paiement n'est **pas idempotent**. Si le premier appel a réussi côté service de paiement mais que la réponse a été perdue en raison d'une coupure réseau ou d'un timeout, un Retry naïf renverrait la requête et entraînerait un **double débit** bancaire du client. Pour pouvoir retenter un `POST` en toute sécurité, il faut impérativement que l'API supporte une clé d'idempotence (*Idempotency-Key*).

### Q1.7 Avec Resilience4j, le Retry entoure le circuit breaker. Combien d'appels le circuit voit-il passer pour une commande quand le catalogue est arrêté ?

- L'aspect `@Retry` ayant la priorité sur l'aspect `@CircuitBreaker`, il enveloppe ce dernier.
- Lorsque le catalogue est arrêté et que le circuit est fermé, chaque appel initial d'`OrderService` déclenche jusqu'à 3 tentatives au niveau du Retry. Chaque tentative traverse le proxy du CircuitBreaker.
- Pour une seule commande, le circuit breaker voit donc passer **3 appels** (1 appel initial + 2 retries).
- **Optimisation pour le circuit ouvert :** Afin d'éviter que le Retry ne réessaie inutilement quand le circuit est déjà ouvert (ce qui ajouterait 600 ms de latence), la méthode de repli lève une `CircuitBreakerOpenException` configurée dans les `ignore-exceptions` du Retry. Ainsi, dès que le circuit est `OPEN`, la réponse 503 est retournée immédiatement en 0 ms (*fail-fast* pur).

### Q1.8 Votre binôme demande un changement après votre push. Faut-il ouvrir une nouvelle Pull Request ? Que faites-vous concrètement ?

- **Non, il ne faut surtout pas ouvrir une nouvelle Pull Request.** Une Pull Request est dynamiquement liée à sa branche source (`feature/1-resilience`).
- **Concrètement :**
  1. On effectue les modifications demandées sur sa branche locale `feature/1-resilience`.
  2. On crée un nouveau commit explicite (par exemple : `git commit -m "fix(order): prise en compte de la relecture"`).
  3. On pousse sur la même branche distante (`git push origin feature/1-resilience`).
  4. La Pull Request existante sur GitHub se met à jour automatiquement avec les nouveaux commits et le diff actualisé.
