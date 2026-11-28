# Ticket : PostgreSQL dédié et consultation via Gateway

Ce lot rend le stockage Ticket durable et expose seulement la consultation et la
vérification de validité. Aucun consommateur Booking, paiement simulé, seed métier,
Notification Service ou endpoint HTTP d'émission n'est ajouté.

## Provisionner le quatrième domaine

Le cluster existant reste PostgreSQL 18.7 ; son volume est conservé. Les fichiers
init Docker ne sont pas rejoués sur un volume existant. Le bootstrap initial à trois
domaines n'est donc pas modifié. Une procédure **explicite et additive** est utilisée
sur un environnement neuf comme sur le cluster existant :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Provision-TicketPostgresql.ps1
# Répéter est autorisé : vérification, aucune rotation ni suppression
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Provision-TicketPostgresql.ps1
```

La commande conserve les quatre secrets existants et génère uniquement
`infra/postgresql/.local/ticket.password` s'il n'existe pas (32 octets aléatoires).
Les fichiers restent ignorés par Git et exclus des images. Le mot de passe n'est
jamais écrit dans le SQL versionné ou dans les arguments de commande.

L'overlay monte le secret supplémentaire en lecture seule dans PostgreSQL pour le
provisionnement. Cela peut remplacer **le seul conteneur PostgreSQL**, en réutilisant
son volume ; prévoir une courte interruption des connexions et ne pas exécuter cette
opération pendant des écritures métier. Les pools applicatifs doivent se reconnecter.
Les autres conteneurs ne sont pas remplacés par la procédure.

Le script crée `ticketflow_ticket`, compte et base distincts. Le compte est sans
privilège de superutilisateur, sans CREATEDB/CREATEROLE, sans héritage ni appartenance
de rôle. La base appartient à `ticketflow_admin`, pas au compte applicatif. Les droits
PUBLIC sur la base et son schéma public sont retirés ; seul le compte Ticket reçoit
ses droits de domaine. Les connexions sont désactivées à la création de la base puis
activées après restriction des ACL, car [CREATE DATABASE ne peut pas être dans une
transaction](https://www.postgresql.org/docs/current/sql-createdatabase.html).

La procédure vérifie son marqueur d'achèvement. Si le rôle existe sans sa base, si le
marqueur manque, si le secret ne correspond plus ou si les privilèges sont inattendus,
elle échoue sans adoption, changement de propriétaire ou rotation automatique. Ne pas
supprimer le volume pour contourner le refus. Inspecter avec l'administrateur et
restaurer le secret d'origine ; toute réparation d'un provisionnement interrompu doit
être revue avant intervention. CREATE ROLE et CREATE DATABASE ne sont pas une opération
atomique unique : un arrêt entre les deux peut nécessiter cette procédure manuelle.

Les tests vérifient l'authentification TCP et le refus CONNECT dans les deux sens entre
Ticket et Customer/Event/Booking, ainsi que le refus des bases de maintenance.
Le job CI Ticket commence avec un cluster neuf, provisionne puis répète cette procédure.

## Activer Ticket PostgreSQL

Avec Config Server, Eureka, Keycloak et Customer déjà actifs :

```powershell
$t = @('compose', '-f', 'infra/docker-compose.yml', '-f', 'infra/docker-compose.postgresql.yml', '-f', 'infra/docker-compose.ticket.yml', '-f', 'infra/docker-compose.ticket-postgresql.yml', '--profile', 'postgresql', '--profile', 'ticket')
docker @t config --quiet
docker @t build ticket-service api-gateway
docker @t up -d --no-deps --wait --wait-timeout 180 ticket-service api-gateway
docker @t ps
node tools/Test-KeycloakIntegration.mjs --ticket-gateway
```

Conserver l'overlay Customer PostgreSQL dans les commandes qui doivent recréer Customer
en mode persistant. La commande ci-dessus utilise `--no-deps` et ne recrée pas Customer,
Event ou Booking. Ne pas lancer un `up` global sans leurs overlays appropriés.

Ticket ne reçoit que `postgres_ticket_password`, via Config Tree
`/run/secrets/ticket/spring.datasource.password`. Il n'obtient aucun secret Customer,
Event ou Booking. Adresse Docker : `postgres:5432/ticketflow_ticket` ; Windows/IntelliJ :
`127.0.0.1:5433/ticketflow_ticket`, compte `ticketflow_ticket`.

Dans IntelliJ, activer le profil `postgresql`. Depuis un terminal PowerShell privé :

```powershell
$env:SPRING_PROFILES_ACTIVE = 'postgresql'
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5433/ticketflow_ticket'
$env:SPRING_DATASOURCE_USERNAME = 'ticketflow_ticket'
$env:SPRING_DATASOURCE_DRIVER_CLASS_NAME = 'org.postgresql.Driver'
$env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'validate'
$env:SPRING_FLYWAY_ENABLED = 'true'
$env:SPRING_DATASOURCE_PASSWORD = [IO.File]::ReadAllText((Resolve-Path 'infra/postgresql/.local/ticket.password')).Trim()
.\mvnw.cmd -B -ntp -pl services/ticket-service spring-boot:run
# Après arrêt, supprimer uniquement la variable de ce terminal
Remove-Item Env:SPRING_DATASOURCE_PASSWORD
```

Ne pas enregistrer le secret dans une configuration IntelliJ partagée ou une capture.
Le profil local et le document Config Server `ticket-service-postgresql.yml` imposent
Flyway et validate. Les variables critiques Docker ont priorité sur Config Server ;
Config Tree est importé après les documents distants. En mode Config Server Git,
coordonner aussi l'ajout du document de profil dans le dépôt de configuration externe.
La garde de démarrage refuse un retour vers H2, un compte d'un autre domaine, un DDL
destructif, une location Flyway étrangère, un baseline automatique ou un secret absent.

## Migrations et conservation des données

Starter Flyway officiel et module PostgreSQL gérés par le BOM Spring Boot existant,
sans changement de versions. Les migrations sont propres à Ticket :

- V1 : lots d'émission (bookingId unique, customerId, premier message UUID unique,
  empreinte et version optimiste), index par client.
- V2 : billets (UUID, référence locale au lot, ligne/place/événement, code, statut,
  dates et version), unicité du code et de `(bookingId,lineId,seatIndex)`.

Hibernate utilise **ddl-auto=validate**, jamais update ou create-drop sur PostgreSQL.
Flyway conserve checksums et versions. Clean est désactivé et le baseline automatique
interdit ; une base non vide sans historique est refusée. Ne jamais modifier une
migration appliquée. Pour une adoption/import : sauvegarde privée, restauration sur
copie, comparaison des schémas/contraintes et procédure revue avant toute écriture.
Aucun transfert des billets H2 n'est automatique.

Les codes sont opaques mais sensibles, stockés en clair dans cette base de développement.
Ne pas les journaliser ni les publier. Le stockage durable ne rend pas l'environnement
local équivalent à une production : protéger le volume, les sauvegardes et les accès.

## Gateway et autorisations

| Route | Méthode | Droits Gateway puis Ticket |
| --- | --- | --- |
| `/api/tickets/me` | GET | CUSTOMER/ADMIN, son propre profil |
| `/api/tickets/{id}` | GET | CUSTOMER propriétaire ou ADMIN |
| `/api/tickets/bookings/{bookingId}` | GET | CUSTOMER propriétaire ou ADMIN |
| `/api/tickets/{id}/validity` | POST | CUSTOMER propriétaire ou ADMIN |

Deux routes explicites `lb://ticket-service`, bornées par méthode HTTP, complètent les
trois routes métier existantes. Les en-têtes X-* ne sont pas une identité ; le Bearer
est transmis pour une seconde validation indépendante par Ticket. Ticket résout le
profil via le JWT validé auprès de Customer. ORGANIZER seul reste refusé. Aucune route
de création, modification, suppression ou administration Ticket n'est ouverte.
Les requêtes CORS OPTIONS légitimes restent gérées par Gateway.

