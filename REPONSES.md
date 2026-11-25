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

## Partie 2 - Config Server, Eureka et Gateway

### Q2.1 Que change le mot optional: dans l'import ? Quel risque prend-on en le laissant ?

- **Sans `optional:`**, un service refuse de démarrer si le Config Server ne répond pas : il s'arrête dès le lancement avec une erreur.
- **Avec `optional:`**, le service démarre quand même. Il continue simplement sans la configuration du serveur, avec seulement ce qu'il a en local (nom, port, import).
- **Le risque :** le service démarre « à moitié configuré » sans que personne ne le remarque. Par exemple, order-service démarre sans ses timeouts Feign, sans les réglages du circuit breaker et sans l'adresse d'Eureka : il est UP, mais il ne se comporte pas comme prévu. L'erreur apparaît plus tard, de façon moins claire qu'un refus de démarrer. En production, on préfère souvent retirer `optional:` pour que la panne soit visible tout de suite.

### Q2.2 Changez le niveau de log de Feign dans config-repo/order-service.yml puis redémarrez order-service sans le recompiler. Qu'est-ce que cela démontre ?

Nous avons passé `logger-level` de `basic` à `full` dans `config-repo/order-service.yml`, puis redémarré order-service. Gradle affiche `compileJava UP-TO-DATE` : rien n'a été recompilé. Pourtant, chaque appel au catalogue affiche maintenant les en-têtes et le corps JSON de la réponse, et plus seulement la ligne `GET` et le code `200`.

Cela démontre que **la configuration est séparée du code** : on change le comportement d'un service en modifiant un fichier du `config-repo`, sans toucher au JAR. Le même JAR peut donc tourner avec des réglages différents (local, test, production), et un changement de réglage ne demande ni nouvelle compilation ni nouvelle livraison, seulement un redémarrage.

### Q2.3 L'attribut url a disparu. Qui transforme maintenant le nom catalog-service en adresse, et à quel moment ?

C'est **Spring Cloud LoadBalancer**, utilisé par OpenFeign, qui s'appuie sur le **client Eureka** d'order-service :

1. Au démarrage puis toutes les 30 s, le client Eureka d'order-service télécharge la liste des instances inscrites dans l'annuaire et la garde en cache.
2. **À chaque appel** de `CatalogClient`, Feign demande au LoadBalancer une instance de `catalog-service`. Le LoadBalancer en choisit une dans cette liste (par exemple `172.21.0.90:8081`) et remplace `http://catalog-service` par son adresse réelle.

La résolution se fait donc au moment de l'appel, et non au démarrage : si une instance apparaît ou disparaît, les appels suivants en tiennent compte, après la mise à jour du cache.

### Q2.4 Lancez une seconde instance du catalogue avec ./gradlew bootRun --args="--server.port=8091". Passez six commandes. Que montrent les logs des deux instances ?

Les deux instances apparaissent dans Eureka (`catalog-service:8081` et `catalog-service:8091`). Sur six commandes (produits 1 à 6), chaque instance reçoit **trois** appels, en alternance : 8081 reçoit les produits 1, 3 et 5, et 8091 les produits 2, 4 et 6. Le LoadBalancer répartit les appels à tour de rôle (*round robin*), sans qu'order-service connaisse les ports.

On a aussi observé un piège : après l'arrêt brutal de l'instance 8091, elle est restée inscrite comme UP dans Eureka (mode d'auto-préservation), et une requête sur deux échouait en 500 jusqu'à sa désinscription. Les clients ne voient une instance disparaître qu'avec un délai (renouvellements, cache de 30 s) : c'est une bonne raison d'avoir un circuit breaker et un retry côté appelant.

### Q2.5 Que signifie le préfixe lb:// ?

`lb` signifie *load balancer*. `lb://catalog-service` veut dire : « ce n'est pas une adresse, c'est le **nom d'un service** à chercher dans Eureka ; choisis l'une de ses instances, avec répartition de charge ». La Gateway n'a donc aucune adresse en dur, et si on lance plusieurs instances d'un service, elle répartit les requêtes entre elles, comme Feign à la question Q2.4.

### Q2.6 Citez trois responsabilités qu'on a intérêt à placer dans la Gateway plutôt que dans chaque service.

1. **La sécurité :** vérifier l'authentification (jeton JWT, par exemple) une seule fois à l'entrée, au lieu de recoder la vérification dans chaque service.
2. **Le CORS** (et plus largement les en-têtes HTTP communs) : un seul endroit dit quels sites web ont le droit d'appeler l'API.
3. **La limitation du débit** (*rate limiting*) : protéger toute la plateforme contre un client qui envoie trop de requêtes.

On peut aussi citer les logs d'accès et le traçage (un identifiant de requête ajouté à l'entrée), ou la réécriture des chemins et la gestion des versions d'API.

