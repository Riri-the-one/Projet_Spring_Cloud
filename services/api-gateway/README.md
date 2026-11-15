# API Gateway — TicketFlow

Gateway WebFlux/Netty, Java 21, Spring Cloud 2025.1. Les contrôleurs métier ne sont pas modifiés.

## Routes principales (TP1 / TP2)

| Chemin public | Destination Eureka / LoadBalancer |
|---|---|
| `/api/customers/**` | `lb://customer-service` |
| `/api/events/**` | `lb://event-service` |
| `/api/bookings/**` | `lb://booking-service` |

Les chemins restent identiques côté service : aucun `StripPrefix` ou `RewritePath` sur ces routes.
`gateway-routes.yml` contient la configuration commune réellement utilisée par l'application et ses tests.
`PreserveHostHeader` préserve l'origine HTTP pour les réponses `Location` des contrôleurs ;
`AddResponseHeader` ajoute `X-Served-By: api-gateway` aux réponses des routes.
La Gateway consulte le registre Eureka pour choisir une instance ; les requêtes métier vont ensuite directement à cette instance.

Le Discovery Locator est désactivé par défaut. Config Server, Eureka, `/api/config/message`, les consoles H2
et les endpoints Actuator des services ne disposent pas de route publique. Seule la santé propre à la Gateway est exposée.
La [phase 1 Keycloak/JWT](../../docs/ARIJ_SECURITY_KEYCLOAK.md) protège ces routes par JWT et rôles.
La [phase 2A Customer](../../docs/ARIJ_CUSTOMER_IDENTITY.md) contrôle désormais les JWT et la propriété
dans Customer. Ces contrôles restent à réaliser dans Event et Booking. Compose est limité à l'interface locale.

## Exécution

Suivre le [guide français](../../docs/ARIJ_GATEWAY_DOCKER.md) pour les modes IntelliJ et Docker.
Depuis la racine, après Config Server et Eureka :

```powershell
.\mvnw.cmd -pl services/api-gateway spring-boot:run
curl.exe -i http://localhost:8080/api/customers
```

`CONFIG_SERVER_URL` vaut `http://localhost:8888` par défaut, `EUREKA_URL` vaut
`http://localhost:8761/eureka/`. L'import Config Server est obligatoire.
Un `503` peut être normal pendant la convergence du registre (environ 30 à 90 secondes).
Sans JWT, les routes privées renvoient `401`. Un rôle insuffisant ou un chemin non autorisé avec JWT
renvoie `403`. Les `404` métier sont toujours propagés après autorisation.

## Discovery Locator facultatif

Le profil `discovery-demo` ajoute `/customer-service/api/customers/**` en conservant les routes explicites.
Il accepte uniquement `customer-service` et uniquement son chemin métier ; il ne publie pas les autres services ni leurs endpoints internes.
L'alias applique les mêmes contrôles JWT/rôles que la route explicite Customer.
La réécriture automatique retire le préfixe `/customer-service` dans ce profil seulement.

```powershell
.\mvnw.cmd -pl services/api-gateway spring-boot:run '-Dspring-boot.run.profiles=discovery-demo'
curl.exe -i http://localhost:8080/customer-service/api/customers/whoami
```

Le profil `tp1` est conservé pour compatibilité : les routes explicites sont désormais déjà actives par défaut.
Les propriétés Gateway utilisent `spring.cloud.gateway.server.webflux`, conformément à la version installée.
Ne pas ajouter Spring MVC/Tomcat à ce module.

## Tests

```powershell
.\mvnw.cmd -B -ntp -pl services/api-gateway test
```

Les tests utilisent de vrais serveurs HTTP sur des ports aléatoires, le vrai LoadBalancer et
`SimpleDiscoveryClient` à la place d'Eureka. Ils couvrent les trois routes, POST/Location,
les erreurs 400/404/409/503, l'absence d'instance, les routes internes et le profil facultatif.
Ils ne démarrent ni Eureka ni les vrais services métier.
Ils utilisent un JWKS local et de vrais JWT signés ; la sécurité reste active dans tous les tests.
