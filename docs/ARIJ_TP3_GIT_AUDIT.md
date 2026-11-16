# Audit TP3 Config Server Git — 16 novembre 2026

**Statut : BLOQUÉ pour le dépôt externe réel.** Aucun dépôt n'a été inventé ou créé.
Le TP3 de Zainab n'est ni repris ni réimplémenté. La configuration native existante
reste opérationnelle ; elle n'est pas présentée comme une validation du mode Git.

## Constats réels

- Audit initial issu de main b0fc1f5 ; PR #8 déjà fusionnée.
- Les PR #9 Ticket PostgreSQL/Gateway et #10 Notification sont désormais fusionnées.
- CONFIG_GIT_URI absent de l'environnement de travail et du conteneur Config Server.
- Aucun .env ou .env.local racine contenant une URL réelle ; configuration IntelliJ
  locale inspectée pour cette seule clé, aucune mention trouvée.
- Les dépôts GitHub accessibles ont été listés : aucun dépôt de configuration TP3
  identifiable. Cela ne prouve pas qu'un dépôt privé d'une coéquipière n'existe pas ;
  son URL et l'accès restent à fournir par son propriétaire.
- Config Server tourne réellement en profil **native**. GET localhost:8888/customer-service/default/main
  retourne deux sources, label main et version null, pas un SHA Git.
- Les fichiers Config Server et l'overlay Git existants sont conservés. L'overlay
  exige déjà CONFIG_GIT_URI et échoue si l'URL manque ; aucun fallback Git fictif.
- Les configurations versionnées utilisent des secrets locaux/Config Tree pour les
  bases. Aucun mot de passe réel n'a été trouvé dans les modifications auditées.

## Outil de vérification prêt, sans modification du dépôt externe

```powershell
# Sans URL : résultat BLOCKED, code de sortie 2 attendu
powershell -NoProfile -File tools/Test-Tp3ConfigGit.ps1
# Lorsque le propriétaire fournit la vraie URL et le droit de lecture :
powershell -NoProfile -File tools/Test-Tp3ConfigGit.ps1 -RepositoryUri $env:CONFIG_GIT_URI -Label main
```

L'outil vérifie l'existence de la branche distante et compare son SHA à celui servi
par Config Server. Il refuse une URL avec credentials incorporés et ne publie aucune
valeur sensible. Le contrôle des propriétés servies ne remplace pas un audit de tout
le dépôt et de son historique : cet audit exhaustif reste bloqué tant que l'URL manque.

Pour tester refresh, utiliser ensuite -RefreshCustomer avec un vrai JWT ADMIN dans
TICKETFLOW_DEMO_ACCESS_TOKEN, sur les seuls endpoints loopback. L'outil ne change aucun
fichier externe et ne simule pas un changement de configuration. Pour prouver une
évolution de valeur, le propriétaire du dépôt devra proposer un changement non sensible,
attendre le SHA, recharger puis vérifier l'application de la valeur avec ce JWT.

## Informations nécessaires au groupe

Fournir l'URL réelle, le propriétaire, la branche/label et un accès de lecture sans
secret versionné. Vérifier customer-service.yml et les profils PostgreSQL, puis le
chargement par Config Server et le refresh protégé. Si le dépôt existe chez Zainab,
utiliser son travail et coordonner uniquement la validation d'Arij.

Ne pas activer le profil Git sur le serveur partagé avec une URL hypothétique. Toute
validation ultérieure devra conserver les paramètres de sécurité PostgreSQL/JWT et
ne pas redémarrer les services métier des autres membres sans nécessité.
