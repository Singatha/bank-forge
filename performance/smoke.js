import http from 'k6/http';
import { check, fail, sleep } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const password = 'performance-only-password';

export const options = {
  vus: Number.parseInt(__ENV.VUS || '5', 10),
  duration: __ENV.DURATION || '30s',
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
  },
};

export function setup() {
  const email = `performance-${Date.now()}@example.test`;
  const headers = { 'Content-Type': 'application/json' };
  const registration = http.post(
    `${baseUrl}/api/v1/auth/register`,
    JSON.stringify({ email, password }),
    { headers, tags: { endpoint: 'register' } },
  );
  if (registration.status !== 201) {
    fail(`Registration failed with status ${registration.status}: ${registration.body}`);
  }

  const login = http.post(
    `${baseUrl}/api/v1/auth/login`,
    JSON.stringify({ email, password }),
    { headers, tags: { endpoint: 'login' } },
  );
  if (login.status !== 200) {
    fail(`Login failed with status ${login.status}: ${login.body}`);
  }

  return {
    accessToken: login.json('accessToken'),
    refreshToken: login.json('refreshToken'),
  };
}

export default function (session) {
  const health = http.get(`${baseUrl}/api/v1/health`, {
    tags: { endpoint: 'health' },
  });
  check(health, {
    'health returns 200': (response) => response.status === 200,
  });

  const identity = http.get(`${baseUrl}/api/v1/auth/me`, {
    headers: { Authorization: `Bearer ${session.accessToken}` },
    tags: { endpoint: 'identity' },
  });
  check(identity, {
    'identity returns 200': (response) => response.status === 200,
    'identity has a subject': (response) => Boolean(response.json('id')),
  });

  sleep(0.5);
}

export function teardown(session) {
  http.post(
    `${baseUrl}/api/v1/auth/logout`,
    JSON.stringify({ refreshToken: session.refreshToken }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { endpoint: 'logout' },
    },
  );
}
