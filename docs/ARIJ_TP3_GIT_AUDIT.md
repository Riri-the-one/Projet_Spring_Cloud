# Audit TP3 Config Server Git — 16 novembre 2026

**Étape 03 validée dans le périmètre TP3 isolé : lecture du vrai dépôt Git et refresh dynamique v1 → v2 démontrés.** Le déploiement partagé reste configuré en native. Cette validation ne constitue pas une migration de la démonstration PostgreSQL/JWT vers les anciens fichiers TP3.

## Base et isolation

- Dépôt principal synchronisé par `git fetch origin` : `main` et `origin/main` identiques à `14896b1` (PR #13 fusionnée), arbre initial propre. Travail limité à cette documentation et aux outils TP3.
- Source officielle : https://github.com/Vi0ma/ticketflow-config.git, publique et lisible sans credentials. `git ls-remote` et clone réel réussis.
- Branche `main`, SHA observé : `8a191f33b30aad5ae7a885b994b4fff24fc9afd5`.
- Arbre : `application.yml`, `customer-service.yml`, `customer-service-dev.yml`, `api-gateway.yml`, `booking-service.yml`. Aucun fichier externe modifié ni commit envoyé sur GitHub dans ce dépôt.
- `services/config-server/src/main/resources/application.yml` définit le backend Git, main, sans fallback master, clone au démarrage et refresh-rate=0. Le profil native fournit son répertoire séparé. Compose partagé fixe `SPRING_PROFILES_ACTIVE=native` et monte `infra/config-demo` en lecture seule ; l'overlay Git remplacerait ce profil et ne doit pas être appliqué pour ce contrôle.
- Docker Desktop est arrêté lors de cette session (pipe du moteur absent). Le constat runtime native de l'audit précédent reste historique ; aucune nouvelle observation du serveur partagé en fonctionnement n'est revendiquée. Aucun démarrage Docker, changement de volume ou redémarrage métier.
- Validation par JVM Java 21 distinctes, liées uniquement à 127.0.0.1. Aucun fichier de configuration métier modifié, aucune base utilisée, aucune inscription Eureka.

## Lecture réelle du dépôt distant

Config Server isolé sur **18888**, profil git, URI HTTPS ci-dessus, label main, répertoire de clone `target/tp3-audit/server-remote`. Le script `tools/Test-Tp3ConfigGit.ps1` a réussi les trois contrôles suivants avec le même SHA distant/servi :

| Endpoint | Sources Git (ordre prioritaire) | Propriétés vérifiées |
| --- | --- | --- |
| `/customer-service/default/main` | customer-service.yml, application.yml | ticketflow.message = `Bienvenue dans TicketFlow - configuration v2` |
| `/api-gateway/default/main` | api-gateway.yml, application.yml | server.port = 8080 ; routes[0].id = customer-service |
| `/customer-service/dev/main` | customer-service-dev.yml, customer-service.yml, application.yml | ticketflow.message = `TicketFlow - profil dev` ; priorité du profil dev |

Les noms de sources sont préfixés par `https://github.com/Vi0ma/ticketflow-config.git/`. Le script contrôle chaque SHA, label, fichier attendu, propriété attendue et les clés sensibles servies sans afficher leurs valeurs. Ce contrôle ciblé n'est pas un audit exhaustif de secrets dans tout l'historique.

**Protection de la Gateway :** la version TP3 ne définit que Customer. Elle a uniquement été lue comme JSON ; aucune Gateway n'a été démarrée avec elle. Les routes Ticket/Notification et les paramètres PostgreSQL/JWT de la démonstration principale sont conservés.

## Refresh dynamique réellement exécuté

Historique existant, sans fabriquer de commit :

- v1 : `da70d329e25ae1816c324fdfe82c085937bbed8a`.
- v2 : `1e91958792b8941687a73bf00ada35f12aab951d`.

`tools/Test-Tp3Refresh.ps1` crée un dépôt bare local jetable, un clone de travail et un clone de lecture. Seule la branche locale `refresh-proof` est avancée par fast-forward puis poussée vers ce bare **local**. Config Server Git sur **18890** récupère ce changement ; rien n'est poussé dans le dépôt de Zainab.

Le client de test sur **18092** utilise le vrai Spring Cloud Config Client, Actuator et le contrôleur de production `ConfigDemoController` annoté `@RefreshScope`. Il ne charge ni services métier, ni base, ni sécurité HTTP/JWT ; son endpoint de test est limité au loopback. Il vérifie le mécanisme de refresh, pas l'autorisation ADMIN de la pile complète.

Preuve finale, client JVM **18960**, sans redémarrage entre les lectures :

1. Config Server sert le SHA v1 ; GET `/api/config/message` renvoie `Bienvenue dans TicketFlow - configuration v1`.
2. Avancement du Git local vers le SHA v2 ; Config Server sert effectivement ce SHA.
3. Le même client renvoie **encore v1 avant refresh**.
4. POST `/actuator/refresh`, avec Content-Type application/json et corps `{}`, renvoie les clés `config.client.version` et `ticketflow.message`.
5. GET sur le même contrôleur et la même JVM renvoie `Bienvenue dans TicketFlow - configuration v2`.

Cette séquence prouve un refresh dynamique ; une lecture initiale de v2 ou un simple HTTP 200 n'aurait pas suffi. L'exécution finale a réussi avec le mécanisme Git fetch depuis le bare local. Les JVM créées par le script ont été arrêtées dans `finally`.

## Reproduction et tests

Depuis la racine, Java/JDK 21 et jars Maven construits :

```powershell
.\mvnw.cmd -B -ntp -pl services/config-server,services/customer-service -am verify
# Lancer une JVM Config Server distincte (laisser le serveur partagé intact) :
java -jar services/config-server/target/config-server-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=18888 --spring.profiles.active=git --spring.cloud.config.server.git.uri=https://github.com/Vi0ma/ticketflow-config.git --spring.cloud.config.server.git.default-label=main --spring.cloud.config.server.git.basedir=target/tp3-audit/server-remote
# Dans un autre terminal :
powershell -NoProfile -File tools/Test-Tp3ConfigGit.ps1 -RepositoryUri https://github.com/Vi0ma/ticketflow-config.git -ConfigServer http://127.0.0.1:18888
# Clone de lecture de la source réelle, si absent :
git clone https://github.com/Vi0ma/ticketflow-config.git target/tp3-audit/external
powershell -NoProfile -File tools/Test-Tp3Refresh.ps1
```

Résultats : **58 tests Customer + 1 test Config Server**, zéro échec/erreur/skipped ; BUILD SUCCESS. Vérifications Git des trois endpoints : réussies. Refresh v1 → v2 : réussi avec assertions et même JVM.

Traces locales ignorées : `target/tp3-audit/maven.log`, `target/tp3-audit/remote.log` et `target/tp3-refresh-1596fdb2d605455ea58d42bc24ed1997/proof.json`, avec logs serveur/client associés.

Essais intermédiaires corrigés : le POST sans Content-Type JSON retournait 415 ; les deux scripts utilisent maintenant application/json. Un URI file pointant directement vers un dépôt bare échouait (`No .git`) ; la procédure finale utilise un clone de lecture connecté à ce bare. Le classpath du client conserve la dépendance crypto requise par Config Client. Aucun de ces échecs n'est compté comme réussite.

## Clôture et limites

Le blocage URL/droits est levé. Les trois lectures distantes et le refresh dynamique requis par cette vérification TP3 sont validés. Le périmètre autorisé permet explicitement l'usage d'un environnement isolé ; l'étape 03 est donc **entièrement validée dans ce périmètre**.

Le refresh ADMIN/JWT et l'état opérationnel de la pile partagée n'ont pas été rejoués ici, Docker étant arrêté. La preuve ne porte pas sur un changement en direct de main GitHub, mais sur ses deux commits historiques rejoués localement. Aucun déploiement Git sur la pile principale n'est nécessaire pour cette clôture et aucun blocage TP3 ne subsiste.
