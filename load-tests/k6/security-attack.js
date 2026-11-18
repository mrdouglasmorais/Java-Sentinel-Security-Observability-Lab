// Simulated hostile client (Mallory, a legitimate customer gone rogue, plus an anonymous attacker):
//   1. forged / garbage bearer tokens                 -> 401, authentication_failed events
//   2. probing other customers' accounts (IDOR/BOLA)  -> 404, ownership_violation events
//   3. calling the lab control plane without the role -> 403, access_denied events
//   4. flooding the API                               -> 429, rate_limited events
//
//   docker compose --profile load run --rm k6 run /scripts/security-attack.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from './lib/random.js';
import { authHeaders } from './lib/auth.js';
import { ACCOUNTS, BASE_URL } from './lib/accounts.js';

// Every response here is "expected": the point is to observe how the API defends itself.
http.setResponseCallback(http.expectedStatuses(200, 401, 403, 404, 429));

export const options = {
  scenarios: {
    forged_tokens: { executor: 'constant-arrival-rate', exec: 'forgedTokens', rate: 5, timeUnit: '1s', duration: '2m', preAllocatedVUs: 5 },
    idor_probing: { executor: 'constant-arrival-rate', exec: 'idorProbing', rate: 3, timeUnit: '1s', duration: '2m', preAllocatedVUs: 5, startTime: '20s' },
    privilege_escalation: { executor: 'constant-arrival-rate', exec: 'labEscalation', rate: 1, timeUnit: '1s', duration: '1m', preAllocatedVUs: 2, startTime: '40s' },
    flood: { executor: 'constant-arrival-rate', exec: 'flood', rate: 400, timeUnit: '1s', duration: '30s', preAllocatedVUs: 100, maxVUs: 300, startTime: '70s' },
  },
};

const FORGED = [
  'Bearer abc.def.ghi',
  'Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiJhZG1pbiIsInJlYWxtX2FjY2VzcyI6eyJyb2xlcyI6WyJsYWItb3BlcmF0b3IiXX19.',
  'Basic YWRtaW46YWRtaW4=',
];

export function forgedTokens() {
  const header = FORGED[Math.floor(Math.random() * FORGED.length)];
  const response = http.get(`${BASE_URL}/api/accounts`, { headers: { Authorization: header }, tags: { name: 'forged_token' } });
  check(response, { 'forged token rejected': (r) => r.status === 401 });
}

export function idorProbing() {
  const target = Math.random() < 0.5 ? ACCOUNTS.aliceChecking : uuidv4();
  const response = http.get(`${BASE_URL}/api/accounts/${target}`, { ...authHeaders('mallory'), tags: { name: 'idor_probe' } });
  // Mallory's own flood (below) throttles her probes too, so 429 is an equally safe answer.
  check(response, { 'foreign account hidden or throttled': (r) => r.status === 404 || r.status === 429 });
}

export function labEscalation() {
  const response = http.post(`${BASE_URL}/api/lab/scenarios/dependency-outage`, null, { ...authHeaders('mallory'), tags: { name: 'lab_escalation' } });
  check(response, { 'lab access denied or throttled': (r) => r.status === 403 || r.status === 429 });
}

export function flood() {
  const response = http.get(`${BASE_URL}/api/accounts`, { ...authHeaders('mallory'), tags: { name: 'flood' } });
  check(response, { 'served or throttled': (r) => r.status === 200 || r.status === 429 });
  sleep(0.01);
}
