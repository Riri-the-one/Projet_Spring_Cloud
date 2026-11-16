# Intégration et qualité Arij — 16 novembre 2026

Les PR #9 Ticket PostgreSQL/Gateway, #10 Notification et #11 audit TP3 sont fusionnées. Ce bilan remplace la combinaison provisoire des branches en worktree.

| Lot | État vérifié | Limite |
| --- | --- | --- |
| Customer sécurisé/PostgreSQL | JWT, propriété, Flyway, persistance | Pas de migration Event/Booking |
| PostgreSQL commun | Cinq comptes métier isolés, vingt refus CONNECT | Sauvegarde de production à organiser |
| Kafka commun | KRaft, quatre topics métier, tests techniques persistants | Pas de confirmation de paiement inventée |
| Ticket | Émission atomique/idempotente, PostgreSQL, JWT et Gateway | Contrat BookingConfirmed à valider avec Salma |
| Notification | Inbox durable, déduplication, reprises, quarantaine et JWT | Canal simulé ; consommateurs métier désactivés |
| TP3 Git externe | Audit et procédure prêts, mode native observé | URL/droits du véritable dépôt manquants |

## Preuves reproductibles

La CI de main après #10 au commit fec363e est verte : [cinq jobs communs](https://github.com/Riri-the-one/Projet_Spring_Cloud/actions/runs/43792662953) et [intégration Notification](https://github.com/Riri-the-one/Projet_Spring_Cloud/actions/runs/43792662949).
Le build complet comporte **243 tests** : Customer 58, Gateway 68, Discovery 2, Config 1, Event 21, Booking 19, Ticket 42, Notification 32. Les suites PostgreSQL séparées couvrent Customer 42, Ticket 30 et Notification/Kafka 19, plus une relecture Ticket/Notification dans une nouvelle JVM après redémarrage.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Test-ArijQuality.ps1
```

Ce contrôle a été réexécuté après fusion de #9/#10 : onze conteneurs sains, ports publiés sur loopback uniquement, trois volumes conservés, cinq bases isolées, quatre topics et authentification OIDC Code + PKCE des trois rôles. Il ne crée aucune donnée ni aucun message. Les secrets Ticket et Notification doivent être montés dans PostgreSQL.
Event et Booking restent en H2 côté application : leurs bases provisionnées ne constituent pas une migration.

## Démonstration

Les guides [Ticket](ARIJ_TICKET_PERSISTENCE_GATEWAY.md), [Notification](ARIJ_NOTIFICATION_SERVICE.md), [contrats Notification](ARIJ_NOTIFICATION_CONTRACTS.md), [contrat Ticket](ARIJ_TICKET_BOOKING_CONTRACT.md) et [TP3](ARIJ_TP3_GIT_AUDIT.md) restent les références spécialisées.

- `node tools/Test-KeycloakIntegration.mjs` : Customer, catalogue, réservation PENDING puis annulation ; aucun paiement confirmé.
- `node tools/Test-KeycloakIntegration.mjs --ticket-gateway` : lectures, 401/403/404 et absence de création arbitraire.
- `node tools/Test-NotificationHttp.mjs` : contrôles JWT et administration directe.
- Les scripts PostgreSQL/Kafka utilisent des schémas et messages techniques isolés. Ils peuvent redémarrer PostgreSQL : les exécuter hors écritures métier.

## Coordination et sécurité restante

| Responsable | Livraison attendue |
| --- | --- |
| Salma | Identité Customer fiable, paiement admissible, BookingConfirmed validé, lignes/quantités, annulation/remboursement et publication fiable/outbox |
| Manar | Propriété organisateur, visibilité des brouillons, migration Event, EventPublished/EventClosed et Angular |
| Zainab/propriétaire TP3 | URL, droits et label réels du dépôt Config, changement non sensible puis refresh |
| Arij après contrats acceptés | Adaptateurs métier Ticket/Notification et invalidation selon les règles convenues |

La branche feature/salma-booking à 4c08ab8 ne fournit pas encore de producteur/contrat définitif. Aucun métier des autres membres n'a été modifié.
Les contrôles métier Event/Booking restent à leurs propriétaires. Kafka local est PLAINTEXT sans ACL. Les codes Ticket et secrets locaux nécessitent une protection adaptée avant production. Le reçu Notification simulé est atomique localement, sans promettre un exactly-once global chez un fournisseur. Aucun consommateur métier non validé n'est actif ; le probe PostgreSQL exige un schéma de test isolé.
