# Contrats Notification à valider avec Salma

**Propositions uniquement : aucun contrat Booking/Payment accepté ni consommateur
métier activé.** Audit du 16 novembre 2026 : main à b0fc1f5 et branche distante
feature/salma-booking à 4c08ab8 ; aucun producteur Kafka ou contrat définitif détecté.
La PR Ticket #9 est désormais fusionnée ; ses capacités de persistance ne remplacent
pas la validation externe du contrat métier.

Réutiliser les topics existants, avec bookingId comme clé et les conventions
`eventId` UUID, `eventType`, `schemaVersion`, `source`, `occurredAt`, `aggregateId`,
`aggregateVersion`, `correlationId`, `data`. L'UUID de message n'est pas l'eventId
métier du catalogue.

| Besoin | Topic proposé | Type à confirmer | Données minimales |
| --- | --- | --- | --- |
| Confirmation | ticketflow.booking.events.v1 | BookingConfirmed | bookingId, customerId fiable, état confirmé, référence de paiement admissible |
| Échec | ticketflow.payment.events.v1 ou Booking, décision Salma | PaymentFailed / BookingPaymentFailed | bookingId, customerId fiable, code de résultat non sensible |
| Annulation | ticketflow.booking.events.v1 | BookingCancelled | bookingId, customerId fiable, version et date métier |

Ne pas produire ces types en utilisant le canal technique comme s'il s'agissait de
paiements réels. Ne pas notifier deux fois un même échec à partir de deux événements
différents : Salma doit décider l'événement faisant autorité et stabiliser l'eventId.
La déduplication actuelle garantit un traitement par UUID de message ; elle ne décide
pas qu'un autre UUID représente le même fait métier.

À valider avec Salma :

1. Résolution du vrai Customer via le JWT validé, sans customerId Angular ni e-mail
   comme clé d'identité ; source et droits du producteur Kafka.
2. Paiement admissible, cas gratuit, transitions et dates, versions et ordre des messages.
3. Publication après commit par outbox ; même UUID et même contenu lors des replays.
4. Lignes/quantités/eventId/validUntil nécessaires à Ticket ; voir le contrat Ticket
   [BookingConfirmed proposé](ARIJ_TICKET_BOOKING_CONTRACT.md).
5. Annulation, remboursement, compensation et invalidation des billets. Notification
   n'annule jamais une réservation et ne change aucun stock.
6. Retries/quarantaine, rétention, minimisation des données et contrats compatibles.

Notification ne lit aucune base Customer/Booking/Payment. Il conserve les IDs métier
et résout l'identité de lecture par l'API HTTP Customer. Le canal courant est simulé :
aucun destinataire e-mail/téléphone, aucun fournisseur payant. Un vrai canal nécessitera
un contrat de coordonnées/préférences, des consentements et une clé d'idempotence côté
fournisseur. Les préférences ne sont pas demandées par RF-07 du cahier versionné ; elles
ne sont pas inventées dans ce lot.

L'adaptateur technique n'accepte que `NotificationProbe`, source `infra-test`, sur
`ticketflow.infra.notification.probe.v1`. Ses données kind, bookingId, customerId et
occurredAt sont des fixtures de test. Le mode business échoue volontairement au
démarrage ; ajouter un adaptateur métier demandera une revue du contrat accepté.
