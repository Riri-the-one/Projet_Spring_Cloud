# Config Server

Run from the repository root:

```sh
mvn -pl services/config-server -am install -DskipTests
mvn -pl services/config-server spring-boot:run
```

The server listens on port 8888 and uses the native backend. Its bundled
`src/main/resources/config-repo/customer-service.yml` is available at
`http://localhost:8888/customer-service/default`.

Set `CONFIG_REPO_LOCATION` to a file URI (for example,
`file:///C:/config/ticketflow/`) to use an external configuration directory.

Customer Service connects to `http://localhost:8888` by default. Set
`CONFIG_SERVER_URL` to change this address. The import is optional so that
Customer Service can also start with its local configuration when Config Server
is unavailable. Restart Customer Service after changing remote configuration.
