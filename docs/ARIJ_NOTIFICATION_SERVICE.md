# Notification Service autonome — guide Arij

Notification est intégré avec les overlays Ticket PostgreSQL de la PR #9 fusionnée.
Pour le lancement complet et les résultats de clôture, voir [livraison finale](ARIJ_FINAL_DELIVERY.md).
Gateway expose uniquement GET /api/notifications/me et GET /api/notifications/{UUID}.
Les opérations d'administration restent accessibles directement en local avec ADMIN.
Les sources métier Customer, Ticket, Event, Booking et Payment ne sont pas modifiées.

## Ce qui fonctionne

Le module Spring MVC stocke une inbox durable, l'historique et les états PENDING,
RETRY, SENT ou DEAD. L'UUID événementiel est unique ; un rejeu identique ne crée pas
de notification supplémentaire. Un contenu différent pour le même UUID est refusé.
Les dates sont normalisées en microsecondes pour éviter les faux conflits après lecture
PostgreSQL. Une transaction d'insertion termine avant le retour du listener Kafka.

Un worker verrouille chaque ligne et applique trois tentatives par défaut avec délai.
Le canal simulé crée un reçu unique dans la même transaction que SENT : un crash avant
commit ne laisse ni faux succès ni reçu perdu. Deux workers ne livrent pas deux fois.
Une panne simulée est configurable par `ticketflow.notification.simulated-fail-first`.
Au-delà de la limite, DEAD est conservé et un administrateur peut reprendre le traitement.
Les tentatives totales et reprises manuelles restent dans l'historique.

Le scheduler reprend automatiquement les PENDING/RETRY après redémarrage. Il ne
contacte jamais Booking et ne peut donc ni annuler une réservation ni modifier le stock.
Le reçu est uniquement une simulation locale, pas une preuve d'envoi d'e-mail/SMS.
Un vrai canal externe nécessitera une idempotence fournisseur ; aucune garantie globale
« exactly once » entre Kafka, une base et un fournisseur externe n'est prétendue.

## Kafka et événements en erreur

Le mode par défaut est **off** : aucun consommateur Booking/Payment et aucun faux
paiement. Le seul adaptateur exécutable accepte le mode explicite **probe**, topic fixe
`ticketflow.infra.notification.probe.v1`, groupe isolé `ticketflow-notification-probe-*`.
Il n'accepte que NotificationProbe/source infra-test/version 1, avec une taille bornée.
Le mode business est refusé tant que les contrats ne sont pas validés.

Consommation par enregistrement, auto-commit désactivé. Les messages invalides ou
contradictoires sont inscrits en quarantaine avec topic/partition/offset, empreinte et
raison, **sans corps brut**. L'offset n'est acquitté qu'après cette écriture durable.
Une panne de stockage échappe au listener ; après trois essais par cycle l'erreur reste
non récupérée et sera rejouée jusqu'au retour de la base, plutôt que perdre le message.
Les tentatives d'envoi, elles, sont bornées et finissent en DEAD.
Voir [Spring Kafka](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html).

La quarantaine n'est pas une DLT Kafka avec corps brut. Les messages mal formés exigent
une analyse puis une republication corrigée par le producteur autorisé ; ils ne sont
pas rejoués aveuglément. Un conflit d'UUID conserve toujours la notification initiale.

## PostgreSQL et secrets

