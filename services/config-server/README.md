# Config Server — TicketFlow

Le mode par défaut conserve le backend **Git du TP3**. Il nécessite la variable `CONFIG_GIT_URI`
(pointant vers le vrai dépôt de configuration), sa branche `main` et les configurations des services.
`clone-on-start: true`, `try-master-branch: false` et `refresh-rate: 0` restent inchangés.
L'URL réelle du dépôt TP3 reste à fournir ; aucune URL fictive ni aucun secret n'est versionné.

## Démonstration locale native

Depuis la racine du dépôt :

```powershell
$env:CONFIG_REPO_LOCATION = ([uri]((Resolve-Path infra/config-demo).Path + '\')).AbsoluteUri
.\mvnw.cmd -pl services/config-server spring-boot:run '-Dspring-boot.run.profiles=native'
```

Le profil lit `infra/config-demo/` par défaut depuis la racine ; la commande ci-dessus fixe un chemin absolu
car Maven lance la JVM dans le répertoire du module. Pour un autre répertoire, utiliser
`CONFIG_REPO_LOCATION` avec une URI `file:///C:/.../`. Dans Compose, ce répertoire est monté en lecture seule à `/config-repo/`.
Ce mode est réservé au développement : H2 en mémoire, données perdues au redémarrage des services.

```powershell
curl.exe http://localhost:8888/customer-service/default/main
curl.exe http://localhost:8888/event-service/default/main
curl.exe http://localhost:8888/booking-service/default/main
```

Les fichiers communs sont dans `application.yml`, les valeurs spécifiques dans les fichiers nommés comme les services.
Le routage Gateway reste dans le module Gateway, pas dans ces fichiers de démonstration.

## TP3 / Git

```powershell
# Définir CONFIG_GIT_URI avec l'URL réelle fournie par l'équipe, puis :
.\mvnw.cmd -pl services/config-server spring-boot:run
```

Ne pas activer le profil native pour ce mode. Avec Docker, utiliser l'overlay
`infra/docker-compose.git.yml` ; Compose refuse une variable absente ou vide avant démarrage.
Le serveur charge Git, mais les clients ne se rafraîchissent pas spontanément :
le TP utilise `POST /actuator/refresh` sur Customer et son contrôleur `@RefreshScope`.
L'exposition `health,info,refresh` doit être présente dans sa configuration distante.
Ce endpoint n'est pas routé par la Gateway.

## Tests

```powershell
.\mvnw.cmd -B -ntp -pl services/config-server test
```

La configuration de test active native sur le classpath, sans Git ni variable externe.
Le test existant valide le chargement du contexte ; le script de démonstration vérifie les propriétés réellement distribuées.
Voir le [guide Arij](../../docs/ARIJ_GATEWAY_DOCKER.md) pour la procédure complète.
