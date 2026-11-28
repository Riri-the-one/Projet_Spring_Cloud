# Ticket Service

Module Java 21 / Spring MVC, Spring Boot 4.0.8 et Spring Cloud 2025.1.2 du monorepo.
Le code métier Booking, Payment et Event n'est pas modifié.

## Audit et périmètre

La mission demande Ticket sous la référence FR-011. La version actuellement présente
de `docs/CAHIER_DES_CHARGES.md` utilise une ancienne numérotation RF et son RF-11
concerne le diagnostic ; elle ne détaille pas ce contrat Ticket. Ce README et le
[contrat proposé à Salma](../../docs/ARIJ_TICKET_BOOKING_CONTRACT.md) explicitent le lot
autorisé, sans présenter une intégration Booking comme déjà disponible.

Le cœur `TicketIssuanceService.issue` accepte une confirmation admissible provenant
d'un futur adaptateur de confiance. Il n'est appelé par **aucune entrée réseau**.
Les seules confirmations fictives sont dans `src/test`. Le runtime démarre sans billets.
Aucun consommateur Kafka, producteur métier, seed ou endpoint HTTP d'émission n'existe.

## Modèle et émission

`TicketBatch` identifie un lot par bookingId et conserve customerId, premier UUID de
message et empreinte canonique du contenu. `Ticket` contient UUID v4, référence locale
au lot, lineId, index technique de place, eventId, code de contrôle, dates et statut.
Il n'y a aucune clé étrangère vers les bases Booking/Event/Customer.

Une confirmation doit être CONFIRMED, paiement SUCCEEDED avec référence, version 1
du contrat, version positive de réservation et lignes valides. PENDING, paiement en
attente ou échoué sont refusés avant toute écriture. Plusieurs lignes et plusieurs
places sont supportées ; un billet distinct est créé pour chaque place.

Les codes utilisent SecureRandom (32 octets, encodage base64url sans padding), sans
nom, e-mail, JWT ni identifiant métier encodé. Ils sont stockés dans la base Ticket et
visibles uniquement dans les réponses autorisées ; les traiter comme des justificatifs
sensibles : ne pas les journaliser, les mettre dans une URL ou les exporter en public.
Le schéma provisoire ne chiffre pas ces codes au repos ; le futur stockage durable
doit définir sa protection et ses sauvegardes.

Une transaction englobe le lot et tous les billets. Contraintes : bookingId unique,
premier message unique, `(bookingId,lineId,seatIndex)` unique et code unique. Un rejeu
rend les billets existants ; un contenu contradictoire provoque
`ConfirmationConflictException`. Aucun stock n'est modifié.

Statuts prévus : ISSUED, USED, VOIDED, EXPIRED. L'expiration est calculée à la lecture,
sans tâche planifiée. Les transitions utilisées/annulées sont testées dans le domaine
mais aucun endpoint de scan ou d'annulation n'est ouvert dans ce lot.
La validité est **locale** : elle ne consulte pas Booking à chaque lecture et ne reflète
pas encore ses futures annulations. Un contrôle d'entrée réel nécessite un contrat
d'annulation et une consommation atomique du billet ; il n'est pas livré ici.

## API et sécurité

| API | Droits et résultat |
| --- | --- |
| `GET /api/tickets/me` | CUSTOMER/ADMIN : billets de son propre profil |
| `GET /api/tickets/{uuid}` | propriétaire ou ADMIN |
| `GET /api/tickets/bookings/{bookingId}` | propriétaire du lot ou ADMIN |
| `POST /api/tickets/{uuid}/validity` | propriétaire ou ADMIN ; corps `{ "controlCode": "…" }` |
| `GET /actuator/health` | public, réponse minimale |

La vérification de validité rend `{ "valid": true, "reason": "ISSUED", "status": "ISSUED" }`
ou valid=false avec CODE_MISMATCH, EXPIRED, USED ou VOIDED. Le code n'est jamais passé
dans l'URL. Aucune méthode HTTP ne crée arbitrairement de billet, même pour ADMIN.
ORGANIZER seul n'obtient aucun droit de lecture/scan.

