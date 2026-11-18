// Steady, realistic traffic: customers read balances and move money between each other.
// Run it in the background while triggering lab scenarios, then watch the Grafana dashboard.
//
//   docker compose --profile load run --rm k6                         (defaults: 15 req/s for 10 minutes)
//   docker compose --profile load run --rm -e RATE=30 -e DURATION=20m k6

import http from 'k6/http';
import { check } from 'k6';
import { randomIntBetween, uuidv4 } from './lib/random.js';
import { authHeaders } from './lib/auth.js';
import { ACCOUNTS, BASE_URL } from './lib/accounts.js';

// 202 (manual review) and 422 (rejected by risk) are valid business outcomes, not errors.
http.setResponseCallback(http.expectedStatuses(200, 201, 202, 422));

export const options = {
  scenarios: {
    customers: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 15),
      timeUnit: '1s',
      duration: __ENV.DURATION || '10m',
      preAllocatedVUs: 20,
      maxVUs: 200,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:transfer}': ['p(95)<500', 'p(99)<1000'],
  },
};

const CUSTOMERS = [
  { username: 'alice', from: ACCOUNTS.aliceChecking, to: ACCOUNTS.bobChecking },
  { username: 'bob', from: ACCOUNTS.bobChecking, to: ACCOUNTS.aliceChecking },
];

function amount() {
  const roll = Math.random();
  if (roll < 0.02) return randomIntBetween(50_001, 80_000); // high risk -> rejected
  if (roll < 0.15) return randomIntBetween(500, 5_000);     // above fallback limit
  return Number((randomIntBetween(100, 9_999) / 100).toFixed(2)); // everyday payments, 1.00-99.99
}

export default function () {
  const customer = CUSTOMERS[randomIntBetween(0, CUSTOMERS.length - 1)];

  if (Math.random() < 0.35) {
    const response = http.get(`${BASE_URL}/api/accounts`, { ...authHeaders(customer.username), tags: { name: 'list_accounts' } });
    check(response, { 'accounts listed': (r) => r.status === 200 });
    return;
  }

  const response = http.post(
    `${BASE_URL}/api/transfers`,
    JSON.stringify({ sourceAccountId: customer.from, targetAccountId: customer.to, amount: amount() }),
    {
      ...authHeaders(customer.username, { 'Content-Type': 'application/json', 'Idempotency-Key': uuidv4() }),
      tags: { name: 'transfer' },
    },
  );
  check(response, { 'transfer handled': (r) => [201, 202, 422].includes(r.status) });
}
