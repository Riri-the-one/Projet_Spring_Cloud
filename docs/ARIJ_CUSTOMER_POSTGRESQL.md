# Customer Service : PostgreSQL persistant

Customer conserve son ID métier `Long`, ses champs de profil et son association
Keycloak `(identityIssuer, identitySubject)`. Les contrôles JWT et de propriété
restent ceux de la phase 2A. Event et Booking ne sont pas migrés et restent sur H2.
Le serveur PostgreSQL 18.7 existant, la base `ticketflow_customer`, le compte
`ticketflow_customer` et son secret local sont réutilisés.

## Deux modes explicites

| Mode | Démarrage | Schéma |
| --- | --- | --- |
| Démonstration H2 | Compose de base, sans overlay Customer | éphémère, Hibernate `create-drop`, Flyway désactivé |
| Customer PostgreSQL | overlays PostgreSQL + Customer PostgreSQL | persistant, Flyway puis Hibernate `validate` |

Il n'y a aucune copie automatique H2 vers PostgreSQL. Revenir au mode H2 conserve le
volume PostgreSQL mais remplace le conteneur Customer : les données H2 précédentes
étaient éphémères. Ne pas utiliser ce changement de mode comme procédure de sauvegarde.

## Migrations versionnées

Le starter officiel `spring-boot-starter-flyway`, le module
`flyway-database-postgresql` et le driver PostgreSQL utilisent les versions du BOM
Spring Boot 4.0.8 : Flyway 11.14.1 et driver 42.7.13. Voir la
[documentation Spring Boot](https://docs.spring.io/spring-boot/how-to/data-initialization.html).
Les migrations sont exclusivement sous
`services/customer-service/src/main/resources/db/migration/customer` :

- `V1__create_customer_profiles.sql` : ID bigint identity, noms, e-mail unique,
  téléphone nullable et date de création avec fuseau horaire.
- `V2__associate_keycloak_identity.sql` : issuer de 512 caractères et subject de
  255 caractères, unicité du couple et contrainte interdisant une liaison partielle
  ou vide. Les profils historiques peuvent garder les deux valeurs nulles.

Flyway mémorise versions et checksums dans `public.flyway_schema_history`.
Un redémarrage valide l'historique sans rejouer les migrations appliquées.
Ne jamais modifier une migration déjà appliquée ; ajouter une nouvelle version.
`baseline-on-migrate=false`, `clean-disabled=true`, aucun `update` ou `create-drop`
sur PostgreSQL. La garde de démarrage vérifie les réglages avant Flyway/Hibernate.

## Précautions avant première activation

Pour une base neuve dédiée, les migrations s'appliquent au démarrage explicite du
profil PostgreSQL. Le schéma public local a été inspecté vide avant les essais.
Si une base contient déjà des tables sans historique Flyway, le démarrage est refusé.
Ne pas activer de baseline automatique pour contourner ce refus.

Procédure pour des données existantes : arrêter les écritures, sauvegarder avec
`pg_dump` dans un emplacement privé, tester la restauration sur une copie, comparer
colonnes/types/contraintes/séquences avec V1/V2, analyser les doublons et liaisons
partielles sans supprimer de lignes, puis préparer une migration d'adoption revue.
La décision de baseline éventuelle doit correspondre exactement au schéma inspecté
et être testée sur la copie avant toute opération réelle. Aucune telle adoption
ni migration des profils H2 n'est automatisée dans cette PR.

## Démarrer avec Docker / PowerShell

Depuis la racine, Docker Desktop Linux et JDK 21 :

```powershell
.\tools\Initialize-PostgresqlDev.ps1
$p = @('compose', '-f', 'infra/docker-compose.yml', '-f', 'infra/docker-compose.postgresql.yml', '-f', 'infra/docker-compose.customer-postgresql.yml', '--profile', 'postgresql')
docker @p config --quiet
docker @p up -d --no-deps --wait --wait-timeout 180 postgres
docker compose -f infra/docker-compose.yml build customer-service
# Démarrer les dépendances existantes si elles ne tournent pas déjà
docker @p up -d --wait --wait-timeout 300
docker @p ps

.\mvnw.cmd -B -ntp verify
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Test-CustomerPostgresql.ps1
node tools/Test-KeycloakIntegration.mjs --customer-postgresql
node tools/Test-KeycloakIntegration.mjs
```

`Initialize-PostgresqlDev.ps1` conserve les secrets existants. L'overlay Customer
monte **uniquement** `postgres_customer_password` comme
`/run/secrets/customer/spring.datasource.password`. Config Tree lit ce fichier ; le
mot de passe n'apparaît ni dans le YAML, ni dans les commandes, ni dans les logs.
Les fichiers `.local/` restent ignorés par Git et exclus du contexte Docker.
Le port PostgreSQL Windows est `127.0.0.1:5433` par défaut ; le réseau Docker utilise
`postgres:5432`. Aucun second serveur n'est créé. Le compte Customer ne peut pas se
connecter aux bases Event et Booking.

## Config Server et priorité

Le fichier commun native et `customer-service.yml` fournissent les valeurs H2 de
démonstration. `customer-service-postgresql.yml` sert les valeurs du profil explicite.
L'overlay fixe URL, compte, driver et DDL via variables d'environnement, prioritaires
sur les documents de configuration. Il importe Config Server puis Config Tree pour
le seul mot de passe Customer. Le profil local contient les emplacements Flyway.
La garde refuse une URL H2, un compte administrateur, un DDL destructif, Flyway
désactivé, un baseline automatique, clean activé ou un secret absent.

En mode Config Server **Git**, publier aussi le document de profil dans le dépôt de
configuration externe sous la responsabilité de son propriétaire. Les variables
critiques Docker et la garde empêchent un retour silencieux vers H2 ; une mauvaise
configuration doit faire échouer le démarrage. Ne pas exposer `/actuator/env` pour
diagnostiquer les secrets.

## Depuis IntelliJ / Windows

Arrêter le conteneur Customer pour libérer 8081, en conservant PostgreSQL, Config
Server, Eureka et Keycloak. Dans un terminal PowerShell privé :

```powershell
$env:SPRING_PROFILES_ACTIVE = 'postgresql'
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5433/ticketflow_customer'
$env:SPRING_DATASOURCE_USERNAME = 'ticketflow_customer'
$env:SPRING_DATASOURCE_DRIVER_CLASS_NAME = 'org.postgresql.Driver'
$env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'validate'
$env:SPRING_FLYWAY_ENABLED = 'true'
$env:SPRING_DATASOURCE_PASSWORD = [IO.File]::ReadAllText((Resolve-Path 'infra/postgresql/.local/customer.password')).Trim()
.\mvnw.cmd -B -ntp -pl services/customer-service spring-boot:run
# Après arrêt : effacer le secret de l'environnement de ce terminal
Remove-Item Env:SPRING_DATASOURCE_PASSWORD
```

Pour IntelliJ, utiliser ces paramètres dans une configuration locale non partagée ;
ne pas enregistrer le secret dans `.idea`, un fichier `.run.xml` ou une capture.
Les valeurs issuer public et JWKS restent celles documentées pour Keycloak.

## Vérifications et démonstration

`Test-CustomerPostgresql.ps1` active le profil Maven `postgresql-it` et utilise le
fichier secret Customer sans en afficher la valeur. Les tests HTTP JWT signés et
d'intégrité de la phase 2A sont rejoués sur le vrai PostgreSQL : 401, 403, 404, 409,
propriété, contraintes e-mail/identité, créations concurrentes et rollbacks.
Des tests supplémentaires vérifient V1 vers V2 sur une ligne historique, les
redémarrages de Flyway et le refus d'un schéma non vide sans historique.
Les fixtures de ces essais restent dans des schémas `customer_probe_*` et des profils
fictifs ; aucune suppression automatique n'est effectuée. Ne pas lancer cette suite
sur une base de production ou une base contenant des données personnelles.

`--customer-postgresql` utilise de vrais JWT obtenus par Code + PKCE. Il crée ou
retrouve le profil fictif, le modifie, teste les refus et les conflits, puis redémarre
Customer et PostgreSQL séparément. Le même profil complet doit être retrouvé après
chaque redémarrage ; les tokens et snapshots restent uniquement en mémoire.
Ce scénario doit être lancé avec l'overlay Customer PostgreSQL déjà actif.
Le parcours complet sans cet argument continue de vérifier Gateway, Event et Booking.

Pour tester à nouveau la démonstration H2 sans détruire PostgreSQL :

```powershell
docker compose -f infra/docker-compose.yml up -d --no-deps --wait --wait-timeout 180 customer-service
node tools/Test-KeycloakIntegration.mjs
# Rétablir ensuite le mode PostgreSQL explicite
docker @p up -d --no-deps --wait --wait-timeout 180 customer-service
```

Ne pas employer `down -v`, `volume rm` ou `--remove-orphans`. Les avertissements
d'orphelins signalent simplement les overlays optionnels absents de la commande.

## Limites et coordination

Résultats locaux du 15 novembre 2026 : Maven `verify` vert avec **156 tests rapides**
(146 préservés et 10 contrôles de configuration), plus **42 tests PostgreSQL** verts.
Le parcours Docker H2, le parcours complet avec Customer PostgreSQL et le scénario
JWT/persistance ont été exécutés. Le même profil est retrouvé après redémarrage de
Customer puis de PostgreSQL. Les migrations V1/V2 sont enregistrées avec succès ;
les redémarrages indiquent « schema up to date ». Les neuf conteneurs sont sains.
La PR Kafka #6 corrigée est fusionnée à `bea7eff` ; ses trois jobs CI main sont verts
([exécution 43772405646](https://github.com/Riri-the-one/Projet_Spring_Cloud/actions/runs/43772405646)).

Seul Customer est migré. Les migrations Event appartiennent à Manar ; celles de
Booking à Salma. Leur comportement métier n'est pas modifié. Le contrat interne
`GET /internal/customers/me` retourne toujours uniquement `{ "customerId": 123 }`
à partir du JWT validé ; Salma doit utiliser cet ID au lieu d'un ID fourni par le client.
Les IDs des profils H2 ne sont pas transférés : un futur import devra préserver et
réconcilier les références avec Booking avant toute utilisation de données durables.
La suppression administrative Customer reste à coordonner avec ces références.
Ticket Service et Notification Service ne sont pas commencés.
