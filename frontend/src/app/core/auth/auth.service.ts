import { Injectable, signal } from '@angular/core';
import Keycloak from 'keycloak-js';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly keycloak = new Keycloak({
    url: 'http://localhost:8180',
    realm: 'ticketflow',
    clientId: 'ticketflow-web',
  });

  readonly authenticated = signal(false);

  async init(): Promise<void> {
    const authenticated = await this.keycloak.init({
      onLoad: 'check-sso',
      pkceMethod: 'S256',
      checkLoginIframe: false,
      redirectUri: `${window.location.origin}/`,
    });

    this.authenticated.set(authenticated);
  }

  login(): Promise<void> {
    return this.keycloak.login({
      redirectUri: `${window.location.origin}/`,
    });
  }
  logout(): Promise<void> {
    return this.keycloak.logout({
      redirectUri: `${window.location.origin}/`,
    });
  }
  async getAccessToken(): Promise<string | null> {
    if (!this.authenticated()) {
      return null;
    }

    try {
      await this.keycloak.updateToken(30);
      return this.keycloak.token ?? null;
    } catch {
      this.keycloak.clearToken();
      this.authenticated.set(false);
      return null;
    }
  }
  canManageEvents(): boolean {
    return this.authenticated() && (
      this.keycloak.hasRealmRole('ORGANIZER') ||
      this.keycloak.hasRealmRole('ADMIN')
    );
  }
}