Resource Server servlet officiel : RS256, signature, iss, aud=ticketflow-api,
exp/nbf et sub non vide. Voir la
[référence Spring Security](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
La conversion des rôles reprend la politique Customer/Gateway, sans rôle implicite.
X-Customer-Id, X-User-Id et X-Roles n'accordent aucun droit.

Dans le service métier, Customer est appelé sur `/internal/customers/me` avec **le
JWT déjà validé**, pour résoudre le customerId fiable. Aucun identifiant HTTP fourni
par Angular n'est utilisé pour cette résolution. Les appels sont bornés à 3 s pour
la connexion et 5 s pour la lecture ; les redirections sont refusées et une indisponibilité interdit l'accès.
ADMIN peut lire un billet sans résolution de son profil ; `/me` reste son propre profil.
Le token utilisateur ne constitue pas encore une authentification technique interservices.
L'URL Customer est une configuration d'exploitation de confiance, jamais une entrée utilisateur.

Erreurs : 400 paramètre/code mal formé ; 401 JWT absent/invalide ; 403 rôle ou propriété
insuffisants et routes non autorisées ; 404 billet/lot/profil introuvable ; 503 résolution
Customer impossible. Les erreurs sont sans données personnelles. Les conflits d'émission
restent internes : aucun endpoint HTTP ne traduit actuellement cette exception en 409.

Les trois routes Gateway existantes restent inchangées. Ticket dispose maintenant de
deux routes bornées par méthode HTTP pour les quatre API ci-dessus ; aucune route
d'émission n'est ajoutée. Les validations JWT et de propriété restent dans Ticket.
Le port direct loopback 8084 permet de vérifier cette protection indépendamment.

## Démarrage PowerShell

Prérequis : Config Server, Eureka, Keycloak et Customer fonctionnels ; profil Customer
déjà créé pour le compte de démonstration. Depuis la racine :

```powershell
.\mvnw.cmd -B -ntp -pl services/ticket-service -am verify
$env:SPRING_PROFILES_ACTIVE = 'demo-h2'
.\mvnw.cmd -B -ntp -pl services/ticket-service spring-boot:run
# Ou Docker, sans redémarrer les services existants :
$t = @('compose', '-f', 'infra/docker-compose.yml', '-f', 'infra/docker-compose.ticket.yml', '--profile', 'ticket')
docker @t config --quiet
docker @t build ticket-service
docker @t up -d --no-deps --wait --wait-timeout 180 ticket-service
node tools/Test-KeycloakIntegration.mjs --ticket-only
.\mvnw.cmd -B -ntp verify
```

IntelliJ : lancer `TicketServiceApplication` avec le profil `demo-h2` et les URLs
locales par défaut. En Docker, issuer public localhost:8180 et JWKS interne keycloak:8080
restent distincts. Le port Ticket est publié exclusivement sur 127.0.0.1.
L'overlay est optionnel et ne change aucun service existant ; ne pas utiliser
`--remove-orphans` ni `down -v` pour les avertissements liés aux autres overlays.

`--ticket-only` vérifie de vrais JWT, lecture vide, refus de rôle et absence de création.
Il n'injecte aucun événement fictif en production. L'émission, les replays, la concurrence,
la propriété et les états de validité sont exercés par les tests automatisés avec H2
et de vraies signatures RSA ; aucun Keycloak n'est nécessaire aux tests Maven.

## Persistance PostgreSQL et démonstration H2

Le [guide PostgreSQL + Gateway](../../docs/ARIJ_TICKET_PERSISTENCE_GATEWAY.md) fournit
les commandes de provisionnement, tests et démarrage. La base `ticketflow_ticket` et
son compte/secret sont distincts de Customer/Event/Booking. La procédure additive est
explicite sur volume neuf ou existant ; le bootstrap initial n'est pas rejoué.

Le profil `postgresql` active Flyway V1/V2 pour lots et billets et Hibernate validate.
Les codes, IDs et garanties d'idempotence sont conservés dans le volume PostgreSQL.
La garde interdit les profils ambigus, une base étrangère et les DDL destructifs.
Le profil `demo-h2` reste disponible sans secret PostgreSQL Ticket : sa base est
éphémère et perd ses billets/idempotence au redémarrage. Aucun import H2 n'est automatique.
Les sauvegardes/restaurations et la protection des codes au repos restent à préparer
avant un usage en production.

L'intégration complète attend le contrat confirmé de Salma, l'identité Booking fiable,
le paiement admissible, les annulations et les
politiques Kafka. Notification Service n'est pas commencé.
