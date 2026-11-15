# PostgreSQL — coordination des prochaines migrations

Répartition officielle : Arij — Customer sécurisé, Ticket, infrastructure PostgreSQL/Kafka,
Notification et coordination ; Manar — Event, données/migrations Event, catalogue et authentification
Angular ; Salma — Booking, Payment simulé, événements Kafka métier et interfaces client ;
Zainab — MCP public, assistant IA et Telegram public au périmètre réduit.

Cette PR fournit seulement trois bases et comptes de développement. Les services restent en H2.

| Responsable | Base / compte | Secret local | URL dans Docker |
|---|---|---|---|
| Manar | `ticketflow_event` | `.local/event.password` | `jdbc:postgresql://postgres:5432/ticketflow_event` |
| Salma | `ticketflow_booking` | `.local/booking.password` | `jdbc:postgresql://postgres:5432/ticketflow_booking` |
| Arij, prochaine étape | `ticketflow_customer` | `.local/customer.password` | `jdbc:postgresql://postgres:5432/ticketflow_customer` |

Les chemins de secrets sont sous `infra/postgresql/`. Depuis Windows/IntelliJ, remplacer l'hôte
et le port par `127.0.0.1:5433` (ou le port local configuré). Ne jamais utiliser `ticketflow_admin`
dans une application. Aucun compte de domaine ne peut se connecter aux autres bases.

À réaliser dans la PR de chaque propriétaire :

1. Choisir et versionner les migrations de son domaine, sans modifier celles d'un autre service.
2. Ajouter le driver PostgreSQL via le BOM Boot ; créer un profil PostgreSQL explicite et garder
   le profil de test H2. Ne pas appliquer la configuration globale `create-drop` à PostgreSQL.
3. Créer le schéma par migrations, puis utiliser Hibernate `validate`. Injecter seulement le secret
   du domaine, sans publier les mots de passe ni transmettre les secrets administrateur/autres domaines.
4. Préserver IDs, contraintes et données existantes ; documenter les sauvegardes et la transition H2
   si elle concerne de vraies données. Ne pas copier automatiquement des données de démonstration.
5. Tester création depuis une base neuve, redémarrage, migrations successives, doublons, transactions
   concurrentes, fuseaux horaires, verrouillage et absence de perte de données.
6. Pour Booking, vérifier particulièrement concurrence de capacité, annulation, prix et références
   Customer/Event. Pour Event, vérifier statuts, dates, prix et capacité. Ces tests restent leur responsabilité.
7. Examiner le schéma technique `ticketflow_infra_probe` séparément ; il ne contient pas de métier.

Arij coordonne l'infrastructure et migrera Customer séparément, notamment ses IDs Long, l'unicité
de `(iss, sub)`, les profils historiques et la suppression/archivage avec références Booking.
Ticket, Payment et Notification recevront leurs propres bases/comptes lorsque leur besoin sera défini.
Cette étape ne commence aucun de ces services ni Kafka.

Commandes et précautions : [guide PostgreSQL](ARIJ_POSTGRESQL_INFRA.md).
