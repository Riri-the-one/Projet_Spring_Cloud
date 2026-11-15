# Ticket Service — premier lot métier

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

Les trois routes Gateway existantes restent inchangées : Ticket n'est **pas encore
exposé par Gateway**. Le test local utilise le port loopback 8084. L'ajout futur d'une
route Gateway et de sa matrice sera une étape explicite avant le branchement Angular.

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

## Persistance provisoire et PostgreSQL futur

Validations locales du 15 novembre 2026 : Maven `verify` vert avec **183 tests**, dont
**27 Ticket** ; image Ticket construite et conteneur sain. Le test `--ticket-only`
avec de vrais JWT Keycloak a réussi sans créer de billet. Les dix conteneurs sont
sains, et les définitions des sept services d'origine sont identiques avec l'overlay.
La PR Customer PostgreSQL #7 est fusionnée à `4c08ab8` ; les quatre jobs de sa
[CI main](https://github.com/Riri-the-one/Projet_Spring_Cloud/actions/runs/43774204921)
sont verts.

**H2 en mémoire uniquement, profil demo-h2 obligatoire.** La garde refuse les autres
datasources et l'absence de profil, pour éviter une connexion à une base d'un autre
domaine. Les billets et garanties d'idempotence disparaissent avec la base H2 au
redémarrage : ce n'est pas un stockage durable ni une livraison exploitable en production.

Le cluster PostgreSQL existant n'a que Customer/Event/Booking. Prévoir une base et un
compte `ticketflow_ticket` distincts. Ne pas modifier le bootstrap initial en espérant
qu'il s'applique à un volume déjà initialisé. Une procédure additive revue devra créer
le compte avec secret local séparé, retirer les droits PUBLIC par défaut et accorder
uniquement l'accès à cette base. Vérifier aussi l'absence de droits depuis les autres
comptes de domaine. Aucune commande de provisionnement n'est exécutée dans ce lot.

Ensuite : migrations Flyway Ticket séparées pour lots et billets, contraintes et index,
types UUID/bigint/timestamptz, DDL validate, secret Ticket exclusif et tests PostgreSQL
de concurrence, rollback, sauvegarde/restauration et persistance après redémarrage.
Ne réutiliser aucune base ou migration Customer/Event/Booking.

L'intégration complète attend le contrat confirmé de Salma, l'identité Booking fiable,
le paiement admissible, les annulations, le provisionnement PostgreSQL Ticket et les
politiques Kafka. Notification Service n'est pas commencé.
