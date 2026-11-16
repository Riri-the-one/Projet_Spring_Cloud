# Bilan Arij et intégration — 16 novembre 2026

Ce bilan distingue le travail développé, les PR à valider et les dépendances métier.
Aucune PR n'a été fusionnée pendant cette mission. Main reste à **b0fc1f5**.

| Lot | État réel | Preuves | Revue / dépendance |
| --- | --- | --- | --- |
| Customer sécurisé et PostgreSQL | Fusionné et fonctionnel | JWT/propriété, Flyway, démonstration Docker et CI | PR #4/#7 fusionnées |
| PostgreSQL/Kafka communs | Fonctionnels | Comptes isolés, volumes, topics et tests réels | PR #5/#6 fusionnées |
| Ticket cœur métier | Fusionné, entrée métier non branchée | 27 tests initiaux, émission idempotente par place | PR #8 fusionnée ; contrat Salma manquant |
| Ticket persistant + Gateway | Développé, en attente de validation personnelle | 211 tests rapides, 30 PostgreSQL, cinq jobs verts | PR #9 conservée en Draft |
| Notification autonome | Développé, en revue ; intégration métier bloquée | 31 tests Notification, 19 PostgreSQL/Kafka, reprise et JWT réels | PR #10 Draft ; contrats Salma manquants |
| TP3 Git externe | Audit terminé, validation réelle bloquée | Mode native confirmé, URL absente, garde code 2 | PR #11 Draft ; URL et accès réels nécessaires |
| Compatibilité et qualité | Contrôles exécutés | 242 tests combinés, onze conteneurs, vingt refus DB | Cette PR indépendante |

## Combinaison de revue sans fusion

Les diffs exacts #9 à **7b056a0** et #10 à **422263b** ont été appliqués en trois voies
dans un worktree détaché de main, sous target/arij-validation, sans conflit, commit
ou changement de référence main. `mvnw verify` y réussit avec **242 tests** : Customer 58,
Gateway 68, Discovery 2, Config 1, Event 21, Booking 19, Ticket 42, Notification 31.
Ces chiffres ne sont pas ceux de main seul : main conserve 183 tests ; #9 ajoute 28
gardes/scénarios et #10 ajoute 31 tests indépendants.

Les suites PostgreSQL restent distinctes : 30 cas Ticket et 19 Notification/Kafka,
avec relecture persistante dans une nouvelle JVM après redémarrage PostgreSQL.
Les données techniques sont dans des schémas de test séparés. Aucun billet payé ou
notification métier n'a été créé artificiellement dans le runtime public.

