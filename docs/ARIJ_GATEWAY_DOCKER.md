# TicketFlow — guide Gateway et Docker d'Arij

## Périmètre et audit

Intervention sur Gateway, tests HTTP, construction Docker des six modules, réseau Compose et configuration de démonstration.
Les entités, DTO, repositories et règles métier Customer/Event/Booking sont préservés. Aucun ajout de paiement,
Kafka, frontend, IA ou authentification. Java 21, Boot 4.0.8 et Cloud 2025.1.2 restent inchangés.

Écarts corrigés : routes explicites auparavant limitées au profil `tp1`, tests seulement sur le locator,
import Config Server en localhost non paramétrable pour Customer/Gateway, Compose limité à quatre services,
variable Git absente, démarrage sans attendre la disponibilité et README décrivant des fichiers inexistants.
Les six Dockerfiles existaient déjà ; ils utilisent maintenant le Maven Wrapper versionné (3.9.16).

## Architecture et routes

```text
Client → Gateway :8080 → instance Customer :8081 / Event :8082 / Booking :8083
                 ↓ résolution lb:// par Eureka :8761
Config Server :8888 → configuration centralisée fournie aux applications
Booking → Event par OpenFeign et Eureka (sans URL fixe en mode Compose)
```

Eureka est un annuaire : les requêtes métier ne transitent pas par le serveur Eureka.
La Gateway WebFlux/Netty utilise `spring.cloud.gateway.server.webflux` et Spring Cloud LoadBalancer.

| Route principale | Destination | Réécriture |
|---|---|---|
| `/api/customers/**` | `lb://customer-service` | Aucune |
| `/api/events/**` | `lb://event-service` | Aucune |
| `/api/bookings/**` | `lb://booking-service` | Aucune |

Les méthodes et contrats existants sont conservés (GET/POST/PUT/DELETE selon le service).
Les réponses métier, corps et codes HTTP sont propagés. `PreserveHostHeader` conserve le Host client pour les
`Location` générés par les contrôleurs ; `X-Served-By: api-gateway` identifie les réponses routées.
Cette configuration vise HTTP local ; une future terminaison TLS nécessitera une politique explicite de proxies de confiance.

Le locator est désactivé par défaut : les noms des services ne sont pas des préfixes publics.
Le profil facultatif `discovery-demo` ajoute seulement `/customer-service/api/customers/**`, retire le préfixe
`customer-service` et exclut les services internes ainsi que les endpoints d'administration de Customer.
Le profil `tp1` reste compatible avec les routes explicites devenues le défaut.

**Sécurité à venir :** aucune authentification JWT/Keycloak. Les routes exposent aussi les écritures, la publication
d'événements et les réservations ; `customerId` n'est pas une identité vérifiée. Ne pas exposer cette démonstration à Internet.
Config Server, Eureka, H2, `/api/config/message` et les Actuator des services n'ont pas de route Gateway.
La santé propre de la Gateway est disponible sur `/actuator/health`.

## Deux modes de configuration distincts

### Démonstration locale (par défaut dans Compose)

Config Server active explicitement `native`, sans Git ni secret. Le répertoire `infra/config-demo/` est monté en lecture seule.
Il fournit les paramètres communs et ceux de Customer, Event et Booking. Les trois bases restent **H2 en mémoire**,
indépendantes et recréées au démarrage : ne pas y mettre des données à conserver. Aucune migration PostgreSQL n'a été faite.
Gateway conserve ses routes dans `gateway-routes.yml`, également importé par les tests, pour éviter leur duplication.

### TP3 / Git

La configuration de production de Config Server conserve Git, la branche `main`, le clonage au démarrage
et `refresh-rate: 0`. L'URL du vrai dépôt est encore à fournir par l'équipe. Aucun dépôt fictif n'a été créé.
Ce dépôt devra fournir les propriétés requises : ports, Eureka, H2 ou la base convenue, `ticketflow.message` pour Customer,
et les éventuels réglages Feign/résilience. Ne pas y réactiver un locator non filtré ni remplacer les routes explicites involontairement.

```powershell
# Dans une session où CONFIG_GIT_URI contient l'URL réelle :
docker compose -f infra/docker-compose.yml -f infra/docker-compose.git.yml config --quiet
docker compose -f infra/docker-compose.yml -f infra/docker-compose.git.yml up -d --build --wait --wait-timeout 300
```

L'overlay active `git` à la place de `native` et exige `CONFIG_GIT_URI` non vide dès la validation Compose.
Sans ce dépôt, l'intégration Git n'est pas validable ; les tests et la démo native restent utilisables.
Le montage native reste présent mais n'est pas utilisé par le backend Git.

