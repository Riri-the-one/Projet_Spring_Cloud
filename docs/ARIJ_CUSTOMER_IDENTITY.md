# TicketFlow — Customer sécurisé et identité Keycloak (phase 2A)

Cette phase sécurise Customer Service, y compris lors d'un appel direct sur son port 8081.
Elle conserve les IDs numériques et prépare la résolution d'identité pour Booking.
**Event et Booking ne sont pas encore sécurisés dans leurs services métier.**

## Compte et profil

Le compte Keycloak permet de se connecter et porte les rôles. Le profil Customer contient les
informations métier : prénom, nom, e-mail de contact et téléphone. Se connecter ne crée pas de profil.

La liaison utilise exactement `(iss, sub)` : l'émetteur du JWT et le sujet attribué par cet émetteur.
Le `sub` est relatif à l'issuer ; l'e-mail peut changer et ne permet jamais de rattacher un profil.
Un même e-mail dans Keycloak et une ancienne ligne Customer ne provoque aucune association automatique.

L'ID Customer reste un `Long` généré avec `IDENTITY`. Deux colonnes sont ajoutées :
`identity_issuer` (512 caractères) et `identity_subject` (255 caractères).
Elles sont définies au moment de la création depuis le JWT validé, sans setter ni opération HTTP
de réaffectation, et sont non modifiables par le mapping JPA (`updatable=false`).
Le DTO de requête conserve seulement `firstName`, `lastName`, `email`, `phone`.
Les champs JSON inconnus, notamment les champs d'identité, sont rejetés en 400.

## Sécurité indépendante du Gateway

Customer utilise Spring MVC et une `SecurityFilterChain` servlet, avec le starter officiel Boot 4
`spring-boot-starter-security-oauth2-resource-server`. Les versions Java 21, Boot 4.0.8 et Cloud
2025.1.2 restent inchangées. Aucun filtre JWT artisanal n'est ajouté.

Le service valide RS256, la signature à partir du JWKS configuré, l'issuer exact, l'audience
`ticketflow-api`, `exp`, `nbf` lorsqu'il est présent et un `sub` non vide. `exp`, `iss`, `aud`, `sub`
sont requis. Tolérance d'horloge : 30 secondes. Les tailles d'identité sont bornées par les colonnes.
Seuls les rôles `CUSTOMER`, `ORGANIZER`, `ADMIN` du JWT validé deviennent des autorités `ROLE_…`.
Les rôles sont indépendants. Aucun en-tête `X-Roles`, `X-User-Id`, `X-Customer-Id` n'est utilisé.

L'API utilise le Bearer, sans session d'authentification ni cache de requêtes en session ; CSRF est
désactivé pour ce mode. Le navigateur utilise le Gateway, qui conserve sa politique CORS.
Customer ne fournit pas un second point d'entrée navigateur avec CORS libre.

`CustomerAccessPolicy` applique aussi les droits au niveau de `CustomerService` : un appel à une
méthode du service ne contourne pas la propriété simplement parce qu'il évite le contrôleur HTTP.

## Droits et endpoints

| Opération | Autorisation |
|---|---|
| `POST /api/customers` | CUSTOMER ou ADMIN, création pour le porteur du JWT uniquement |
| `GET /api/customers/me` | CUSTOMER ou ADMIN, son propre profil |
| `PUT /api/customers/me` | CUSTOMER ou ADMIN, modifier ses données de contact |
| `GET /api/customers/{id}` | Propriétaire CUSTOMER ou ADMIN |
| `PUT /api/customers/{id}` | Propriétaire CUSTOMER ou ADMIN, sans changement d'identité |
| `GET /api/customers` | ADMIN |
| `DELETE /api/customers/{id}` | ADMIN uniquement |
| `GET /internal/customers/me` | CUSTOMER ou ADMIN, résolution de sa propre identité |
| `GET /actuator/health` | Public, sans détails ni composants |
| `GET /api/customers/whoami` | ADMIN ; diagnostic de l'instance, pas identité utilisateur |
| `GET /api/config/message` | ADMIN, accès direct interne seulement |
| `POST /actuator/refresh` | ADMIN lorsque l'endpoint est exposé par le profil de configuration |
| Console H2 et autres chemins/méthodes | Refus par défaut ; console désactivée |