### Q2.7 Cette Pull Request touche beaucoup de fichiers. Aurait-il été préférable de faire trois Pull Requests ? Donnez un avantage et un inconvénient.

- **Avantage :** des PR plus petites (Config Server, puis Eureka, puis Gateway) sont plus faciles à relire et à tester. Le relecteur se concentre sur une seule brique, il repère mieux les erreurs, et chaque brique peut être fusionnée dès qu'elle est prête.
- **Inconvénient :** les trois briques dépendent les unes des autres. La Gateway a besoin d'Eureka pour `lb://`, et les deux lisent leur configuration dans le Config Server. Il faut donc les fusionner dans l'ordre et rebaser les branches suivantes après chaque fusion, et certaines PR ne se testent pas seules (une Gateway sans Eureka ne route rien). Cela demande aussi plus de cycles de relecture pour le binôme.

Ici, nous avons gardé une seule PR mais avec un commit par brique : le relecteur peut lire la PR commit par commit, ce qui donne une partie de l'avantage des petites PR sans leur inconvénient.

## Partie 3 - Communication asynchrone avec Kafka

### Q3.1 Pourquoi utiliser orderId comme clé de message ? Pensez à un CREATED suivi d'un CANCELLED pour la même commande.

Kafka envoie tous les messages qui ont la même clé dans la **même partition**, et l'ordre n'est garanti qu'à l'intérieur d'une partition. Avec `orderId` comme clé, le CREATED et le CANCELLED d'une même commande sont lus dans l'ordre où ils ont été publiés.

Sans clé (ou avec une clé aléatoire), les deux messages pourraient tomber dans deux partitions différentes, lues en parallèle. inventory-service pourrait alors recevoir le CANCELLED avant le CREATED : il remettrait en stock des articles jamais retirés, puis les retirerait, et le stock final serait faux.

Dans les logs de notre test, les messages de la commande 1 ont bien la clé `1` et se suivent dans la partition 0 (offsets 0 puis 1).

### Q3.2 Le même record est recopié dans trois services. Pourquoi ne pas en faire une bibliothèque partagée ?

Une bibliothèque partagée **recouplerait les services** que Kafka vient de découpler :

- Pour faire évoluer l'événement, il faudrait publier une nouvelle version de la bibliothèque, puis recompiler et redéployer les trois services en même temps. On perd l'indépendance de déploiement, qui est l'intérêt des microservices.
- Chaque consommateur n'a besoin que d'une partie de l'événement. notification-service n'utilise pas les lignes, par exemple, et pourrait les retirer de sa copie.
- Les services pourraient ne pas être tous écrits en Java.

Le vrai contrat entre services, c'est le **format JSON** du message, pas une classe Java. C'est pour cela que le producteur n'envoie pas le nom de sa classe (`spring.json.add.type.headers: false`) : chaque service désérialise le JSON dans sa propre classe. En échange, il faut garder les copies compatibles, par exemple en ajoutant des champs sans en supprimer.

### Q3.3 L'événement contient les lignes de la commande. Que devrait faire inventory-service s'il ne recevait que l'orderId ?

Il devrait **rappeler order-service** en synchrone (`GET /api/v1/orders/{id}`) pour connaître les produits et les quantités avant de modifier le stock.

Cela changerait beaucoup le couplage :

- inventory-service dépendrait de nouveau d'order-service : il devrait connaître son API, et order-service devrait être allumé au moment où le message est traité. On retrouverait les problèmes de la partie 1 (timeouts, circuit breaker, pannes en cascade).
- Chaque événement provoquerait un appel HTTP supplémentaire vers order-service.
- Il y aurait un risque d'incohérence : au moment du rappel, la commande a peut-être déjà changé (annulée entre-temps), et on lirait son état actuel plutôt que celui au moment de l'événement.

Un événement « complet » (*event-carried state transfer*) contient tout ce dont les consommateurs ont besoin : ils n'ont jamais à rappeler l'émetteur.

