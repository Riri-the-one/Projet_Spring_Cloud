# Coordination sécurité — phase 2

La phase 1 ajoute Keycloak et les contrôles JWT/rôles du Gateway. Elle ne contrôle pas la propriété
des ressources et ne sécurise pas les appels directs aux services métier.

Répartition officielle : Arij — architecture, Gateway, Docker, sécurité, Customer et Ticket ;
Manar — Event, PostgreSQL et frontend catalogue ; Salma — Booking, paiement, Kafka et notifications ;
Zainab — IA, MCP et Telegram.

## Manar / Event

- Ajouter un propriétaire organisateur immuable, dérivé du couple `(iss, sub)` validé.
- Configurer Event comme Resource Server et contrôler les rôles dans le service.
- Réserver création, modification, publication et clôture à ORGANIZER/ADMIN ; vérifier le propriétaire
  pour ORGANIZER avant toute mutation, y compris lors d'un appel direct au service.
- Exposer anonymement uniquement les événements publiés ; définir les lectures de brouillons/clôturés
  pour leur organisateur et ADMIN. Le détail n'est pas public au Gateway pendant la phase 1.
- Tester organisateur A contre événement de B, rôle insuffisant, accès direct et visibilité publique.
- Coordonner schéma/migrations PostgreSQL et traitement des événements existants sans propriétaire.
- Avec Arij, rouvrir le détail public après ces tests ; avec Salma, stabiliser le contrat Feign
  de lecture d'un événement clôturé (404 ou endpoint interne explicitement autorisé).

## Salma / Booking

- Configurer Booking comme Resource Server ; supprimer la confiance dans `customerId` reçu du client.
- Remplacer le champ de création par une identité résolue côté serveur, avec Arij.
- Filtrer systématiquement les listes par le Customer authentifié ; seul ADMIN peut sélectionner autrui.
- Vérifier la propriété avant lecture/annulation. Lors d'une annulation, vérifier dans la transaction
  avant modification de statut ou restitution de places.
- Tester client A contre réservation de B, filtres falsifiés, absence de profil, accès direct,
  et absence de modification du stock lors d'un refus.
- Définir les identités techniques des futurs traitements paiement/Kafka ; aucun rôle ADMIN implicite.

## Arij / Customer et Ticket

- Configurer Customer comme Resource Server ; associer son profil au couple immuable `(iss, sub)`.
- Ajouter une contrainte d'unicité sur ce couple ; ne pas associer automatiquement par e-mail.
- Préparer l'endpoint interne de résolution du profil (`/internal/customers/me` proposé), sans route
  Gateway, avec JWT validé et propagation ciblée du jeton depuis Booking.
- Définir provisioning, concurrence, profil non associé et migration des profils existants.
- Contrôler la propriété du profil et distinguer suppression du profil et suppression du compte Keycloak.
- Coordonner avec Manar les migrations PostgreSQL et avec Salma les erreurs et contrats HTTP.
- Prévoir que Ticket vérifie aussi le propriétaire de la réservation ; ne pas faire confiance à un ID
  client, un en-tête d'identité ou une requête issue de l'IA.

## Ordre conseillé

1. Valider ensemble matrice, identité, visibilité et codes d'erreur.
2. Livrer la liaison Customer et son contrat interne.
3. Livrer séparément les Resource Servers et contrôles de propriété Event/Booking.
4. Tester les appels directs, Feign et les scénarios multi-utilisateurs.
5. Ouvrir le détail public et intégrer Angular PKCE après validation de la visibilité.

Les droits ADMIN restent explicites. ORGANIZER ne signifie jamais CUSTOMER.
Aucun des changements décrits ici n'est implémenté pendant la phase 1.