ORGANIZER seul ne donne aucun accès Customer. ADMIN peut lire/modifier les profils historiques,
mais ne peut pas changer leur association via le DTO. Il peut créer son propre profil ; la création
pour un autre compte nécessiterait un contrat distinct, absent de cette phase.
La suppression ne supprime pas le compte Keycloak. Avant une utilisation persistante, il faut définir
avec Salma le devenir des réservations référençant le profil : aucun nettoyage interservices ni cascade
distribuée n'est réalisé ici. Supprimer puis recréer un profil peut donner un nouvel ID métier.

| Statut | Cas |
|---|---|
| 400 | Corps invalide, champ JSON inconnu ou contrainte d'intégrité autre qu'un doublon |
| 401 | JWT absent, signature invalide ou claims requis invalides |
| 403 | Rôle insuffisant, profil d'autrui ou chemin/méthode refusé |
| 404 | ID inexistant ou identité authentifiée sans profil associé |
| 409 | Identité déjà associée ou e-mail indisponible |

Les erreurs de profil absent utilisent `CUSTOMER_PROFILE_NOT_FOUND` ; les conflits utilisent
`CUSTOMER_IDENTITY_CONFLICT` ou `CUSTOMER_EMAIL_CONFLICT` dans une réponse Problem Detail.
Les erreurs ne renvoient ni SQL, ni e-mail existant, ni identifiant Keycloak d'une autre personne.
Les catégories Hibernate qui journalisent les valeurs d'erreurs SQL sont désactivées dans la
configuration locale principale. Ne pas activer des traces SQL contenant des données personnelles.

## Intégrité H2 et concurrence

La table porte les contraintes nommées `uk_customer_identity`, `uk_customer_email` et
`ck_customer_identity_pair`. Plusieurs anciennes lignes peuvent avoir les deux colonnes nulles.
Une liaison partielle ou contenant des valeurs vides est rejetée par la base, même en SQL direct.
Les anciens profils restent inaccessibles à un utilisateur ordinaire, qui ne peut pas les revendiquer.

La vérification préalable des doublons ne remplace pas la contrainte unique. `saveAndFlush` fait
remonter les conflits dans la transaction ; l'advice les traduit après rollback. Le code ne tente
jamais de continuer à utiliser une transaction ayant échoué. Les tests font notamment passer les
deux vérifications préalables avant de libérer simultanément deux insertions concurrentes.

## Contrat destiné à Booking

```http
GET /internal/customers/me
Authorization: Bearer <access_token utilisateur>
```

```json
{"customerId":123}
```

La réponse 200 contient uniquement ce champ et `Cache-Control: no-store`. Aucun ID ni couple
d'identité n'est accepté comme sélection de profil ; des paramètres/en-têtes falsifiés ne modifient
pas la résolution. Le profil est déterminé exclusivement par le JWT validé.
Les réponses sont 401 sans JWT valide, 403 sans rôle autorisé, 404 si aucun profil n'est associé.
Un GET ne provisionne rien. ADMIN résout aussi son propre profil, sans sélection d'autrui.

Cette route n'est pas exposée par Gateway. Le JWT prouve l'identité utilisateur, **pas l'identité
technique du service appelant**. Tout acteur ayant accès au réseau et un JWT utilisateur admissible
peut résoudre ce même profil. Une authentification technique supplémentaire serait nécessaire pour
réserver strictement l'appel à Booking. Aucun secret de service n'est introduit dans cette phase.

