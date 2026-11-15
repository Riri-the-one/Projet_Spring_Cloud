# TicketFlow — Keycloak, JWT et Gateway : phase 1

> Ce document conserve le périmètre et les résultats de phase 1. La [phase 2A Customer](ARIJ_CUSTOMER_IDENTITY.md)
> ajoute maintenant la validation JWT directe et la propriété des profils Customer. Les limites décrites
> pour Event et Booking restent applicables. Les scripts de démonstration ont été adaptés à Customer sécurisé.

Cette phase protège l'entrée Gateway. **TicketFlow n'est pas encore entièrement sécurisé** :
Customer, Event et Booking ne valident pas encore les JWT et ne contrôlent pas la propriété.
Leurs entités, DTO, repositories et méthodes métier sont inchangés.

## Présenter les concepts

Keycloak centralise la connexion, les comptes et les rôles. TicketFlow ne stocke pas les mots de passe
des utilisateurs dans ses profils Customer et ne développe pas son propre serveur de connexion.

- **OAuth2** définit comment une application obtient une autorisation d'accès à une API.
- **OIDC** ajoute l'authentification de l'utilisateur à OAuth2, notamment avec un ID token.
- **JWT** est un format de jeton signé contenant des informations vérifiables. Lire son contenu
  ne prouve pas son authenticité : il faut vérifier sa signature et ses claims.

Le navigateur se connecte à Keycloak, reçoit un code, puis l'échange avec une preuve PKCE S256.
Il utilise l'**access token**, jamais l'ID token, dans `Authorization: Bearer …` pour appeler Gateway.
Gateway vérifie ce jeton, applique les rôles et transmet l'access token au service choisi via Eureka.
Les services devront refaire ces validations et vérifier les propriétaires en phase 2.

## Versions et choix

- Java 21, Spring Boot **4.0.8**, Spring Cloud **2025.1.2** conservés.
- Starter officiel `spring-boot-starter-security-oauth2-resource-server`, version gérée par Boot.
- Spring Security **7.0.7**, résolu par le parent Boot, et Nimbus JOSE JWT ; aucun adaptateur
  Keycloak historique ni filtre JWT artisanal.
- Image officielle **`quay.io/keycloak/keycloak:26.8.2`**, version fixée, distribution JVM indépendante
  des versions Spring. Communication par les protocoles OIDC/JWKS standards.
- Keycloak `start-dev`, base H2 persistée dans un volume dédié : démonstration locale uniquement.