Base/compte **ticketflow_notification** dans le serveur PostgreSQL 18.7 existant.
Secret notification.password aléatoire, distinct de tous les secrets existants, ignoré
par Git et Docker build. Le service ne reçoit que ce secret via Config Tree.
Flyway V1 crée l'inbox et les index ; V2 crée les reçus et la quarantaine. DDL validate,
clean interdit, pas de baseline automatique. Le bootstrap initial n'est pas modifié.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Provision-NotificationPostgresql.ps1
# Même procédure sur cluster neuf ou existant ; répétition non destructive
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Provision-NotificationPostgresql.ps1
$n = @('compose', '-f', 'infra/docker-compose.yml', '-f', 'infra/docker-compose.postgresql.yml', '-f', 'infra/docker-compose.notification.yml', '--profile', 'postgresql', '--profile', 'notification')
docker @n build notification-service
docker @n up -d --no-deps --wait --wait-timeout 180 notification-service
node tools/Test-NotificationHttp.mjs
```

Le provisionnement peut recréer uniquement PostgreSQL pour monter le nouveau fichier,
en conservant son volume : prévoir une courte interruption de connexion. Si le secret
Ticket existe déjà, ses overlays PostgreSQL intégrés conservent son montage serveur,
sans reprendre ses migrations/routes. Aucun secret ni volume existant n'est supprimé.
Rôle/base partiels, privilèges inattendus ou secret perdu : refus, inspection manuelle,
aucune rotation automatique. Vérification CONNECT dans les deux sens pour les domaines
présents, y compris Ticket lorsqu'il existe. Ne jamais utiliser down -v ou remove-orphans.

Pour une base non vide sans historique Flyway : ne pas forcer le baseline. Sauvegarder,
restaurer une copie et faire revoir une migration d'adoption. Le profil demo-h2 reste
disponible pour les tests locaux ; il n'offre aucune persistance après redémarrage.
Depuis IntelliJ PostgreSQL : profil postgresql, URL 127.0.0.1:5433/ticketflow_notification,
compte ticketflow_notification, mot de passe lu du seul fichier local dans une variable
d'environnement SPRING_DATASOURCE_PASSWORD privée, jamais une configuration partagée.

## API et droits

Port local 127.0.0.1:8085, aucune nouvelle route Gateway dans cette branche indépendante.
JWT RS256/issuer/audience/exp/nbf/sub vérifiés dans le service ; aucun en-tête X-* n'accorde
de droit. Le profil propre est résolu via Customer avec le JWT validé, pas par un ID Angular.

| API | Autorisation |
| --- | --- |
| GET /api/notifications/me?page=0 | CUSTOMER/ADMIN, son propre profil, pages de 20 |
| GET /api/notifications/{eventId} | propriétaire ou ADMIN |
| GET /api/notifications?page=0 | ADMIN |
| GET /api/notifications/quarantine?page=0 | ADMIN, métadonnées sans corps brut |
| POST /api/notifications/{eventId}/retry | ADMIN, uniquement DEAD |
| GET /actuator/health | public, réponse minimale |

Aucune API d'insertion arbitraire. ORGANIZER seul est refusé. 400 paramètre invalide,
401 JWT absent/invalide, 403 rôle/propriété, 404 notification/profil absent, 409 reprise
d'un état non DEAD, 503 résolution Customer indisponible. La politique est aussi dans
la couche service, pas uniquement dans le contrôleur.

## Tests et démonstration honnête

```powershell
.\mvnw.cmd -B -ntp verify
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Test-NotificationInfra.ps1
node tools/Test-NotificationHttp.mjs
```

La suite infra démarre le broker existant, crée explicitement le topic technique et
utilise un schéma notification_test_<uuid> séparé du runtime public. Elle produit des
fixtures NotificationProbe, vérifie consommation/rejeu/quarantaine, concurrence,
droits, reprises et persistance après redémarrage PostgreSQL et d'une nouvelle JVM.
Le test scheduler vérifie aussi la reprise automatique sans appel manuel de livraison.
Les fixtures restent dans le schéma de test ; le runtime public n'est pas peuplé.
Le test HTTP utilise de vrais jetons Keycloak Code + PKCE et vérifie une liste vide.
Ne pas présenter cette démonstration technique comme une réservation réellement payée.

Les versions sont gérées par Boot 4.0.8 : Spring Kafka 4.0.7 et client 4.1.2, testé contre
le broker officiel 4.4.0 déjà fourni. Aucun changement du BOM ou des autres services.
Le workflow Notification integration est indépendant des cinq jobs CI principaux.

## Ce qui attend le groupe

Résultats locaux du 16 novembre 2026 : 32 tests rapides Notification et 20 tests
PostgreSQL/Kafka réussis, puis reprise persistante rejouée après redémarrage PostgreSQL
et d'une JVM. Les tests HTTP avec Keycloak réel passent ; Booking reste fonctionnel
avec Notification arrêté. Les confirmations et historiques de test sont exclusivement
dans des schémas isolés ; la démonstration runtime conserve une liste vide.

[Contrats proposés](ARIJ_NOTIFICATION_CONTRACTS.md) : Salma doit confirmer les événements,
le paiement admissible, l'identité fiable, les annulations/remboursements et l'outbox.
L'intégration métier reste bloquée ; le stockage, le canal simulé et les reprises sont
fonctionnels indépendamment. Les préférences et vrais canaux demanderont leurs propres
contrats de coordonnées/consentement. Kafka local reste PLAINTEXT sans ACL ; TLS/ACL,
sauvegardes/restauration et politique de rétention des données sont nécessaires avant
un déploiement de production.