401 = JWT absent/invalide ; 403 = rôle/propriété insuffisants ou route interdite ;
404 = billet/lot/profil introuvable ; 503 = résolution Customer indisponible.
La vérification de validité est une lecture locale et ne consomme pas le billet.

## Vérifications PowerShell

```powershell
.\mvnw.cmd -B -ntp verify
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Test-TicketPostgresql.ps1
node tools/Test-KeycloakIntegration.mjs --ticket-gateway
node tools/Test-KeycloakIntegration.mjs
```

La suite PostgreSQL utilise un schéma `ticket_test_<uuid>` distinct du schéma runtime
public, avec le seul secret Ticket. Elle rejoue les tests JWT/propriété/concurrence,
émet des fixtures **de test uniquement**, puis redémarre PostgreSQL et une nouvelle JVM.
Le même événement doit rendre les mêmes IDs et une empreinte identique des codes.
Le marqueur local ne contient que les identifiants et une empreinte, aucun code.
Les fixtures sont conservées dans ces schémas isolés ; ne pas lancer sur une base de
production. Aucun faux paiement ou billet n'est injecté dans le runtime public.

`--ticket-gateway` utilise de vrais JWT Code + PKCE et vérifie les quatre routes,
les refus et une liste vide. Les contrôles d'accès à des billets existants appartiennent
aux tests signés dans les schémas isolés ; cette démonstration ne simule pas une vente.
Le test nécessite que le compte demo-customer ait déjà son profil Customer.