Pour Salma : transmettre explicitement le Bearer au seul client Feign Customer, y compris en présence
de circuit breaker/changement de thread. Utiliser le `customerId` résolu, jamais un ID proposé par
le client. Arrêter le parcours si la résolution échoue ; distinguer 404 de l'indisponibilité réseau/503.
Ne pas mettre en cache un profil sous une clé indépendante de l'identité et ne pas journaliser le jeton.
Voir le [récapitulatif Booking](ARIJ_CUSTOMER_BOOKING_CONTRACT.md).
**Booking n'appelle pas encore cet endpoint et son code reste inchangé.**

## Configuration Windows et Docker

| Variable | Windows / IntelliJ | Customer dans Compose |
|---|---|---|
| `KEYCLOAK_ISSUER_URI` | `http://localhost:8180/realms/ticketflow` | Même issuer public |
| `KEYCLOAK_JWK_SET_URI` | `http://localhost:8180/realms/ticketflow/protocol/openid-connect/certs` | `http://keycloak:8080/realms/ticketflow/protocol/openid-connect/certs` |
| `TICKETFLOW_JWT_AUDIENCE` | `ticketflow-api` | `ticketflow-api` |

Compose fournit ces valeurs à Customer et attend la santé de Keycloak. Aucun port supplémentaire
n'est publié, les ports hôte restent sur `127.0.0.1`. Le realm et les trois comptes fictifs existants
sont conservés ; aucun réimport ni suppression de volume n'est nécessaire.
Gateway autorisait déjà GET/PUT `/api/customers/me` : aucune modification de ses règles n'est requise.
Config Server native/Git et Eureka restent inchangés. L'overlay Git dépend toujours du vrai dépôt TP3.

```powershell
.\mvnw.cmd -B -ntp -pl services/customer-service -am test
.\mvnw.cmd -B -ntp verify
docker compose -f infra/docker-compose.yml config --quiet
docker compose -f infra/docker-compose.yml build customer-service
docker compose -f infra/docker-compose.yml up -d --wait --wait-timeout 300
docker compose -f infra/docker-compose.yml ps
node tools/Test-KeycloakIntegration.mjs
```

Le test Node nécessite Node.js 22 ou plus et reste limité à des URLs loopback. Il obtient de vrais
jetons Code + PKCE S256 des trois comptes fictifs existants, sans afficher les jetons. Il vérifie
Customer directement et via Gateway, crée/réutilise le profil CUSTOMER, le consulte/modifie, vérifie
le conflit 409, les refus ORGANIZER, les droits ADMIN et le contrat interne minimal.
Les tests Maven couvrent aussi deux utilisateurs CUSTOMER distincts et les profils historiques.

Le script appelle ensuite `Test-ArijDemo.ps1` avec le jeton ADMIN en mémoire. Ce script transmet
désormais le Bearer aux appels directs Customer, notamment configuration et refresh. Il crée le profil
ADMIN au premier passage puis le réutilise après le 409 attendu lors des passages suivants. Il conserve
le scénario Event publié → réservation → annulation, sans changer le métier de ces services.

Pour une présentation rapide :

```powershell
curl.exe -i http://localhost:8081/actuator/health
curl.exe -i http://localhost:8081/api/customers/me
node tools/Test-KeycloakIntegration.mjs
```

Attendre respectivement 200, 401, puis les validations explicites du scénario authentifié.
Pour arrêter sans supprimer de volume : `docker compose -f infra/docker-compose.yml stop`.

## Préparer PostgreSQL avec Manar

Cette phase n'ajoute ni PostgreSQL ni migration automatique. Les colonnes textuelles, l'ID numérique
et les contraintes nommées sont compatibles avec une future migration, qui devra être testée réellement.

1. Sauvegarder et inventorier la base persistante ; contrôler les doublons avant toute contrainte.
2. Ajouter les deux colonnes nullable, puis la contrainte de paire et l'unicité du couple.
3. Préserver IDs et lignes historiques ; effectuer les associations uniquement avec une procédure
   explicite, contrôlée et auditée. Ne pas remplir par correspondance d'e-mail.
