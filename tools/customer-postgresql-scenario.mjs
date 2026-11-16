// Real Keycloak tokens supplied by Test-KeycloakIntegration.mjs; no token is logged or persisted.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { existsSync } from 'node:fs';

export async function verifyCustomerPostgresql({ customer, organizer, admin, customerService, fetchTimed }) {
  async function api(method, path, expected, token, body) {
    const response = await fetchTimed(`${customerService}${path}`, {
      method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(body ? { 'Content-Type': 'application/json' } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
    assert.equal(response.status, expected, `${method} ${path}`);
    console.log(`OK PostgreSQL/JWT ${expected} ${method} ${path}`);
    return response;
  }
  const compose = ['compose', '-f', 'infra/docker-compose.yml', '-f', 'infra/docker-compose.postgresql.yml',
    '-f', 'infra/docker-compose.customer-postgresql.yml'];
  // Keep the existing domain mounts and their canonical order on the shared cluster.
  if(existsSync('infra/postgresql/.local/ticket.password')) compose.push('-f','infra/docker-compose.ticket.yml','-f','infra/docker-compose.ticket-postgresql.yml');
  if(existsSync('infra/postgresql/.local/notification.password')) compose.push('-f','infra/docker-compose.notification.yml');
  compose.push('--profile','postgresql');
  function docker(args, capture = false) {
    const result = spawnSync('docker', [...compose, ...args], { encoding: 'utf8', timeout: 240000 });
    if (result.status !== 0) throw new Error(`Docker Customer validation failed (${args[0]})`);
    if (!capture) console.log(`OK Docker ${args.join(' ')}`);
    return result.stdout;
  }
  const resolved = JSON.parse(docker(['config', '--format', 'json'], true));
  assert.equal(resolved.services['customer-service'].environment.SPRING_JPA_HIBERNATE_DDL_AUTO, 'validate');
  assert.equal(resolved.services['customer-service'].environment.SPRING_DATASOURCE_URL,
    'jdbc:postgresql://postgres:5432/ticketflow_customer');
  assert.equal(resolved.services['customer-service'].secrets.length, 1);
  assert.equal(resolved.services['customer-service'].secrets[0].source, 'postgres_customer_password');
  await api('GET', '/api/customers/me', 401);
  await api('GET', '/api/customers/me', 403, organizer);
  await api('GET', '/api/customers/9223372036854775807', 404, customer);
  let ownResponse = await fetchTimed(`${customerService}/api/customers/me`, { headers: { Authorization: `Bearer ${customer}` } });
  assert.ok([200, 404].includes(ownResponse.status));
  const body = { firstName: 'Persistent', lastName: 'Fixture', email: `${randomUUID()}@example.invalid`, phone: '123' };
  if (ownResponse.status === 404) ownResponse = await api('POST', '/api/customers', 201, customer, body);
  const own = await ownResponse.json();
  await api('POST', '/api/customers', 409, customer, body);
  const snapshot = await (await api('PUT', '/api/customers/me', 200, customer, body)).json();
  assert.equal(snapshot.id, own.id);
  let adminProfile = await fetchTimed(`${customerService}/api/customers/me`, { headers: { Authorization: `Bearer ${admin}` } });
  assert.ok([200, 404].includes(adminProfile.status));
  if (adminProfile.status === 404) {
    adminProfile = await api('POST', '/api/customers', 201, admin, { ...body, email: `${randomUUID()}@example.invalid` });
  }
  const other = await adminProfile.json();
  await api('GET', `/api/customers/${other.id}`, 403, customer);
  await api('PUT', `/api/customers/${other.id}`, 403, customer, body);
  await api('PUT', '/api/customers/me', 409, customer, { ...body, email: other.email });
  assert.deepEqual(await (await api('GET', '/api/customers/me', 200, customer)).json(), snapshot);
  await api('GET', '/api/customers', 200, admin);
  assert.deepEqual(await (await api('GET', '/internal/customers/me', 200, customer)).json(), { customerId: own.id });

  for (const service of ['customer-service', 'postgres']) {
    docker(['restart', '--timeout', '30', service]);
    docker(['up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', service]);
    const deadline = Date.now() + 60000;
    while (true) {
      let response;
      try {
        response = await fetchTimed(`${customerService}/api/customers/me`, { headers: { Authorization: `Bearer ${customer}` } });
      } catch (error) {
        // A pooled HTTP connection can be closed by the deliberate restart. Never mask auth/data failures.
        assert.ok(['ECONNRESET', 'ECONNREFUSED', 'UND_ERR_SOCKET'].includes(error.cause?.code), 'Unexpected recovery error');
        assert.ok(Date.now() < deadline, 'Customer HTTP endpoint did not recover');
        await delay(1000);
        continue;
      }
      if (response.status === 200) {
        assert.deepEqual(await response.json(), snapshot);
        break;
      }
      assert.equal(response.status, 500, 'Only transient JDBC recovery is tolerated');
      assert.ok(Date.now() < deadline, 'Customer JDBC connection did not recover');
      await delay(1000);
    }
    console.log(`OK persistent Customer profile after ${service} restart`);
  }
  console.log('Customer PostgreSQL: real JWT, 401/403/404/409, ownership, rollback and persistence verified.');
}
