# Livraison finale Arij — TicketFlow

Ce document est le point d'entrée de la contribution d'Arij. Les guides spécialisés restent les références de configuration. Les preuves ci-dessous concernent du code fusionné, des tests locaux réels et les jobs GitHub Actions, pas une intégration métier inventée.

## Ce qu'Arij a personnellement réalisé

- Mise en place de CI, Gateway à routes explicites, découverte Eureka et environnement Docker reproductible.
- Fondation Keycloak/OIDC : connexion Authorization Code + PKCE S256, validation RS256, issuer, audience, exp/nbf/sub et rôles indépendants CUSTOMER, ORGANIZER, ADMIN.
- Customer sécurisé directement dans le service : liaison immuable et unique (iss, sub), profil personnel, droits administrateur, contrôles de propriété et résolution interne minimale de customerId. Migration PostgreSQL/Flyway, concurrence et intégrité.
- Infrastructure PostgreSQL commune : secrets locaux distincts, comptes par domaine, absence de CONNECT entre domaines, volume persistant et provisionnement explicite non destructif des nouvelles bases.
- Infrastructure Kafka KRaft : listeners Docker/Windows, topics versionnés, volume et tests techniques réels de publication, consommation, redémarrage et relecture.
- Ticket : un billet par place, UUID et code aléatoire 256 bits sans donnée personnelle, lots atomiques et idempotents, concurrence protégée par contraintes SQL, états ISSUED/USED/VOIDED/EXPIRED, PostgreSQL/Flyway et consultation propriétaire via Gateway.
- Notification : inbox durable, déduplication par UUID, empreinte de contenu, canal simulé transactionnel, scheduler, retries bornés, DEAD/reprise administrateur, quarantaine et consultation JWT/propriétaire. Aucun échec Notification n'annule Booking.
- Audit du vrai TP3 Git, contrôles de qualité, procédures Windows et préparation des contrats du groupe.

Les transitions USED/VOIDED sont disponibles et testées dans le domaine Ticket ; aucune API de scan d'entrée, annulation ou remboursement non convenue n'est exposée. EXPIRED est calculé à la lecture selon validUntil. La vérification HTTP de validité ne consomme pas le billet.

## Livraison GitHub

Les PR #1 à #8 étaient déjà fusionnées. Cette clôture intègre #9, #10, #11 et #12 après revue et CI, puis une PR de livraison finale pour les lectures Notification Gateway, le script et ce guide.