4. Conserver la comparaison exacte et sensible à la casse de l'issuer/subject ; vérifier les tailles
   et la collation. Garder l'unicité e-mail après sa normalisation métier.
5. Définir des migrations versionnées avec Manar, puis utiliser `ddl-auto=validate` en environnement
   persistant. **Le `create-drop` actuel détruit le schéma au cycle de vie de la base de démonstration ;
   il ne doit pas être utilisé avec de vraies données PostgreSQL.** Aucun mode `update` automatique
   n'est ajouté comme substitut à une migration contrôlée.
6. Tester sur PostgreSQL : création depuis une base vide, migration de profils non associés, paire
   partielle, doublons, deux transactions concurrentes, rollback, préservation des IDs et redémarrage.
7. Définir avec Salma la suppression/archivage des profils et leurs références Booking avant de
   permettre des suppressions physiques sur des données persistantes.

Les deux valeurs nulles permettent les profils historiques ; une nouvelle création HTTP exige
toujours une identité complète. Le même `sub` sous deux issuers différents est distinct en base,
mais le Resource Server n'accepte actuellement que l'issuer Keycloak configuré.

## Limites de livraison

Customer est sécurisé ; Event et Booking restent au niveau de protection Gateway de phase 1.
Booking fait encore confiance à son `customerId` reçu et n'applique pas encore la propriété des
réservations. Event n'applique pas encore la propriété organisateur ni toute la visibilité du détail.
La suppression Keycloak, le rattachement administratif d'anciens profils, la révocation immédiate
des access tokens et l'authentification technique interservices ne sont pas implémentés.
Le futur Ticket Service n'est pas commencé ; il devra consommer des contrats d'identité et de
propriété fiables, sans faire confiance à un ID client ou à un en-tête d'identité.

Les résultats réels de Maven, Docker et GitHub Actions sont indiqués dans la Draft PR et le rapport
de livraison. Les journaux locaux restent dans `target/arij-validation/`, ignoré par Git.

## Validation observée le 15 novembre 2026

`.\mvnw.cmd -B -ntp verify` : **BUILD SUCCESS**, 146 tests, zéro échec, erreur ou test ignoré.
Les 106 tests antérieurs sont conservés et 40 cas sont ajoutés : Customer 48, Gateway 55,
Event 21, Booking 19, Discovery 2 et Config Server 1.

Les essais Customer utilisent de vrais JWT RSA, le décodeur servlet de production, un JWKS local,
JPA et H2. Ils couvrent aussi les conflits d'identité/e-mail HTTP et les contraintes de base après
deux vérifications préalables simultanément réussies. Les tests n'exigent ni Docker ni Keycloak.

L'image Customer a été construite et son conteneur remplacé, sans reconstruire ni redémarrer les
autres services métier. Le test Node avec les trois comptes réels fictifs du realm a réussi :
401/403, création et modification du profil CUSTOMER, conflit 409, refus des champs d'identité,
résolution interne minimale, accès administrateur, refresh et scénario Event/Booking existant.
Le refus d'accès d'un second utilisateur CUSTOMER est couvert dans Maven ; le compte ORGANIZER
réel vérifie le refus pour un autre utilisateur sans rôle Customer dans Compose.
Les sept conteneurs restent sains ; aucun volume n'a été supprimé ni realm réimporté.
Un second passage complet a également réussi : le profil ADMIN existant est réutilisé après le 409
d'identité attendu, puis le scénario Event/Booking est rejoué sans remise à zéro des services.

La migration PostgreSQL n'a pas été exécutée. Le mode Config Server Git n'a pas été démarré contre
un vrai dépôt TP3 ; sa configuration est inchangée. GitHub Actions est vérifié sur le commit final
de la Draft PR, séparément du résultat Maven local.