La [CI Notification dédiée](https://github.com/Riri-the-one/Projet_Spring_Cloud/actions/runs/43789395285)
et sa [CI monorepo](https://github.com/Riri-the-one/Projet_Spring_Cloud/actions/runs/43789395213)
sont vertes. La [CI #9](https://github.com/Riri-the-one/Projet_Spring_Cloud/actions/runs/43785209744)
reste verte sur cinq jobs. La PR #9 n'a pas été marquée prête ni fusionnée.

## Contrôle d'exploitation reproductible

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Test-ArijQuality.ps1
```

Prérequis : les onze conteneurs locaux sont déjà déployés depuis leurs branches de
revue, avec les secrets de leurs domaines montés dans PostgreSQL pour l'audit. L'outil
ne déploie ni ne provisionne, n'écrit aucune table et ne publie aucun message Kafka.
Il vérifie santé, interfaces publiées, noms de volumes, endpoints HTTP, cinq comptes
PostgreSQL et vingt refus CONNECT, quatre topics métier et OIDC réel Code + PKCE.
Il n'imprime ni token, ni mot de passe, ni corps de billet/notification.

Résultat réel : onze conteneurs sains ; publications sur 127.0.0.1 uniquement. Volumes
PostgreSQL, Kafka et Keycloak conservés. Les bases Customer/Event/Booking/Ticket/Notification
sont isolées. Event et Booking restent en H2 côté application jusqu'aux migrations de
leurs propriétaires ; leur base provisionnée n'est pas présentée comme une migration.

## Démonstration devant le professeur

Après checkout/déploiement des PR correspondantes (sans fusion automatique) :

1. Exécuter le contrôle en lecture seule ci-dessus : infrastructure et OIDC.
2. Sur les sources contenant #9 : `node tools/Test-KeycloakIntegration.mjs --ticket-gateway`.
   Montrer les lectures vides autorisées, les refus 401/403, 404 et l'absence de création
   arbitraire. Les billets réels attendent toujours une confirmation fiable.
3. Sur la branche #10 : `node tools/Test-NotificationHttp.mjs` pour les droits de lecture
   et d'administration, sans notification fictive en runtime.
4. `node tools/Test-KeycloakIntegration.mjs` montre le parcours existant catalogue,
   réservation PENDING puis annulation ; ce parcours a aussi réussi avec Notification arrêté.
5. `tools/Test-TicketPostgresql.ps1` (#9) et `tools/Test-NotificationInfra.ps1` (#10)
   démontrent les replays, la concurrence, Kafka technique, les pannes simulées et
   la reprise durable dans des schémas isolés. Annoncer explicitement qu'il s'agit de tests.

Ce n'est **pas** un achat payé de bout en bout. Ne pas affirmer qu'un BookingConfirmed
réel a circulé, qu'un paiement réel/simulé du service Payment a été validé ou qu'un
e-mail a été envoyé. Le canal Notification courant produit un reçu simulé transactionnel.
Les scripts de test PostgreSQL peuvent redémarrer le serveur partagé : ne pas les
exécuter pendant des écritures métier et conserver tous les volumes.

## Contrats et tâches du groupe

| Responsable | Information ou livraison encore requise |
| --- | --- |
| Salma / Booking | Identité Customer résolue depuis le JWT, règles de propriété, confirmation fiable, lignes/quantités, versions, annulation et publication outbox |
| Salma / Payment | Paiement admissible, types succès/échec, référence stable, remboursement et événement faisant autorité pour éviter deux notifications d'échec |
| Manar / Event | Propriété organisateur, visibilité des brouillons, migrations propres Event, événements EventPublished/EventClosed et données de validité utiles aux billets |
| Manar/Salma / Angular | Parcours authentifié catalogue/client/admin et affichage des API, sans IDs clients tenus pour preuves d'identité |
| Propriétaire TP3 | URL réelle, accès et label du dépôt Config ; audit de secrets de tout le dépôt/historique et refresh après changement non sensible |
| Arij après contrats acceptés | Adaptateurs Kafka métier Ticket/Notification, invalidation après annulation/remboursement, stratégie de messages en désordre, éventuelle exposition Gateway Notification |
| Groupe / production | TLS/ACL Kafka, secrets gérés, sauvegarde/restauration, chiffrement/protection des codes, rétention, supervision et contrôle d'entrée atomique des billets |

Les propositions détaillées sont dans les PR : [Ticket](https://github.com/Riri-the-one/Projet_Spring_Cloud/pull/9),
[Notification et contrats Booking/Payment](https://github.com/Riri-the-one/Projet_Spring_Cloud/pull/10),
[audit TP3](https://github.com/Riri-the-one/Projet_Spring_Cloud/pull/11).
Une nouvelle branche distante feature/salma-booking a été détectée et respectée ; au
SHA 4c08ab8 elle ne contient pas de nouveau producteur/contrat définitif. Aucun travail
de Manar, Salma ou Zainab n'a été écrasé ou développé à leur place.

## Sécurité restante

La fondation JWT ne rend pas Event/Booking entièrement sécurisés : leurs contrôles
métier restent à leurs propriétaires. Le transport Kafka local est PLAINTEXT sans ACL.
Les codes Ticket sont sensibles et stockés en clair dans la base locale. Les secrets
locaux restent des fichiers de développement, pas un gestionnaire de secrets de production.
Le reçu simulé garantit l'atomicité locale, pas un exactly-once global avec un fournisseur.
Le vrai TP3 Git et les contrats métier restent bloqués ; aucun de ces points n'est
marqué terminé par les tests techniques.