### Q3.4 Pourquoi ne pas appeler kafka.send directement dans place ? Décrivez le scénario qui poserait problème.

Parce que l'envoi à Kafka et l'enregistrement en base ne sont pas dans la même transaction. Si on envoie l'événement **avant** que la base ait validé, on peut annoncer une commande qui n'existe pas.

Scénario, avec `cancel()` qui est `@Transactional` :

1. `order.cancel()` passe la commande à CANCELLED en mémoire ;
2. `kafka.send(CANCELLED)` part tout de suite : inventory-service remet le stock, notification-service envoie l'e-mail « annulée » ;
3. au commit, la base échoue (conflit, contrainte, base indisponible) et la transaction est annulée (*rollback*).

Résultat : en base, la commande est toujours PENDING, mais le stock a été remis et le client a reçu un e-mail d'annulation. L'événement a annoncé quelque chose qui n'a jamais eu lieu, et on ne peut pas rappeler un message Kafka déjà envoyé.

Avec `@TransactionalEventListener(phase = AFTER_COMMIT)`, l'envoi n'a lieu qu'une fois le commit réussi : en cas de rollback, rien ne part.

### Q3.5 Si Kafka est arrêté au moment du commit, la commande existe mais l'événement est perdu. Quel patron de conception corrige ce défaut ?

Le patron **Transactional Outbox** (boîte d'envoi transactionnelle).

Dans la même transaction que la commande, on enregistre l'événement dans une table `outbox` de la base d'order-service : soit les deux sont enregistrés, soit aucun. Un processus séparé (une tâche planifiée, ou un outil comme Debezium qui lit le journal de la base) lit ensuite cette table et publie les événements dans Kafka, en réessayant tant que Kafka ne répond pas, puis les marque comme envoyés.

### Q3.6 inventory-service et notification-service lisent le même topic. Pourquoi reçoivent-ils chacun tous les messages ? Que se passerait-il avec le même groupId ?

Kafka distribue les messages **par groupe de consommateurs**. Chaque groupe a sa propre position de lecture (*offset*) dans le topic et reçoit tous les messages. Comme inventory-service est dans le groupe `inventory` et notification-service dans le groupe `notification`, ils lisent le topic chacun de leur côté, sans se gêner.

Avec le **même groupId**, les deux services formeraient un seul groupe. Kafka se **partagerait** alors les messages entre eux : chaque message ne serait lu que par l'un des deux. Notre topic n'a qu'une partition, donc un seul des deux services recevrait tout, et l'autre rien. Avec plusieurs partitions, chacun recevrait une partie des commandes : certaines commandes retireraient du stock sans e-mail, d'autres enverraient un e-mail sans toucher au stock.

Le même groupId sert au contraire à **répartir la charge** entre plusieurs instances d'un même service, par exemple deux instances d'inventory-service.

### Q3.7 Comparez avec la partie 1 : order-service a-t-il besoin qu'inventory-service soit allumé pour accepter une commande ?

**Non.** order-service publie l'événement dans Kafka et répond 201 tout de suite, sans connaître inventory-service ni notification-service. Si l'un d'eux est arrêté, les messages attendent dans le topic, et il les lit à son redémarrage, à partir de sa dernière position.

Nous l'avons vérifié : notification-service arrêté, trois commandes passées (201 à chaque fois), puis au redémarrage les trois e-mails sont partis.

À la partie 1, c'était l'inverse : l'appel au catalogue est synchrone, donc sans catalog-service une commande est refusée (503). Le catalogue reste synchrone parce qu'on a besoin de sa réponse (le prix) pour créer la commande. Le stock et les notifications, eux, peuvent être prévenus après coup. On parle de **découplage dans le temps**.

### Q3.8 Pourquoi --force-with-lease plutôt que --force ?

Après un `git rebase`, l'historique local ne suit plus celui de la branche distante : il faut forcer le push.

- `--force` écrase la branche distante **quoi qu'elle contienne**. Si le binôme a poussé un commit sur cette branche entre-temps (une correction après relecture, par exemple), ce commit est supprimé sans avertissement.
- `--force-with-lease` n'écrase la branche que si elle est **encore dans l'état que l'on a vu** lors du dernier `git fetch`. Si quelqu'un a poussé depuis, le push est refusé : on récupère d'abord son travail, puis on recommence.

`--force-with-lease` est donc un « force prudent » : il protège le travail du binôme.