| PR | Contribution |
| --- | --- |
| [#1](https://github.com/Riri-the-one/Projet_Spring_Cloud/pull/1) / [#2](https://github.com/Riri-the-one/Projet_Spring_Cloud/pull/2) | CI, Gateway et Docker |
| [#3](https://github.com/Riri-the-one/Projet_Spring_Cloud/pull/3) | Keycloak/JWT |
| [#4](https://github.com/Riri-the-one/Projet_Spring_Cloud/pull/4) / [#7](https://github.com/Riri-the-one/Projet_Spring_Cloud/pull/7) | Customer sécurisé puis PostgreSQL |
| [#5](https://github.com/Riri-the-one/Projet_Spring_Cloud/pull/5) / [#6](https://github.com/Riri-the-one/Projet_Spring_Cloud/pull/6) | PostgreSQL et Kafka communs |
| [#8](https://github.com/Riri-the-one/Projet_Spring_Cloud/pull/8) / [#9](https://github.com/Riri-the-one/Projet_Spring_Cloud/pull/9) | Ticket, persistance et Gateway |
| [#10](https://github.com/Riri-the-one/Projet_Spring_Cloud/pull/10) | Notification autonome |
| [#11](https://github.com/Riri-the-one/Projet_Spring_Cloud/pull/11) | Audit TP3 et outil de vérification |
| [#12](https://github.com/Riri-the-one/Projet_Spring_Cloud/pull/12) | Contrôles d'intégration et qualité |
| [#13](https://github.com/Riri-the-one/Projet_Spring_Cloud/pull/13) — livraison finale | Routes de lecture Notification, reprise Kafka, résilience Gateway, démonstration reproductible et guide central ; statut effectif sur GitHub |

Une fusion du document d'audit TP3 ne signifie pas que le dépôt Git externe a été trouvé. La procédure reste prête mais sa preuve externe est bloquée.

## Démarrage Windows et présentation

Prérequis : Docker Desktop lancé, Java 21, Node 22+ et Git. Depuis la racine :

```powershell
# Premier déploiement ou après une modification des sources Arij :
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Run-ArijDemo.ps1 -Build
# Déploiement existant, contrôles et parcours fictif PENDING/annulation :
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Run-ArijDemo.ps1 -Showcase
# Tests techniques isolés, avec redémarrages PostgreSQL/Kafka :
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Run-ArijDemo.ps1 -TechnicalTests
# Parcours existant avec Notification arrêté, puis avec Kafka arrêté (restaurés ensuite) :
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Run-ArijDemo.ps1 -FailureTests
# Audit sans création de données, sur environnement déjà démarré :
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Test-ArijQuality.ps1
.\mvnw.cmd -B -ntp verify
```

Le script conserve tous les secrets existants et les trois volumes. Il initialise seulement les fichiers manquants, refuse les états de provisionnement incohérents et n'effectue aucune rotation automatique. Les bases Ticket/Notification sont provisionnées explicitement, sur cluster neuf comme existant, avant le démarrage des applications. Les quatre topics sont créés explicitement par init-topics.sh ; ils ne sont pas créés automatiquement au démarrage du broker.

Le mode Showcase utilise les comptes fictifs du realm versionné, par véritable flux Code + PKCE ; ni mots de passe ni JWT ne sont imprimés. Il crée des données de démonstration Customer/catalogue/réservation PENDING puis annulation. Il ne produit aucun paiement réussi ni billet ou notification métier. Ne pas exécuter TechnicalTests pendant des écritures métier : PostgreSQL/Kafka peuvent redémarrer. Les schémas de test et messages techniques sont isolés du runtime.

Le mode pédagogique H2 reste disponible avec Compose de base et l'overlay Ticket sans PostgreSQL Ticket ; Notification dispose de son profil explicite demo-h2. Ne pas retirer un overlay PostgreSQL en pensant continuer à lire les données persistantes : cela change volontairement le stockage. Ne jamais utiliser create-drop/update sur les bases persistantes.

`tools/Test-ArijH2Profiles.ps1` démarre aussi Ticket et Notification H2 dans des conteneurs temporaires sans secret PostgreSQL, sans volume ni port publié et sans inscription Eureka, puis vérifie health et 401. Il utilise le Config Server du déploiement local.
Lors du premier passage des anciens identifiants Docker Eureka aux identifiants stables, un redémarrage de `discovery-server` permet de vider son ancien registre en mémoire ; les clients se réinscrivent. Cette opération ne touche aucune base ni volume. Les identifiants fixes conviennent au mode local à une instance par service, pas à plusieurs réplicas.

### Ordre oral conseillé

1. Montrer les conteneurs et Eureka : les services s'enregistrent, Gateway ne publie que les routes prévues.
2. Expliquer compte Keycloak versus profil Customer et montrer 401 sans JWT, 403 avec ORGANIZER, lecture personnelle autorisée.
3. Montrer les bases et vingt refus CONNECT, Flyway/validate et la conservation après redémarrage.
4. Montrer les quatre topics et un essai Kafka technique persistant.
5. Présenter Ticket : tests de plusieurs places, concurrence/rejeu et propriété ; lecture runtime sans fabrication d'une confirmation.
6. Présenter Notification : tests Kafka isolés, déduplication, panne du canal, DEAD/reprise et persistance ; canal simulé annoncé clairement.
7. Terminer par les contrats encore attendus et le blocage TP3. Ne pas annoncer un achat payé de bout en bout.

## Services, ports et stockage

Toutes les publications hôte sont sur 127.0.0.1 uniquement.

| Composant | Port Windows | Rôle |
| --- | --- | --- |
| Gateway | 8080 | Entrée des API autorisées |
| Customer | 8081 | Diagnostic local et endpoint interne |
| Event / Booking | Aucun port hôte | 8082 / 8083 dans Docker |
| Ticket / Notification | 8084 / 8085 | Diagnostic local protégé |
| Config / Eureka | 8888 / 8761 | Infrastructure locale, aucune route Gateway |
| Keycloak | 8180 | Realm ticketflow, client public ticketflow-web |
| PostgreSQL | 5433 | 5432 dans Docker |
| Kafka | 29092 | kafka:9092 dans Docker, contrôleur non publié |

Versions déjà fixées : PostgreSQL 18.7-bookworm, Apache Kafka 4.4.0 KRaft, Keycloak 26.8.2. Les secrets PG sont dans infra/postgresql/.local, ignorés par Git et le build Docker ; chaque application ne reçoit que son propre secret.

| Base / compte métier | Responsable | Stockage de l'application |
| --- | --- | --- |
| ticketflow_customer | Arij | PostgreSQL, Flyway et validate |
| ticketflow_event | Manar | Base provisionnée ; application encore H2 |
| ticketflow_booking | Salma | Base provisionnée ; application encore H2 |
| ticketflow_ticket | Arij | PostgreSQL, Flyway V1/V2 et validate |
| ticketflow_notification | Arij | PostgreSQL, Flyway V1/V2 et validate |

Le compte d'administration PostgreSQL ne sert pas aux applications. Les cinq comptes métier ne peuvent pas se connecter aux quatre autres bases. Les volumes nommés PostgreSQL, Kafka et Keycloak sont conservés. Aucun accès direct entre bases métier n'est utilisé.

## Kafka et contrats

| Topic | Producteur responsable |
| --- | --- |
| ticketflow.event.events.v1 | Manar : EventPublished / EventClosed à fournir |
| ticketflow.booking.events.v1 | Salma : confirmations/annulations à valider |
| ticketflow.payment.events.v1 | Salma : résultats et remboursements à valider |
| ticketflow.ticket.events.v1 | Arij : futurs événements Ticket, pas encore publiés |
| ticketflow.infra.notification.probe.v1 | Tests techniques Notification uniquement |

Les quatre topics métier ont trois partitions, réplication 1 et rétention sept jours en développement. La clé est l'ID de l'agrégat (bookingId ou eventId métier) ; l'eventId UUID d'enveloppe identifie un message unique. Une chaîne source n'authentifie pas un producteur ; Kafka local n'a pas encore d'ACL/TLS.

**BLOQUÉ PAR CONTRAT EXTERNE** : les branches accessibles ne fournissent pas encore de BookingConfirmed accepté ni de producteur après paiement admissible. La branche feature/salma-booking à 4c08ab8 ne suffit pas. Les consommateurs métier Ticket et Notification restent désactivés ; ingress=business est refusé. Le probe Notification ne fonctionne en PostgreSQL que dans un schéma notification_test_UUID. Il ne remplit pas le runtime public.

À valider avec Salma :
- customerId fiable, résolu par JWT via GET /internal/customers/me ; jamais pris dans Angular.
- bookingId/version, UUID stable du message, paiement SUCCEEDED admissible et référence stable, occurredAt, lignes/quantités/eventId/validUntil.
- publication après commit fiable/outbox, même UUID/contenu au rejeu, ordre et versions.
- autorité unique pour les échecs, annulations/remboursements et invalidation des billets.

Les propositions détaillées restent [Ticket](ARIJ_TICKET_BOOKING_CONTRACT.md) et [Notification](ARIJ_NOTIFICATION_CONTRACTS.md). Elles ne sont pas présentées comme acceptées.

## API et droits

| API | Droits / effet |
| --- | --- |
| POST /api/customers | CUSTOMER/ADMIN : créer son propre profil ; 409 doublon |
| GET/PUT /api/customers/me | Profil lié au JWT ; 404 absent |
| GET/PUT /api/customers/{id} | Propriétaire ou ADMIN |
| GET /api/customers ; DELETE /api/customers/{id} | ADMIN ; suppression à coordonner avant données référencées |
| GET /internal/customers/me | JWT CUSTOMER/ADMIN, retourne seulement customerId ; aucune route Gateway |
| GET /api/tickets/me | CUSTOMER/ADMIN, profil personnel résolu via Customer |
| GET /api/tickets/{id} ; GET /api/tickets/bookings/{bookingId} | Propriétaire ou ADMIN |
| POST /api/tickets/{id}/validity | Propriétaire/ADMIN et code ; vérification sans émission/consommation |
| GET /api/notifications/me ; GET /api/notifications/{UUID} | CUSTOMER/ADMIN, propriété vérifiée localement |
| GET /api/notifications ; GET /api/notifications/quarantine ; POST /api/notifications/{UUID}/retry | ADMIN, accès direct local uniquement, refusés par Gateway |

Aucune création HTTP de Ticket/Notification n'est exposée. Les headers X-Roles/X-Customer-Id ne constituent jamais une identité. JWT validés localement dans Customer, Ticket et Notification, même pour un accès direct. Les erreurs 401/403/404/409/503 sont documentées dans les guides ; les pannes de résolution Customer échouent sans permettre d'usurper un propriétaire. Les health checks sont publics et minimaux.

## Tests et preuves

Le build complet final vérifié avant publication contient **262 tests** : Customer 58, Gateway 82, Discovery 2, Config 1, Event 21, Booking 19, Ticket 42 et Notification 37. Les nouveaux cas Gateway couvrent les lectures Notification, les rôles, le transfert Bearer, les headers falsifiés, CORS, les routes administratives refusées et trois connexions amont refusées (503 sans fuite d'adresse ni rejeu de commande). Cinq gardes supplémentaires imposent une isolation cohérente JDBC/Hibernate/Flyway pour le probe Notification.

Les suites distinctes PostgreSQL couvrent **42 Customer**, **30 Ticket**, **20 Notification/Kafka**, plus une relecture persistante Ticket et Notification dans une nouvelle JVM après redémarrage PostgreSQL. Le nouveau cas Notification arrête réellement le consommateur, publie un message technique, puis vérifie la reprise durable et la déduplication. Les scripts réexécutent aussi les tests rapides des modules ; ne pas additionner ces répétitions comme de nouveaux scénarios uniques.

Le contrôle d'exploitation vérifie onze conteneurs sains, cinq bases/comptes, vingt refus CONNECT, quatre topics métier, loopback, volumes et les trois vrais rôles OIDC. Les essais Kafka vérifient producteurs/consommateurs Docker et Windows, message conservé après redémarrage/recréation du broker et volume identique.
Le parcours Customer/catalogue/Booking PENDING puis annulation a réussi avec Notification arrêté, puis Kafka arrêté. Les deux services ont été restaurés et le contrôle des onze conteneurs a réussi à nouveau. Les lectures Notification directes et via Gateway ont été vérifiées avec de vrais JWT, sans injection métier.
Le scénario `--customer-postgresql` a vérifié les vrais JWT, 401/403/404/409, le rollback et le même profil après redémarrage de Customer puis PostgreSQL. Les procédures conservent l'ordre des montages Ticket/Notification afin de limiter les recréations inutiles du conteneur PostgreSQL.
Les tests H2 restent exécutés par Maven ; les tests PostgreSQL utilisent des schémas aléatoires dédiés. Aucun billet payé ou notification métier n'est injecté dans les tables runtime pour la présentation.

Les résultats détaillés de la session sont conservés localement sous target/arij-validation (ignoré par Git). Les workflows CI exécutent le build, les infrastructures PostgreSQL/Kafka, Customer PostgreSQL, Ticket PostgreSQL et Notification PostgreSQL/Kafka. Un échec de téléchargement Maven dans le premier essai Kafka #12 a été diagnostiqué et le job relancé ; aucune vérification n'a été désactivée.

Un redémarrage PostgreSQL de test avait chevauché un démarrage Notification : le déploiement a été relancé séquentiellement. Une ancienne adresse Eureka a aussi révélé une connexion refusée renvoyée 500 ; Gateway la traduit désormais en 503 sans rejouer la commande. Les identifiants Eureka Docker des quatre composants Arij sont stables après recréation. Les essais ne masquent pas ces premiers échecs ; ils valident leur correction et la reprise.

## TP3 Config Server Git

**BLOQUÉ — URL/DROITS DU DÉPÔT TP3 MANQUANTS.** Le Config Server réellement lancé est native ; /customer-service/default/main renvoie deux sources et version=null. CONFIG_GIT_URI est absent des environnements inspectés. Aucun dépôt externe officiel identifiable n'a été trouvé parmi les dépôts accessibles ; un dépôt privé peut exister sans accès.

```powershell
powershell -NoProfile -File tools/Test-Tp3ConfigGit.ps1
# Sans URL réelle : code 2 attendu, aucun faux dépôt ni fausse preuve.
```

Voir [audit TP3](ARIJ_TP3_GIT_AUDIT.md). La procédure exige une vraie URL/branche, compare le SHA servi et distant, contrôle les propriétés sensibles et prépare un refresh Customer authentifié ADMIN. L'audit des propriétés servies ne remplace pas un audit exhaustif de l'historique du dépôt externe. Le TP de Zainab n'a pas été refait.

## Checklist courte du groupe

**Manar** : conserver son compte/base Event ; fournir ses migrations avant activation PostgreSQL ; publier EventPublished/EventClosed selon l'enveloppe et la clé métier ; appliquer propriété/visibilité ; utiliser les routes Gateway avec PKCE.

**Salma** : conserver son compte/base Booking ; fournir migrations et producteurs fiables ; résoudre Customer via le bearer utilisateur ; faire valider BookingConfirmed, échec, annulation et remboursement ; ne pas recréer Kafka/PostgreSQL/Keycloak. Ticket/Notification ne modifient jamais le stock.

**Zainab/propriétaire TP3** : fournir l'URL officielle, droits et label ; démontrer un changement non sensible et le refresh. IA/MCP/Telegram restent hors de cette livraison.

## Limites et clôture

La partie autonome Customer/infrastructure/Ticket/Notification est opérationnelle et testée. La connexion métier Ticket/Notification reste sous réserve des contrats du groupe. Les préférences ne sont pas exigées par RF-07 du cahier versionné ; aucun contrat de coordonnées ou fournisseur n'est inventé. Le canal simulé suffit à la démonstration locale, sans envoi e-mail/SMS.

Event/Booking nécessitent encore leurs contrôles de propriété et migrations. Le JWT utilisateur de l'endpoint interne prouve l'utilisateur, pas l'identité technique de Booking. Avant production : TLS et ACL Kafka, secrets gérés, sauvegarde/restauration, protection des codes Ticket, rétention et observabilité. Les actions GitHub v4 produisent un avertissement de dépréciation à traiter séparément ; le build reste vert sans changement de versions non nécessaire.