Références : [image et distribution officielles](https://www.keycloak.org/downloads),
[starter Boot 4](https://docs.spring.io/spring-boot/4.0/reference/using/build-systems.html),
[JWT réactifs Spring Security](https://docs.spring.io/spring-security/reference/7.0/reactive/oauth2/resource-server/jwt.html),
[adresses frontend/backend Keycloak](https://www.keycloak.org/server/hostname).

## Realm et comptes fictifs

L'export reproductible `infra/keycloak/ticketflow-realm.json` est chargé au premier démarrage.

| Élément | Valeur |
|---|---|
| Realm | `ticketflow` |
| Client public | `ticketflow-web` |
| Flux | Authorization Code + PKCE S256 obligatoire |
| Audience d'accès | `ticketflow-api` |
| Redirections locales exactes | `http://localhost:4200/`, `http://localhost:4200/security-demo` |
| Origine web | `http://localhost:4200` |
| Durée d'access token | 300 secondes |
| Signature | RS256 |
| Password grant / Implicit / Service accounts | Désactivés |

| Compte fictif | Mot de passe de démonstration public | Rôle |
|---|---|---|
| `demo-customer` | `Customer-demo-only-2026!` | CUSTOMER |
| `demo-organizer` | `Organizer-demo-only-2026!` | ORGANIZER |
| `demo-admin` | `Admin-demo-only-2026!` | ADMIN |

Ces mots de passe sont des fixtures publiques locales, pas des secrets réels. Les e-mails utilisent
`example.invalid`. Ne réutiliser aucun de ces comptes en production. ADMIN est un rôle applicatif,
pas un administrateur Keycloak. Aucun compte administrateur Keycloak n'est provisionné par Compose.
Pour voir l'espace personnel : `http://localhost:8180/realms/ticketflow/account/`.

Les rôles sont indépendants, sans héritage. Le mapper n'expose que les rôles applicatifs autorisés
au client. Spring convertit `realm_access.roles` en `ROLE_CUSTOMER`, `ROLE_ORGANIZER`, `ROLE_ADMIN`.
Le scope client `basic` est explicitement conservé : Keycloak y fournit le mapper de `sub`.
Les en-têtes `X-Roles`, `X-User-Id`, `X-Customer-Id` ne confèrent aucun droit et sont retirés avant routage.

## Validation du jeton

Le décodeur Nimbus réactif utilise les clés publiques du JWKS configuré et accepte uniquement RS256.
Il vérifie l'issuer exact, l'audience attendue, l'expiration, `nbf` lorsqu'il est présent et un `sub`
non vide. `exp`, `iss`, `aud` et `sub` sont obligatoires ; `nbf` est optionnel selon JWT mais ne peut
pas annoncer une validité future au-delà de la tolérance d'horloge de 30 secondes.
Un rôle seul ne suffit jamais. Une indisponibilité JWKS sans clé en cache ne permet pas de contourner
la validation ; les clés déjà en cache restent utilisables dans leur durée normale.

| Configuration | Exécution Windows | Conteneur Gateway |
|---|---|---|
| `KEYCLOAK_ISSUER_URI` | `http://localhost:8180/realms/ticketflow` | Même issuer public |
| `KEYCLOAK_JWK_SET_URI` | `http://localhost:8180/realms/ticketflow/protocol/openid-connect/certs` | `http://keycloak:8080/realms/ticketflow/protocol/openid-connect/certs` |

Dans Compose, `KEYCLOAK_PUBLIC_URL` définit le hostname public de Keycloak et construit l'issuer attendu
par Gateway. Ne pas remplacer l'issuer par `http://keycloak:8080` : ce serait différent du `iss` réel.
Les applications Windows peuvent définir directement les deux variables Spring ci-dessus.
`TICKETFLOW_JWT_AUDIENCE` et `TICKETFLOW_CORS_ORIGINS` configurent l'audience et les origines autorisées.
L'export local contient volontairement les redirections Angular locales ; changer d'origine exige
de coordonner aussi l'export du client, pas uniquement CORS.

## Routes effectivement protégées

Les trois routes `lb://customer-service`, `lb://event-service`, `lb://booking-service` sont conservées.

| Méthode et chemin | Autorisation Gateway phase 1 |
|---|---|
| `GET /actuator/health` | Public, sans composants ni détails |
| `GET /api/events` | Public ; Event filtre déjà la liste publiée |
| `GET /api/events/{id}` | CUSTOMER, ORGANIZER ou ADMIN ; visibilité métier encore à réaliser |
| `POST /api/events` | ORGANIZER ou ADMIN |
| `PUT /api/events/{id}` | ORGANIZER ou ADMIN |
| `POST /api/events/{id}/publish` ou `/close` | ORGANIZER ou ADMIN |
| `GET /api/customers` | ADMIN |
| `POST /api/customers` | CUSTOMER ou ADMIN |
| `GET`, `PUT /api/customers/{id}` | CUSTOMER ou ADMIN, propriété non contrôlée |
| `DELETE /api/customers/{id}` | ADMIN |
| `GET /api/customers/whoami` | ADMIN ; identifie l'instance, pas l'utilisateur |
| `GET /api/bookings`, `GET /api/bookings/{id}` | CUSTOMER ou ADMIN, propriété non contrôlée |
| `POST /api/bookings`, `POST /api/bookings/{id}/cancel` | CUSTOMER ou ADMIN, propriété non contrôlée |
| Autre chemin ou méthode | Refus par défaut, même pour ADMIN |

Avec un jeton valide, les chemins internes (`/eureka/apps`, `/config-server/...`, `/api/config/message`,
`/actuator/gateway/routes`, `/admin/...`) renvoient **403** et ne sont pas routés. Sans jeton, ils peuvent
renvoyer **401**. Les anciens 404 de routage sont remplacés par le refus de sécurité en amont.
Les 404 métier restent propagées pour une requête autorisée.

Un prévol CORS valide sur `/api/**`, depuis l'origine autorisée et pour une méthode/en-têtes autorisés,
passe sans JWT. Une origine étrangère est refusée. L'API n'utilise aucune session ni cookie
d'authentification ; CSRF est désactivé pour cette API Bearer uniquement.

Le profil pédagogique `discovery-demo` est conservé : son alias Customer applique les mêmes rôles
que `/api/customers`. Il ne publie ni Config Server ni Eureka. `tp1` conserve aussi la sécurité.
Aucun profil ne désactive Spring Security.

## Démarrage PowerShell

Prérequis : JDK 21, Docker Desktop en conteneurs Linux, Node.js 22 ou plus pour le test OIDC automatisé.
Depuis la racine du dépôt :

```powershell
.\mvnw.cmd -B -ntp verify
docker compose -f infra/docker-compose.yml config --quiet
docker compose -f infra/docker-compose.yml up -d --build --wait --wait-timeout 300
docker compose -f infra/docker-compose.yml ps
Invoke-RestMethod http://localhost:8180/realms/ticketflow/.well-known/openid-configuration
node tools/Test-KeycloakIntegration.mjs
```

Les sept conteneurs doivent être `healthy` : les six microservices et Keycloak. Le délai de convergence
d'Eureka peut encore ajouter 30 à 90 secondes au démarrage des requêtes métier.
Pour le mode Config Server Git : définir la vraie `CONFIG_GIT_URI`, puis utiliser les deux fichiers
`-f infra/docker-compose.yml -f infra/docker-compose.git.yml`. L'overlay Git ne désactive pas JWT.
Ne jamais enregistrer des identifiants Git dans cette URL versionnée.

Ports hôte publiés uniquement sur `127.0.0.1` : Gateway 8080, Keycloak 8180, Customer 8081,
Config Server 8888 et Eureka 8761. Event et Booking restent sans port hôte.
Le port de santé Keycloak 9000 reste interne. Les interfaces d'administration Keycloak restent
sur le même port loopback 8180 et ne sont pas exposées par Gateway.

Arrêter sans supprimer de volumes :

```powershell
docker compose -f infra/docker-compose.yml stop
```

Le démarrage n'écrase pas un realm déjà présent dans le volume : Keycloak utilise `IGNORE_EXISTING`.
Après évolution de l'export, prévoir une migration explicite du realm ou un nouveau volume de test
isolé ; ne pas supprimer les volumes existants pour faire passer une démonstration.
L'export versionné sert de source reproductible depuis un volume neuf ; les clés privées générées,
sessions et données réelles du volume ne doivent jamais être exportées dans Git.

## Démonstration devant le professeur

1. Montrer les sept conteneurs et l'issuer dans le document OIDC.
2. Exécuter `curl.exe -i http://localhost:8080/api/events` : **200**, catalogue public.
3. Exécuter `curl.exe -i http://localhost:8080/api/bookings` : **401**, authentification requise.
4. Lancer `node tools/Test-KeycloakIntegration.mjs` : connexion des trois comptes par Code + PKCE,
   puis appels avec de vrais access tokens. CUSTOMER sur la liste clients : **403** ; ADMIN : **200** ;
   ORGANIZER sur Booking : **403** ; CUSTOMER sur Booking : **200**.
5. Le même script exécute `Test-ArijDemo.ps1` avec le jeton ADMIN en mémoire : registre Eureka,
   Config Server, refresh, création Customer, création/publication Event, réservation puis annulation.
6. Montrer les refus des routes internes et expliquer les limites de propriété de cette phase.

Le script Node pilote le formulaire HTML réel de Keycloak uniquement sur loopback, vérifie `state`,
utilise un challenge PKCE et vérifie que les rôles/audience/issuer attendus sont émis. Il ne suit pas
la redirection vers Angular : le code est échangé directement par le client de test. Il ne journalise
ni access token ni cookies. Ce pilote de formulaire sert aux tests locaux, pas au futur frontend.
Le script vérifie aussi que le grant `password` est refusé. La signature des jetons obtenus est
vérifiée par le Gateway lors des appels ; le décodage dans Node vérifie uniquement la fixture.

Pour utiliser séparément l'ancien script pédagogique, définir `TICKETFLOW_DEMO_ACCESS_TOKEN` avec
un access token ADMIN obtenu par OIDC, puis lancer `powershell -NoProfile -File tools/Test-ArijDemo.ps1`.
Sans token il s'arrête avec une explication, sans basculer en mode non sécurisé.
Ce script ajoute des données fictives ; il ne supprime pas de données existantes.

## Tests automatisés

Les tests Maven ne nécessitent ni Keycloak ni Docker. Un serveur JWKS local publie une clé publique
de test ; les jetons sont réellement signés avec une clé RSA contrôlée. Le décodeur de production
reste actif et utilise ce JWKS. Les cas incluent expiration, mauvaise signature, issuer, audience,
`nbf` futur, absence de `sub`/`exp`, algorithme interdit, rôles insuffisants, méthodes non autorisées,
en-têtes falsifiés, transmission du Bearer, CORS et chemins internes. Les tests antérieurs de routage,
du profil discovery-demo et d'absence d'instance restent présents, avec un JWT ADMIN signé.

```powershell
.\mvnw.cmd -B -ntp -pl services/api-gateway -am test
.\mvnw.cmd -B -ntp verify
```

Les résultats réellement observés sont consignés dans la section de validation ajoutée avant livraison
et dans la Draft PR ; les journaux locaux restent dans `target/arij-validation/`, ignoré par Git.

## Validation observée le 15 novembre 2026

`.\mvnw.cmd -B -ntp verify` : **BUILD SUCCESS**, 106 tests, zéro échec, erreur ou test ignoré.
Les 64 tests existants sont conservés, avec 42 nouveaux cas de sécurité.

| Module | Tests réussis |
|---|---:|
| Customer | 8 |
| Gateway | 55 |
| Discovery | 2 |
| Config Server | 1 |
| Event | 21 |
| Booking | 19 |

- Construction Docker des six images réussie ; Keycloak 26.8.2 et les six services `healthy`.
- Realm chargé ; les trois comptes obtiennent de vrais jetons par Code + PKCE S256 avec issuer,
  audience, `sub`, RS256 et rôles attendus. Le grant `password` est effectivement refusé.
- Tests réels : catalogue 200, routes privées sans JWT 401, rôle insuffisant 403, accès autorisé 200,
  refus des chemins internes 403, prévol CORS 200 et absence de contournement par en-têtes falsifiés.
- Démonstration historique adaptée réussie : registre Eureka, Config Server native, refresh Customer,
  création/lecture Customer, création/publication Event, réservation/lecture/annulation Booking,
  propagation des erreurs métier et des en-têtes `Location` / `X-Served-By`.
- Syntaxe Compose native et overlay Git validée ; le mode Git n'a pas été démarré contre un vrai
  dépôt de configuration, qui reste à fournir. Aucun volume existant supprimé.

L'essai initial avait révélé l'absence de `sub` dans les jetons : l'export a été corrigé en ajoutant
le scope `basic`. Le realm fictif créé pendant cette intervention a été réimporté hors ligne,
puis les trois connexions ont été validées. La validation `sub` du Gateway n'a pas été assouplie.
Le statut GitHub Actions est relevé séparément sur le commit final de la Draft PR.

## Limites et production

**Un client CUSTOMER peut encore demander une réservation pour un autre `customerId`, lister les
réservations d'autrui et lire/modifier un profil d'autrui par son ID. Un ORGANIZER peut encore agir
sur l'événement d'un autre organisateur. Le détail authentifié peut encore montrer un brouillon.**
Le Gateway filtre les rôles, pas les propriétaires ; les identités Keycloak et Customer ne sont pas liées.
Les services métier ne valident pas encore le JWT transmis. Customer est directement accessible sur
loopback 8081 ; un acteur ayant accès au réseau Docker peut aussi appeler les autres services sans Gateway.
L'accès local direct à Config Server/Eureka est conservé pour la démonstration, sans authentification.

Ne pas déployer cette configuration sur Internet. Une configuration de production devra utiliser
Keycloak en mode `start` avec HTTPS, une base externe, des secrets injectés, des origines strictes,
des accès d'administration séparés, des réseaux internes contrôlés et les Resource Servers métier.
Avec validation JWT locale, la déconnexion Keycloak ne révoque pas immédiatement un access token
déjà émis ; sa courte expiration limite cette fenêtre. La stratégie de révocation est à préciser.

La [coordination phase 2](ARIJ_SECURITY_COORDINATION.md) détaille les travaux de Manar, Salma et Arij :
liaison Customer `(iss, sub)`, résolution interne, propriété Event/Booking, migrations et futur Ticket.
