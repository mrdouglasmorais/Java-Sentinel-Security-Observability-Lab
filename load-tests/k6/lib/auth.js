import http from 'k6/http';

const KEYCLOAK_URL = __ENV.KEYCLOAK_URL || 'http://localhost:8180';
const TOKEN_URL = `${KEYCLOAK_URL}/realms/sentinel/protocol/openid-connect/token`;

// Lab-only credentials, defined in infrastructure/keycloak/sentinel-realm.json.
const PASSWORDS = {
  alice: 'alice-lab-password',
  bob: 'bob-lab-password',
  mallory: 'mallory-lab-password',
  carol: 'carol-lab-password',
  ops: 'ops-lab-password',
};

const cache = {};

/**
 * Returns a cached access token for the user, refreshing it 30s before expiry (tokens live 5 minutes).
 * The cache is per VU, which matches how real clients behave.
 */
export function token(username) {
  const cached = cache[username];
  if (cached && cached.expiresAt - 30_000 > Date.now()) {
    return cached.value;
  }
  const response = http.post(TOKEN_URL, {
    grant_type: 'password',
    client_id: 'sentinel-cli',
    username,
    password: PASSWORDS[username],
  }, { tags: { name: 'keycloak_token' } });
  if (response.status !== 200) {
    throw new Error(`token request for ${username} failed: ${response.status} ${response.body}`);
  }
  const body = response.json();
  cache[username] = { value: body.access_token, expiresAt: Date.now() + body.expires_in * 1000 };
  return body.access_token;
}

export function authHeaders(username, extra = {}) {
  return { headers: { Authorization: `Bearer ${token(username)}`, ...extra } };
}
