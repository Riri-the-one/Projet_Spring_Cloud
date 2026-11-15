# BookingConfirmed vers Ticket — proposition à valider avec Salma

**Statut : PROPOSÉ, non accepté, aucun consommateur de production connecté.**
L'audit des branches et du code disponible ne trouve aucun contrat définitif
`BookingConfirmed`. Booking possède actuellement PENDING/CONFIRMED/CANCELLED mais ne
produit pas de confirmation après paiement ; son customerId reste transmis par le
client. Il ne peut donc pas encore alimenter Ticket en confiance.

## Responsabilités

Salma contrôle la confirmation après paiement admissible, l'identité fiable du client,
les lignes et quantités ainsi que le stock. Arij contrôle l'émission et l'idempotence
des billets. Ticket ne réserve ni ne libère de capacité et n'accepte pas de création
de billet depuis Angular. Les confirmations fictives existent uniquement dans les tests.

## Transport et enveloppe proposés

Réutiliser `ticketflow.booking.events.v1`, clé Kafka = **bookingId en chaîne**.
`eventId` de l'enveloppe est l'UUID unique du message ; ce n'est pas l'identifiant
métier de l'événement du catalogue. `aggregateId` vaut le bookingId en chaîne.
Les noms et champs restent soumis à validation de Salma.

```json
{
  "eventId": "c12054b7-cecb-43db-814b-849ce2b26b07",
  "eventType": "BookingConfirmed",
  "schemaVersion": 1,
  "source": "booking-service",
  "occurredAt": "2026-11-15T10:00:00Z",
  "aggregateId": "123",
  "aggregateVersion": 2,
  "correlationId": "checkout-demo-123",
  "data": {
    "bookingId": 123,
    "customerId": 456,
    "status": "CONFIRMED",
    "paymentStatus": "SUCCEEDED",
    "paymentId": "payment-demo-1",
    "lines": [
      { "lineId": "line-1", "eventId": 789, "quantity": 2, "validUntil": "2026-12-01T23:00:00Z" }
    ]
  }
}
```

Cet exemple est documentaire ; aucun outil ne le publie. L'identifiant eventId dans
chaque ligne désigne l'événement métier, distinct de l'eventId UUID de l'enveloppe.
Le code interne nomme ce dernier `messageId` pour éviter l'ambiguïté.
Les IDs métier Long JSON sont proposés ici ; coordonner leur sérialisation avec Salma
et les clients JavaScript avant validation (précision des grands entiers).

## Conditions de confiance et admission

- Booking résout d'abord le vrai customerId à partir du JWT via Customer. Aucun ID
  Angular ni rapprochement par e-mail n'est une preuve d'identité.
- Publier seulement après transaction de confirmation validée et paiement admissible.
  Définir avec Salma une outbox pour éviter les écarts entre commit et publication.
- Le premier lot interne accepte uniquement CONFIRMED + SUCCEEDED, une référence de
  paiement, une version positive, des lignes distinctes et quantités positives.
  Le cas gratuit, les autres moyens de paiement et leurs preuves restent à décider.
- `lineId` est stable dans la réservation. Il ne représente pas un siège numéroté :
  Ticket génère un index technique 1..quantity par ligne. Confirmer si les lignes
  multiples et la date limite sont disponibles dans le futur modèle Booking.
- `validUntil` doit suivre l'instant de confirmation. Une livraison tardive peut créer
  un billet déjà expiré ; la vérification de validité le refuse.
- Les limites locales sont 100 lignes et 1000 billets par confirmation. Les confirmer
  avec Salma ; elles protègent contre une allocation excessive.

## Idempotence et conflits

Le bookingId est la clé unique d'un lot d'émission. Chaque billet a également une
contrainte unique `(bookingId, lineId, seatIndex)`. Le premier UUID de message est
conservé et unique. Le contenu admis est empreinté de façon canonique, indépendamment
de l'ordre des lignes et de l'UUID du message.

Un rejeu identique rend les mêmes IDs et codes, même avec un nouvel UUID. Conserver
`occurredAt` et `aggregateVersion` de la confirmation métier, indépendamment de la date
de relivraison. Un changement de client, quantité, ligne, échéance, référence de paiement, version ou instant de
confirmation provoque un conflit ; il ne réécrit jamais les billets. Les nouvelles
confirmations métier d'une réservation déjà émise nécessiteront un contrat distinct.
Les UUID supplémentaires de rejeu ne sont pas tous enregistrés dans une inbox : cette
étape sera à définir avec le consommateur. La protection contre les doublons d'émission
repose sur bookingId, pas uniquement sur la déduplication des messages.

Deux émissions concurrentes sont arbitrées par la base dans des transactions séparées.
Le perdant relit le gagnant après rollback ; aucune transaction en échec n'est réutilisée.

## Avant toute connexion Kafka

Valider avec Salma : nom/type et version, source de confiance, schéma des lignes,
admission du paiement, identité fiable, outbox, replay et messages en désordre.
Définir aussi la réception des annulations/remboursements et la révocation des billets,
les retries, la quarantaine/DLT et l'acquittement après commit.
Des rôles de producteurs/ACL seront nécessaires hors du réseau local de confiance.
Le champ `source` seul ne prouve pas l'origine d'un message Kafka.
Aucun listener, producteur TicketIssued ou consommateur Notification n'est ajouté ici.