Pour le mode H2, aucune base/clé PostgreSQL Ticket n'est nécessaire :

```powershell
$h = @('compose', '-f', 'infra/docker-compose.yml', '-f', 'infra/docker-compose.ticket.yml', '--profile', 'ticket')
docker @h up -d --no-deps --wait --wait-timeout 180 ticket-service
node tools/Test-KeycloakIntegration.mjs --ticket-gateway
# Rétablir le mode persistant sans effacer les billets PostgreSQL
docker @t up -d --no-deps --wait --wait-timeout 180 ticket-service
```

H2 reste éphémère et ne conserve pas son idempotence au redémarrage. Ne jamais utiliser
`down -v`, `volume rm` ou `--remove-orphans`. Les avertissements d'orphelins proviennent
des overlays optionnels absents de la commande.

## Dépendances Salma

Résultats locaux du 16 novembre 2026 : Maven `verify` vert avec **211 tests**,
dont les 183 préservés, 13 scénarios Gateway Ticket et 15 gardes de stockage.
**30 tests PostgreSQL** supplémentaires réussissent, puis le test de persistance est
rejoué dans une nouvelle JVM après redémarrage PostgreSQL. Provisionnement répété,
isolation dans les deux sens, mode H2 sans secret Ticket et appels réels Keycloak
directs/Gateway ont été validés. Le runtime public reste vide de billets fictifs.
La PR #8 est fusionnée à `b0fc1f5`, avec les quatre jobs
[CI main verts](https://github.com/Riri-the-one/Projet_Spring_Cloud/actions/runs/43782765329).

[BookingConfirmed reste une proposition](ARIJ_TICKET_BOOKING_CONTRACT.md).
À valider : paiement réellement admissible, résolution fiable du customerId, stabilité
des lignes/quantités/versions, annulation/remboursement et invalidation des billets,
outbox/publication fiable, replays et messages en désordre. Aucun consommateur Kafka
n'est connecté. Le cœur d'émission est préservé ; son branchement demandera un contrat
accepté, pas un paiement fictif déguisé en confirmation réelle.
