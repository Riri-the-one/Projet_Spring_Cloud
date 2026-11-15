# Kafka : infrastructure locale TicketFlow

Cette étape fournit uniquement le broker et ses vérifications. Aucun producteur métier,
Notification Service ou Ticket Service n'est implémenté. Customer, Event et Booking
conservent leurs configurations H2 et fonctionnent sans Kafka.

## Version et architecture

Image officielle Apache **`apache/kafka:4.4.0`**, version fixée, vérifiée dans les
[versions supportées](https://kafka.apache.org/community/downloads/) et le
[guide officiel Docker](https://kafka.apache.org/quickstart/).
Un seul nœud combine broker et contrôleur **KRaft**, sans ZooKeeper.
Le script utilise `kafka-storage.sh format --standalone` uniquement sur un volume vide :
le wrapper Docker Apache utilise encore le formatage statique de compatibilité.
Notre configuration suit le [quorum dynamique KRaft](https://kafka.apache.org/44/operations/kraft/).
Un volume déjà initialisé est réutilisé ; une identité de cluster différente ou un
répertoire non vide non initialisé provoque un refus, jamais une remise à zéro.

| Usage | Adresse |
| --- | --- |
| Futurs services dans le réseau Compose | `kafka:9092` |
| IntelliJ / Windows | `127.0.0.1:29092` |
| Contrôleur KRaft interne | `kafka:9093`, non publié |

Seul le listener Windows est publié, sur **127.0.0.1**. Les métadonnées renvoient
l'adresse appropriée selon le listener utilisé. Le health check vérifie le quorum,
son leader et son identifiant de cluster. Le volume nommé `ticketflow-kafka-data`
contient journaux et métadonnées ; les clients de test éphémères utilisent des tmpfs.

## Commandes PowerShell

Depuis la racine, avec Docker Desktop en mode conteneurs Linux et JDK 21 :

```powershell
$k = @('compose', '-f', 'infra/docker-compose.yml', '-f', 'infra/docker-compose.kafka.yml', '--profile', 'kafka')
docker @k config --quiet
docker @k up -d --no-deps --wait --wait-timeout 240 kafka
docker @k exec -T kafka bash /opt/ticketflow-kafka/init-topics.sh
docker @k ps kafka

powershell -NoProfile -ExecutionPolicy Bypass -File tools/Test-KafkaInfra.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Test-KafkaInfra.ps1 -Recreate
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Test-KafkaHost.ps1 -Port 29092

# Arrêter uniquement Kafka, sans supprimer son volume
docker @k stop kafka
# Vérifier le parcours existant, les sept services devant déjà être démarrés
node tools/Test-KeycloakIntegration.mjs
.\mvnw.cmd -B -ntp verify
```

Sans overlay ni profil `kafka`, Compose conserve les sept services existants.
`kafka-client` est un outil transitoire appelé par les tests, pas un huitième service
applicatif. Un avertissement « orphan » concernant PostgreSQL peut apparaître si son
overlay n'est pas inclus : ne pas employer `--remove-orphans`.

Paramètres facultatifs : `$env:TICKETFLOW_KAFKA_PORT='29093'` change le port Windows
et son annonce ; utiliser le même port avec `Test-KafkaHost.ps1`.
`TICKETFLOW_KAFKA_CLUSTER_ID` est un identifiant public de 22 caractères base64url,
pas un secret. Sa valeur par défaut est reproductible. Ne pas le changer pour un
volume existant. Aucun mot de passe ni token n'est requis par ce broker de développement.
Le fichier `infra/kafka/server.properties` contient les paramètres du broker.

## Topics et tests

`init-topics.sh` crée uniquement les topics absents du manifeste `topics.tsv` et
vérifie les paramètres existants sans les modifier :

| Topic | Partitions | Réplication | Rétention |
| --- | --- | --- | --- |
| `ticketflow.event.events.v1` | 3 | 1 | 7 jours |
| `ticketflow.booking.events.v1` | 3 | 1 | 7 jours |
| `ticketflow.payment.events.v1` | 3 | 1 | 7 jours |
| `ticketflow.ticket.events.v1` | 3 | 1 | 7 jours |

La création automatique des topics est désactivée. Après le démarrage du broker,
exécuter explicitement `init-topics.sh` (commande ci-dessus) ; le script de test le
fait également et vérifie chacun des quatre topics du manifeste. Aucun message métier n'est envoyé.
Manar sera responsable des producteurs `EventPublished` et `EventClosed`, avec
l'identifiant métier de l'événement comme clé Kafka. L'UUID `eventId` de l'enveloppe
identifie le message, pas l'événement métier. Aucun producteur Event n'est implémenté ici.
Le topic technique `ticketflow.infra.probe.v1` reçoit uniquement des marqueurs de test.
Les tests contrôlent la création, les acquittements, la consommation exacte et ordonnée
de deux messages avec clés, puis leur présence après redémarrage et remplacement du
conteneur avec le même volume. Un client Docker distinct teste `kafka:9092` ; un outil
Java 21 sous `tools/kafka-probe` teste réellement les métadonnées, la production et la
consommation depuis Windows. Cet outil n'est ni un microservice ni un module du reactor.

Le job CI `kafka-infrastructure` exécute ces deux scénarios indépendamment des jobs
Maven et PostgreSQL. Les scripts échouent si une vérification échoue.

## Résultats observés le 15 novembre 2026

- PR PostgreSQL #5 fusionnée : `16d9dd1` ; CI main `43768840464` verte (deux jobs).
- Maven : BUILD SUCCESS, **146 tests**, aucun échec.
- Broker sain ; clients Docker et Windows : publication/consommation réussies.
- Redémarrage et remplacement du conteneur : marqueurs retrouvés, même volume.
- Kafka arrêté : démonstration OIDC Customer/Event/Booking réussie.
- Aucun volume existant supprimé ; aucune configuration métier migrée.

## Limites et suite

PLAINTEXT, sans SASL/TLS/ACL : réservé au poste local et au réseau Docker de confiance.
Les JWT Keycloak des API HTTP ne sécurisent pas Kafka. Un seul broker et une réplication
de 1 ne fournissent aucune haute disponibilité ; `acks=all` ne crée pas de redondance.
Pour la production : réseau privé, authentification et ACL par service, TLS, plusieurs
brokers/contrôleurs, réplication, supervision, sauvegardes et rétention dimensionnée.
Le runtime Java de l'image officielle est indépendant du JDK 21 des services.

Les contrats à valider avec Salma sont dans [ARIJ_KAFKA_CONTRACTS.md](ARIJ_KAFKA_CONTRACTS.md).
Les migrations PostgreSQL et les producteurs/consommateurs métier restent des étapes séparées.
