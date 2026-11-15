# Contrats Kafka à valider avec Salma

Le broker est prêt. Cette note propose les conventions avant le développement des
producteurs ; elle ne constitue pas une implémentation métier.

| Responsable futur | Topic | Clé proposée | Types proposés |
| --- | --- | --- | --- |
| Manar / Event | `ticketflow.event.events.v1` | identifiant métier de l'événement en chaîne | `EventPublished`, `EventClosed` |
| Salma / Booking | `ticketflow.booking.events.v1` | `bookingId` en chaîne | `booking.created`, `booking.cancelled`, `BookingConfirmed` proposé pour Ticket |
| Salma / Payment simulé | `ticketflow.payment.events.v1` | `bookingId` en chaîne | `payment.succeeded`, `payment.failed` |
| Arij / Ticket ultérieur | `ticketflow.ticket.events.v1` | `bookingId` en chaîne | `ticket.issued` |

Docker : `bootstrap.servers=kafka:9092`. IntelliJ : `127.0.0.1:29092`.
Les quatre topics ont trois partitions, réplication 1 et rétention sept jours en local.
Leur création relève de l'infrastructure : exécuter explicitement `init-topics.sh`
après le démarrage du broker, comme indiqué dans le guide. Le démarrage du broker
ne crée pas les topics métier. Les tests parcourent tout le manifeste, y compris Event.
Manar reste responsable des futurs producteurs `EventPublished` et `EventClosed`.
Aucun code Event Service n'est modifié.

## Enveloppe JSON UTF-8 proposée

Le [contrat BookingConfirmed pour Ticket](ARIJ_TICKET_BOOKING_CONTRACT.md) est proposé
pour validation de Salma ; aucun consommateur métier n'est connecté dans ce premier lot.

Voir [le schéma versionné](../infra/kafka/contracts/event-envelope-v1.schema.json).
Champs obligatoires : `eventId` UUID, `eventType`, `schemaVersion=1`, `source`,
`occurredAt` ISO-8601 UTC, `aggregateId` en chaîne, `correlationId` et `data` objet.
`aggregateId` désigne l'événement, la réservation, le paiement ou le ticket du producteur concerné.
Pour Event, la clé Kafka est l'identifiant métier de l'événement ; `eventId` dans
l'enveloppe est l'UUID unique du message, distinct de cet identifiant métier.
La clé Kafka peut donc être le bookingId alors que l'aggregateId est un paymentId.
`aggregateVersion` est recommandé pour détecter les événements obsolètes.
Salma reste propriétaire des champs de `data` et des moments de publication.

Ne pas inclure de JWT, mot de passe ou données personnelles inutiles. Les identifiants
techniques sont préférables aux e-mails. Le schéma est documentaire : le broker ne
valide pas le JSON et aucun Schema Registry n'est déployé.

## Garanties à prévoir avec les producteurs

- Producteur : acquittements `all`, idempotence et délais bornés. Conserver le même
  `eventId` pour la relivraison d'un événement logique.
- Publication fiable après validation de la transaction métier : définir avec Salma
  une stratégie outbox. L'idempotence Kafka seule ne rend pas atomiques base et broker.
- Consommateur futur : dédoublonner par `eventId`, acquitter après traitement réussi,
  utiliser un groupe propre à l'application, par exemple `ticketflow-notifications-v1`.
- L'ordre n'est garanti que dans une partition d'un topic, jamais entre Booking et
  Payment. Définir les transitions tolérées, reprises, retries et éventuelle DLT avant
  le développement ; ces mécanismes ne sont pas encore implémentés.

À confirmer ensemble : noms définitifs, clé et version d'agrégat, schémas de `data`,
publication après commit, politique de rétention et compatibilité des évolutions.
Aucun changement Booking/Payment, Ticket ou Notification n'est inclus dans cette phase.
