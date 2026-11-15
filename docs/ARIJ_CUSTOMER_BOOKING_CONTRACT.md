# Contrat Customer → Booking pour Salma

Phase 2A : endpoint implémenté dans Customer ; Booking reste inchangé.

- Appel direct interne : `GET /internal/customers/me`, avec le Bearer utilisateur original.
- JWT : issuer Keycloak configuré, audience `ticketflow-api`, RS256 et rôle CUSTOMER ou ADMIN.
- 200 : uniquement `{"customerId":123}`, avec `Cache-Control: no-store`.
- 401 : JWT absent/invalide ; 403 : rôle insuffisant.
- 404 : Problem Detail portant `code=CUSTOMER_PROFILE_NOT_FOUND` ; aucun provisioning automatique.
- Aucun ID, issuer ou subject en paramètre ne permet de choisir un autre profil.
- Aucun routage Gateway. Le Bearer identifie l'utilisateur, pas techniquement Booking.

À réaliser dans une future PR de Salma :

1. Ajouter un client Feign Customer avec propagation ciblée du JWT, compatible avec les threads
   du circuit breaker. Ne pas propager aveuglément le token vers tous les clients HTTP.
2. Utiliser l'ID retourné à la création et pour le filtrage/contrôle des réservations.
3. Supprimer la confiance dans `BookingRequest.customerId` et les paramètres client équivalents.
4. Refuser lecture/annulation d'une réservation d'autrui ; vérifier avant toute restitution de places.
5. Distinguer profil absent, rôle insuffisant et indisponibilité réseau/503 ; aucun fallback vers un ID client.
6. Tester deux clients, les en-têtes falsifiés, les listes filtrées, l'annulation interdite sans mutation
   et les changements de thread. Ne pas journaliser les jetons ou partager un cache entre identités.

À coordonner avec Arij et Manar : profils non associés, politique de suppression/archivage Customer,
préservation des IDs lors de la migration PostgreSQL et future authentification technique interservices.
