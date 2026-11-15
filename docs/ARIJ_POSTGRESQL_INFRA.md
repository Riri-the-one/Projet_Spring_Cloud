# TicketFlow — infrastructure PostgreSQL locale

Cette étape ajoute PostgreSQL à côté de TicketFlow. **Elle ne migre aucun service.** Customer,
Event et Booking continuent d'utiliser H2 ; leurs entités, règles métier et migrations restent inchangées.
Gateway, Keycloak, Config Server et Eureka conservent leur configuration actuelle.

## Version et stockage

L'image officielle est fixée à **`postgres:18.7-bookworm`**, vérifiée le 15 novembre 2026 dans le
[catalogue officiel Docker](https://hub.docker.com/_/postgres) et la
[politique de versions PostgreSQL](https://www.postgresql.org/support/versioning/).
La version stable 18.7 est utilisée, sans tag flottant `latest` et sans version de prépublication.

Le volume dédié `ticketflow-arij_ticketflow-postgresql-data` est monté sur `/var/lib/postgresql`.
C'est le point de montage de l'image PostgreSQL 18, dont le PGDATA est situé sous `18/docker`.
Les volumes Keycloak et les autres projets ne sont pas modifiés. Une montée de version majeure
nécessitera une procédure de migration explicite, jamais le simple remplacement du tag sur ce volume.

## Activer PostgreSQL explicitement

Prérequis : Docker Desktop en conteneurs Linux et PowerShell. Depuis la racine du dépôt :

```powershell
powershell -NoProfile -File tools/Initialize-PostgresqlDev.ps1

$pg = @('compose', '--env-file', 'infra/postgresql/.env.local',
    '-f', 'infra/docker-compose.yml', '-f', 'infra/docker-compose.postgresql.yml',
    '--profile', 'postgresql')

docker @pg config --quiet
docker @pg up -d --no-deps --wait --wait-timeout 180 postgres
docker @pg ps
```

L'overlay ajoute seulement le service `postgres`, ses secrets et son volume. Le profil `postgresql`
doit être activé explicitement. Il n'ajoute aucune variable de datasource aux applications existantes.
Le fichier Compose H2 habituel fonctionne seul, sans les secrets PostgreSQL :

```powershell
docker compose -f infra/docker-compose.yml config --quiet
docker compose -f infra/docker-compose.yml up -d --wait --wait-timeout 300
```

Ne pas utiliser de commande supprimant les volumes ou les conteneurs d'autres projets pour résoudre
un conflit de port. Changer le port PostgreSQL local dans `.env.local` si nécessaire.

## Paramètres et secrets

| Paramètre | Valeur de développement |
|---|---|
| Service Docker / port interne | `postgres:5432` |
| Adresse Windows / IntelliJ | `127.0.0.1:5433` |
| `TICKETFLOW_POSTGRES_PORT` | `5433`, configurable dans `infra/postgresql/.env.local` |
| `TICKETFLOW_POSTGRES_SECRETS_DIR` | `./postgresql/.local` par défaut, relatif au premier fichier Compose |
| Compte opérateur | `ticketflow_admin`, aucun service ne reçoit ce compte |
| Mot de passe administrateur | Variable `POSTGRES_PASSWORD_FILE` vers un secret monté |

Le script de préparation crée quatre mots de passe aléatoires distincts, issus de 32 octets
cryptographiques, dans `infra/postgresql/.local/{admin,customer,event,booking}.password`.
Il ne les affiche pas et ne remplace jamais des secrets existants. Un jeu partiel provoque un arrêt
explicite : il faut restaurer les fichiers manquants, sans rotation silencieuse.
Les fichiers générés sont en UTF-8 sans BOM ni retour à la ligne ; conserver ce format si les secrets
sont déplacés vers un répertoire personnalisé.

Le fichier `.env.local` est créé depuis `.env.example` et ne contient que des paramètres non secrets.
Les mots de passe sont montés comme secrets Compose dans PostgreSQL ; ils ne figurent pas dans les
valeurs d'environnement des sept services. `.local/` est exclu de Git **et** des contextes de build Docker.
Les secrets Compose locaux restent des fichiers lisibles par l'opérateur Docker : ce n'est pas un
gestionnaire de secrets de production. Ne pas transmettre le compte administrateur aux applications.

Changer un fichier de mot de passe après initialisation ne change pas le mot de passe SQL enregistré.
Toute rotation future exige une procédure coordonnée de changement des rôles et des clients, avec
sauvegarde ; ni le démarrage ni les tests ne font cette rotation automatiquement.

## Bases et isolation

| Domaine | Base | Compte de domaine | Propriétaire des futures migrations |
|---|---|---|---|
| Customer | `ticketflow_customer` | `ticketflow_customer` | Arij, migration séparée |
| Event | `ticketflow_event` | `ticketflow_event` | Manar |
| Booking | `ticketflow_booking` | `ticketflow_booking` | Salma |

Chaque compte peut se connecter, créer des schémas/tables et utiliser des tables temporaires dans
sa propre base de développement. Il n'est ni superutilisateur, ni créateur de bases/rôles, ni membre
d'un autre rôle ; il ne peut pas contourner les politiques de lignes. Les bases restent propriété
de l'administrateur, afin que les comptes de domaine ne contrôlent pas leurs droits de connexion.

Les droits `PUBLIC` de connexion sont retirés des trois bases et des bases de maintenance `postgres`
et `template1`. Chaque compte reçoit CONNECT uniquement sur sa base. Les droits publics du schéma
`public` sont également retirés, puis USAGE/CREATE accordés au seul compte de domaine concerné.
Ainsi, connaître le mot de passe Customer ne permet pas de se connecter à la base Event, ni
d'utiliser le compte Event. SCRAM-SHA-256 est imposé pour les connexions TCP.

L'administrateur de cluster conserve volontairement l'accès à toutes les bases. Cette isolation
est une séparation de droits SQL dans un cluster de développement commun, pas une séparation
physique de serveurs. Les sauvegardes, ressources et disponibilités restent partagées.
Une séparation plus fine entre comptes de migration et comptes d'exécution pourra être ajoutée
lorsque chaque service disposera de ses migrations et besoins définitifs.

Ticket, Payment et Notification ne sont pas créés à ce stade. Leurs futures bases/comptes devront
suivre le même modèle, par une évolution explicite ; aucun compte commun n'est prévu.

## Initialisation et health check

`infra/postgresql/init/10-domains.sql` est exécuté par l'image officielle uniquement sur un répertoire
de données neuf. Il crée les rôles/bases, les droits et un marqueur final d'initialisation.
Il ne contient aucune suppression, ne copie aucune donnée H2 et ne crée aucune table métier.
S'il est lancé manuellement sur des objets de domaine existants, il refuse de continuer plutôt
que de remplacer les comptes ou les données. Ce bootstrap n'est pas un outil de migration.

Le health check vérifie une connexion TCP authentifiée et le marqueur final. Une initialisation
partielle ne sera donc pas déclarée saine au démarrage suivant. En cas d'échec, conserver le volume,
consulter les journaux, puis préparer une réparation contrôlée ou un autre volume de test isolé.
Ne pas effacer le volume pour masquer une erreur d'initialisation.

## Vérifications reproductibles

```powershell
# Connexions réelles, droits, mots de passe et données après redémarrage de PostgreSQL seulement.
powershell -NoProfile -File tools/Test-PostgresqlInfra.ps1

# Vérification renforcée : nouveau conteneur PostgreSQL, même volume et mêmes données.
powershell -NoProfile -File tools/Test-PostgresqlInfra.ps1 -Recreate

# Les sept services existants et le parcours authentifié restent en H2.
node tools/Test-KeycloakIntegration.mjs
.\mvnw.cmd -B -ntp verify
```

Le test PostgreSQL vérifie trois connexions autorisées, douze refus CONNECT entre domaines et vers
les bases de maintenance, les attributs et appartenances des comptes, les mauvais mots de passe
et les six tentatives d'utiliser le mot de passe d'un domaine avec le compte d'un autre.
Il exige les erreurs PostgreSQL attendues : un timeout ou un serveur inaccessible ne vaut pas
preuve d'isolation.

Des témoins sont créés dans `ticketflow_infra_probe.persistence`, dans un schéma de test séparé
de `public`. Une ligne par base est insérée uniquement si elle n'existe pas. Leurs marqueurs sont
comparés avant/après redémarrage ou remplacement du conteneur ; aucun témoin n'est réinitialisé.
Ces données techniques de développement ne sont pas des données métier à migrer.

Le job CI Maven existant est conservé. Un job distinct `postgresql-infrastructure` exécute les
scripts PowerShell sur Ubuntu, avec une base neuve et des secrets éphémères non publiés. Il teste
le redémarrage et le remplacement du conteneur, sans construire ni lancer les services Java.

Pour consulter les journaux ou arrêter PostgreSQL sans supprimer ses données :

```powershell
docker @pg logs --tail 60 postgres
docker @pg stop postgres
# Reprendre ensuite avec le même volume :
docker @pg up -d --no-deps --wait postgres
```

## Connexion des services : prochaine étape uniquement

Les applications ne possèdent pas encore leur migration PostgreSQL validée. Ne pas activer les
exemples suivants contre des données réelles ou avec `ddl-auto=create-drop`.

| Domaine | URL JDBC depuis Docker | URL JDBC depuis IntelliJ |
|---|---|---|
| Customer | `jdbc:postgresql://postgres:5432/ticketflow_customer` | `jdbc:postgresql://127.0.0.1:5433/ticketflow_customer` |
| Event | `jdbc:postgresql://postgres:5432/ticketflow_event` | `jdbc:postgresql://127.0.0.1:5433/ticketflow_event` |
| Booking | `jdbc:postgresql://postgres:5432/ticketflow_booking` | `jdbc:postgresql://127.0.0.1:5433/ticketflow_booking` |

Utiliser le compte correspondant à la base et seulement son mot de passe. Ne pas laisser la base
par défaut `postgres` dans IntelliJ : elle est interdite aux comptes de domaine.

Après ajout du driver `org.postgresql:postgresql` avec version gérée par Boot, migrations versionnées
et validation par le propriétaire du service, les variables de datasource pourront être injectées
dans un lancement IntelliJ dédié. Exemple **futur Event**, à ne pas appliquer maintenant :

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5433/ticketflow_event'
$env:SPRING_DATASOURCE_USERNAME = 'ticketflow_event'
$env:SPRING_DATASOURCE_PASSWORD = (Get-Content -Raw infra/postgresql/.local/event.password).Trim()
$env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'validate'
# Lancer Event seulement après préparation de ses migrations ; ne pas afficher le mot de passe.
```

Dans Docker, le futur overlay du service devra injecter son propre secret et l'URL interne ci-dessus,
sans lui donner les trois autres secrets. Vérifier la priorité effective face au Config Server
native/Git. La configuration de développement actuelle publie `create-drop` : elle doit être
remplacée explicitement lors de la bascule, sans profil de repli silencieux vers H2.

Les [instructions pour Manar et Salma](ARIJ_POSTGRESQL_COORDINATION.md) précisent les responsabilités.
Arij migrera Customer séparément, en préservant les IDs et la liaison `(iss, sub)`.

## Validation et limites

Validation locale du 15 novembre 2026 : PostgreSQL 18.7 démarré ; trois bases/comptes créés ; isolation
SQL vérifiée ; marqueurs inchangés après redémarrage puis remplacement du conteneur ; volume et port
loopback vérifiés. Les sept définitions Compose existantes ont été comparées avant/après ajout de
l'overlay et sont identiques. Les huit conteneurs sont sains, et la démonstration OIDC/Customer/Event/Booking passe.
`mvnw verify` est vert avec **146 tests**, sans échec, erreur ou test ignoré.

Aucune migration de service, aucun test de migrations PostgreSQL métier, aucune conversion de
données H2 ni configuration TLS/haute disponibilité de production n'est réalisé ici. Le port est
publié uniquement sur `127.0.0.1`. Kafka, Ticket et Notification ne sont pas commencés.
Le statut des deux jobs GitHub Actions sera relevé sur le commit final de la Draft PR.
