// Local fictitious demo accounts only; Code + PKCE, never logs a bearer token.
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { readFile } from 'node:fs/promises';
const keycloak = process.env.KEYCLOAK_PUBLIC_URL ?? 'http://localhost:8180';
const gateway = process.env.TICKETFLOW_GATEWAY_URL ?? 'http://localhost:8080';
const customerService = process.env.TICKETFLOW_CUSTOMER_URL ?? 'http://localhost:8081';
for (const address of [keycloak, gateway, customerService]) {
  const url = new URL(address);
  assert.equal(url.protocol, 'http:', 'This script is exclusively for the local HTTP demonstration');
  assert.ok(['localhost', '127.0.0.1'].includes(url.hostname), 'Only loopback targets are allowed');
}
const issuer = `${keycloak}/realms/ticketflow`;
const redirect = 'http://localhost:4200/security-demo';
const realm = JSON.parse(await readFile(new URL('../../infra/keycloak/ticketflow-realm.json', import.meta.url)));
const tokens = new Map();

async function fetchTimed(url, options = {}) {
  return fetch(url, { ...options, signal: AbortSignal.timeout(15000) });
}

async function authenticate(user) {
  const verifier = randomBytes(48).toString('base64url');
  const state = randomBytes(24).toString('hex');
  const nonce = randomBytes(24).toString('hex');
  const challenge = createHash('sha256').update(verifier).digest('base64url');
  const authorization = new URL(`${issuer}/protocol/openid-connect/auth`);
  authorization.search = new URLSearchParams({
    client_id: 'ticketflow-web', response_type: 'code', scope: 'openid', redirect_uri: redirect,
    state, nonce, code_challenge: challenge, code_challenge_method: 'S256',
  });
  const cookies = new Map();
  async function sessionRequest(url, options = {}) {
    assert.equal(new URL(url).origin, new URL(keycloak).origin, 'Credentials must stay on local Keycloak');
    const response = await fetchTimed(url, {
      ...options, redirect: 'manual',
      headers: { ...options.headers, Cookie: [...cookies].map(([name, value]) => `${name}=${value}`).join('; ') },
    });
    for (const cookie of response.headers.getSetCookie()) {
      const pair = cookie.split(';', 1)[0];
      const separator = pair.indexOf('=');
      cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
    }
    return response;
  }
  const page = await sessionRequest(authorization);
  assert.equal(page.status, 200, 'Keycloak login page');
  const html = await page.text();
  const form = html.match(/<form\b[^>]*\bid="kc-form-login"[^>]*>/)?.[0];
  assert.ok(form, 'Expected the standard Keycloak local login form');
  const action = form.match(/\baction="([^"]+)"/)?.[1]?.replaceAll('&amp;', '&');
  assert.ok(action, 'Login form action');
  const loggedIn = await sessionRequest(action, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username: user.username, password: user.credentials[0].value, credentialId: '' }),
  });
  assert.equal(loggedIn.status, 302, 'Login must redirect with an authorization code');
  const callback = new URL(loggedIn.headers.get('location'));
  assert.equal(callback.origin + callback.pathname, redirect);
  assert.equal(callback.searchParams.get('state'), state);
  assert.ok(callback.searchParams.get('code'));
  const response = await fetchTimed(`${issuer}/protocol/openid-connect/token`, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'authorization_code', client_id: 'ticketflow-web',
      redirect_uri: redirect, code: callback.searchParams.get('code'), code_verifier: verifier }),
  });
  assert.equal(response.status, 200, 'PKCE code exchange');
  const result = await response.json();
  // Decoding here checks fixture configuration only; Gateway verifies the signature on every API call.
  const claims = JSON.parse(Buffer.from(result.access_token.split('.')[1], 'base64url'));
  const header = JSON.parse(Buffer.from(result.access_token.split('.')[0], 'base64url'));
  assert.equal(header.alg, 'RS256');
  assert.equal(claims.iss, issuer);
  assert.ok([claims.aud].flat().includes('ticketflow-api'));
  assert.ok(claims.sub && claims.exp > Date.now() / 1000);
  assert.deepEqual([...claims.realm_access.roles].sort(), [...user.realmRoles].sort());
  const idClaims = JSON.parse(Buffer.from(result.id_token.split('.')[1], 'base64url'));
  assert.equal(idClaims.nonce, nonce);
  console.log(`OK OIDC Code + PKCE S256 : ${user.username}, rôle ${user.realmRoles.join(', ')}, issuer/audience/RS256`);
  return result.access_token;
}


export async function demoTokens() {
  const discovery = await fetchTimed(`${issuer}/.well-known/openid-configuration`);
  assert.equal(discovery.status, 200);
  assert.equal((await discovery.json()).issuer, issuer);
  for (const user of realm.users) tokens.set(user.username, await authenticate(user));
  return tokens;
}
