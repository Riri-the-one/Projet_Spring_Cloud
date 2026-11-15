// Local integration ONLY. Drives the real Keycloak HTML login using Authorization Code + PKCE.
// Never logs tokens or cookies. Node >= 22, no third-party dependency.
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const keycloak = process.env.KEYCLOAK_PUBLIC_URL ?? 'http://localhost:8180';
const gateway = process.env.TICKETFLOW_GATEWAY_URL ?? 'http://localhost:8080';
for (const address of [keycloak, gateway]) {
  const url = new URL(address);
  assert.equal(url.protocol, 'http:', 'This script is exclusively for the local HTTP demonstration');
  assert.ok(['localhost', '127.0.0.1'].includes(url.hostname), 'Only loopback targets are allowed');
}
const issuer = `${keycloak}/realms/ticketflow`;
const redirect = 'http://localhost:4200/security-demo';
const realm = JSON.parse(await readFile(new URL('../infra/keycloak/ticketflow-realm.json', import.meta.url)));
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

async function check(method, path, expected, bearer, body, headers = {}) {
  const response = await fetchTimed(`${gateway}${path}`, {
    method, headers: { ...headers, ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  assert.equal(response.status, expected, `${method} ${path}`);
  console.log(`OK ${expected} ${method} ${path}`);
  return response;
}

const discovery = await fetchTimed(`${issuer}/.well-known/openid-configuration`);
assert.equal(discovery.status, 200);
assert.equal((await discovery.json()).issuer, issuer);
for (const user of realm.users) { tokens.set(user.username, await authenticate(user)); }
if (process.argv.includes('--auth-only')) {
  console.log('Authentification OIDC locale validée ; appels Gateway non exécutés (--auth-only).');
  process.exit(0);
}
const customer = tokens.get('demo-customer');
const organizer = tokens.get('demo-organizer');
const admin = tokens.get('demo-admin');
// Eureka's caches converge after health checks; wait only on backend availability.
const readyDeadline = Date.now() + 180000;
while (true) {
  const ready = await fetchTimed(`${gateway}/api/customers`, { headers: { Authorization: `Bearer ${admin}` } });
  if (ready.status === 200) break;
  assert.equal(ready.status, 503, 'Gateway readiness must not hide an authentication/configuration failure');
  assert.ok(Date.now() < readyDeadline, 'Eureka routing did not become ready in 180 seconds');
  await delay(3000);
}
await check('GET', '/api/events', 200);
await check('GET', '/api/customers', 401);
await check('GET', '/api/bookings', 401, undefined, undefined,
  { 'X-Roles': 'ADMIN', 'X-User-Id': 'demo-admin', 'X-Customer-Id': '1' });
await check('GET', '/api/customers', 403, customer);
await check('GET', '/api/customers', 200, admin);
await check('GET', '/api/bookings', 200, customer);
await check('GET', '/api/bookings', 403, organizer);
await check('POST', '/api/events', 403, customer, {});
await check('GET', '/actuator/gateway/routes', 403, admin);
await check('GET', '/eureka/apps', 403, admin);
await check('GET', '/api/config/message', 403, admin);
const preflight = await check('OPTIONS', '/api/bookings', 200, undefined, undefined, {
  Origin: 'http://localhost:4200', 'Access-Control-Request-Method': 'POST',
  'Access-Control-Request-Headers': 'authorization,content-type',
});
assert.equal(preflight.headers.get('access-control-allow-origin'), 'http://localhost:4200');

// Reject the password grant; the application and this test both use Code + PKCE.
const passwordGrant = await fetchTimed(`${issuer}/protocol/openid-connect/token`, {
  method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({ grant_type: 'password', client_id: 'ticketflow-web',
    username: realm.users[0].username, password: realm.users[0].credentials[0].value }),
});
assert.equal(passwordGrant.status, 400);
assert.equal((await passwordGrant.json()).error, 'unauthorized_client');
console.log('OK password grant désactivé');

// Reuse the six-service demonstration with a real admin token, never written to disk.
const demo = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
  fileURLToPath(new URL('./Test-ArijDemo.ps1', import.meta.url)), '-Gateway', gateway], {
  env: { ...process.env, TICKETFLOW_DEMO_ACCESS_TOKEN: admin }, encoding: 'utf8', timeout: 240000,
});
process.stdout.write(demo.stdout ?? '');
if (demo.status !== 0) {
  process.stderr.write(demo.stderr ?? '');
  throw new Error('Six-service demonstration failed');
}
console.log('Intégration phase 1 validée. Propriété des ressources et validation JWT interne : phase 2.');