Pour le TP3, modifier une propriété distante ne pousse pas automatiquement sa valeur aux clients.
Le contrôleur Customer `/api/config/message` utilise `@RefreshScope` : après modification de la configuration,
appeler directement `POST http://localhost:8081/actuator/refresh`, puis relire ce contrôleur.
Le mode native de démonstration expose ce refresh localement ; en Git, le dépôt doit prévoir cette exposition.
Ne pas publier cet endpoint via la Gateway.
Le test réel a révélé une erreur de recréation du client Eureka lors du refresh (identifiant d'instance nul).
La démo fixe `eureka.client.refresh.enable=false` : les beans métier `@RefreshScope` continuent à être rafraîchis,
mais l'instance du client Eureka n'est pas recréée. Modifier son URL demande donc un redémarrage.
Compose impose aussi cette valeur en mode Git ; pour IntelliJ/Git, la prévoir dans le vrai dépôt TP3.
Voir la [propriété officielle Eureka](https://docs.spring.io/spring-cloud-netflix/reference/configprops.html).

## Variables

| Variable | Utilisation |
|---|---|
| `CONFIG_SERVER_URL` | Import Customer/Gateway ; également supporté par Event/Booking. Défaut IntelliJ `http://localhost:8888`. |
| `EUREKA_URL` | URL Eureka dans les configurations locales/démo. Défaut `http://localhost:8761/eureka/`. |
| `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` | Compose impose `http://discovery-server:8761/eureka/` avec priorité d'environnement. |
| `SERVER_PORT` | Compose impose 8080/8081/8082/8083/8761/8888 selon le service. |
| `CONFIG_REPO_LOCATION` | Répertoire native, défaut depuis la racine `file:./infra/config-demo/`, Compose `file:/config-repo/`. |
| `CONFIG_GIT_URI` | Obligatoire uniquement en mode Git ; aucune valeur réelle versionnée. |
| `EVENT_SERVICE_URL` | Démo native : override Feign optionnel, laisser vide pour Eureka. |
| `SPRING_PROFILES_ACTIVE` | `native` pour Config Server de démo ; `git` pour l'overlay TP3 ; `discovery-demo` facultatif sur Gateway. |

`.env.example` décrit la variable Git. Un éventuel `.env` personnel est ignoré par Git et exclu du contexte Docker.
Pour un fichier personnel, préciser `--env-file .env` ou son chemin exact sur les commandes Compose,
afin d'éviter toute ambiguïté de répertoire ; les exemples ci-dessus utilisent la variable de session PowerShell.
Ne jamais mettre de token dans une URL versionnée. L'authentification d'un dépôt privé reste à convenir séparément.

## Lancer Docker depuis PowerShell

Depuis `C:\Users\bouta\Projet_Spring_Cloud`, avec Docker Desktop démarré en mode conteneurs Linux :

```powershell
docker version
docker compose version
docker compose -f infra/docker-compose.yml config --quiet
docker compose -f infra/docker-compose.yml build
docker compose -f infra/docker-compose.yml up -d --wait --wait-timeout 300
docker compose -f infra/docker-compose.yml ps
powershell -NoProfile -File tools/Test-ArijDemo.ps1
```

Le projet Compose s'appelle `ticketflow-arij` ; aucun `container_name` global ne risque de heurter un autre projet.
Les images Java 21 sont construites avec `./mvnw`, puis exécutées sans root. `curl`, installé dans les images,
vérifie réellement `/actuator/health`. `depends_on: service_healthy` attend Config Server/Eureka puis les services métier.
La disponibilité HTTP ne prouve pas encore la convergence Eureka : le script attend la route Customer et contrôle le registre.
Les tags Java suivent les correctifs de Java 21 ; ils ne constituent pas un verrouillage par digest.

Ports publiés **uniquement sur 127.0.0.1** : Gateway 8080, Customer 8081 (TP2), Eureka 8761 et Config Server 8888 (TP3).
Event 8082 et Booking 8083 restent accessibles uniquement sur le réseau Compose. `localhost` à l'intérieur d'un
conteneur désigne ce conteneur : les imports et URL Eureka utilisent donc les noms DNS Compose.
Event/Booking utilisent un import Config Server obligatoire dans Compose afin qu'une erreur de configuration ne soit pas masquée.

```powershell
docker compose -f infra/docker-compose.yml logs --tail 80 api-gateway customer-service
docker compose -f infra/docker-compose.yml stop
```

`stop` conserve les conteneurs. Ne pas utiliser `down -v`, ni supprimer les volumes ou conteneurs d'autres projets.
Les données H2 disparaissent de toute façon à chaque arrêt de la JVM, indépendamment des volumes.

## Exécution depuis IntelliJ / Windows

Ne pas laisser Compose occuper les mêmes ports. Depuis la racine du dépôt, lancer chaque commande dans son terminal :

```powershell
$env:CONFIG_REPO_LOCATION = ([uri]((Resolve-Path infra/config-demo).Path + '\')).AbsoluteUri
.\mvnw.cmd -pl services/config-server spring-boot:run '-Dspring-boot.run.profiles=native'
.\mvnw.cmd -pl services/discovery-server spring-boot:run
.\mvnw.cmd -pl services/customer-service spring-boot:run
.\mvnw.cmd -pl services/event-service spring-boot:run
.\mvnw.cmd -pl services/booking-service spring-boot:run
.\mvnw.cmd -pl services/api-gateway spring-boot:run
```

Avec IntelliJ, utiliser le JDK 21 et le dossier racine comme répertoire de travail ; activer `native` seulement pour Config Server.
La variable ci-dessus fournit un chemin absolu : Maven `spring-boot:run` utilise normalement le répertoire du module,
alors que le défaut relatif du profil native suppose la racine du dépôt.
Sans ce profil, Config Server utilise toujours Git et exige `CONFIG_GIT_URI`. Aucune configuration personnelle IntelliJ n'est modifiée.

## Démonstration orale

1. Montrer `http://localhost:8761` et les quatre clients enregistrés : Customer, Event, Booking et Gateway.
2. Lire `http://localhost:8888/customer-service/default/main` : propriétés servies par Config Server native.
3. Appeler `http://localhost:8081/api/customers` directement puis `http://localhost:8080/api/customers`.
4. Créer un client via Gateway : montrer **201**, `Location` pointant sur Gateway et `X-Served-By`.
5. Suivre `Location` : **200** ; comparer à l'accès direct Customer.
6. Créer/publier un événement, réserver puis annuler : le script le fait avec les contrats existants, sans code métier modifié.
7. Montrer les **404** sur `/config-server/...`, `/eureka/apps`, `/actuator/gateway/routes` et `/api/config/message` via Gateway.
8. Expliquer `lb://customer-service` et le délai de propagation du registre. Sans instance, la Gateway renvoie **503**.

```powershell
$body = @{firstName='Arij'; lastName='Demo'; email=([guid]::NewGuid().ToString()+'@example.com')} | ConvertTo-Json
$created = Invoke-WebRequest -UseBasicParsing -Method Post -Uri http://localhost:8080/api/customers -ContentType application/json -Body $body
$created.StatusCode
$created.Headers
Invoke-RestMethod $created.Headers.Location
```

Le script ajoute des données de démonstration avec des identifiants uniques ; il ne supprime aucun travail existant.
Les tests Gateway automatisés utilisent SimpleDiscoveryClient et des backends HTTP simulés ; le scénario Compose
complète cette validation avec le vrai Eureka et les trois vraies applications métier.

## Validation et limites

La commande de référence reste `.\mvnw.cmd -B -ntp verify`. Les résultats exacts de cette intervention sont
consignés dans la PR ; journaux locaux dans `target/arij-validation/` (ignorés par Git).
Les six images ont été construites, les six conteneurs ont atteint l'état `healthy` et le scénario
`Test-ArijDemo.ps1` a réussi avec le vrai registre : Customer direct/Gateway (201, Location, 200),
Event (création/publication), Booking (création/lecture/annulation) et 404 sur les chemins internes.
Le refresh Customer et la lecture de son message après refresh renvoient également 200.
La validation Maven finale comporte **64 tests réussis** : Customer 8, Gateway 13, Discovery 2,
Config Server 1, Event 21 et Booking 19, sans échec ni erreur.
Les essais du mode Git resteront à effectuer lorsque l'équipe fournira le vrai dépôt TP3.
Event et Booking peuvent encore tenter un import Config Server optionnel pendant leurs tests Maven existants ;
ces avertissements ne signifient pas un échec. La résilience/gestion de concurrence existante est préservée.

À coordonner : politiques JWT/Keycloak et autorisations, base PostgreSQL avec Manar/Salma, contrats d'identité Customer,
futur Ticket Service, puis intégration frontend. Aucun de ces chantiers n'est entamé ici.

Références de configuration : [Gateway 5](https://docs.spring.io/spring-cloud-gateway/reference/configprops.html),
[backend native Config Server](https://docs.spring.io/spring-cloud-config/reference/server/environment-repository/file-system-backend.html).
