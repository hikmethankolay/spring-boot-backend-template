// k6 load test: virtual users follow the journeys real clients take through the API, at the same time.
//   browse     returning users: log in, read their profile, export their data, refresh the token, log out
//   signup     new users: register, log in, edit the profile, change the password, delete the account
//   admin      the owner pages through and opens user accounts (needs ADMIN_USERNAME and ADMIN_PASSWORD)
//   anonymous  unauthenticated traffic: password reset requests, calls without a token and, with
//              FAILED_LOGINS=true, wrong passwords
// Run it with scripts/load-test.sh; see that script for the target app's settings.
//
// Environment:
//   BASE_URL      default http://localhost:8080
//   PROFILE       smoke | load (default) | stress | spike | soak | throughput (see PROFILES)
//   VUS           peak virtual users of the browse journey (default 20); the others scale from it
//   DURATION      how long the peak is held (default depends on PROFILE)
//   THINK_TIME    average pause in seconds between a user's requests (default 1, randomised by +-50%;
//                 the throughput profile does not pause unless it is set)
//   USERS         returning users registered in setup and deleted in teardown (default 20)
//   FAILED_LOGINS true adds wrong-password logins. Every failure counts against the client IP, and k6 sends
//                 everything from one IP, so start the target with a high LOGIN_MAX_ATTEMPTS and a short
//                 LOGIN_LOCK_DURATION first (see scripts/load-test.sh).
import http from 'k6/http';
import exec from 'k6/execution';
import { check, group, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// Sign-ups create the account only when the emailed link is opened; the link is read from Mailpit.
const MAILPIT_URL = __ENV.MAILPIT_URL || 'http://localhost:8025';
const PROFILE = __ENV.PROFILE || 'load';
const VUS = Number(__ENV.VUS || 20);
const THINK_TIME = Number(__ENV.THINK_TIME ?? (PROFILE === 'throughput' ? 0 : 1));
const USERS = Number(__ENV.USERS || 20);
const FAILED_LOGINS = __ENV.FAILED_LOGINS === 'true';
const ADMIN_USERNAME = __ENV.ADMIN_USERNAME;
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD;

const PASSWORD = 'Load#12345-passphrase';
const NEW_PASSWORD = 'Load#67890-passphrase';
const SORTABLE = ['id', 'username', 'email', 'role', 'emailVerified', 'createdAt', 'updatedAt'];

// Load shapes as [stage duration, share of each journey's peak]. DURATION replaces the hold stages ('hold').
const PROFILES = {
  smoke: { hold: '30s', stages: [['hold', 1]] },
  load: { hold: '3m', stages: [['1m', 1], ['hold', 1], ['30s', 0]] },
  stress: { hold: '2m', stages: [['1m', 1], ['hold', 1], ['1m', 2], ['hold', 2], ['1m', 3], ['hold', 3], ['1m', 0]] },
  spike: { hold: '1m', stages: [['30s', 0.2], ['10s', 5], ['hold', 5], ['10s', 0.2], ['1m', 0.2], ['10s', 0]] },
  soak: { hold: '1h', stages: [['2m', 1], ['hold', 1], ['2m', 0]] },
};

const journeySuccess = new Rate('journey_success');
const journeyDuration = new Trend('journey_duration', true);
const rateLimited = new Counter('rate_limited');

function stages(peak) {
  const profile = PROFILES[PROFILE];
  const hold = __ENV.DURATION || profile.hold;
  // Every journey keeps at least one user while its share is above zero, so small peaks still run.
  return profile.stages.map(([duration, share]) => ({
    duration: duration === 'hold' ? hold : duration,
    target: share === 0 ? 0 : Math.max(1, Math.round(peak * share)),
  }));
}

function journey(name, peak) {
  const shape = stages(peak);
  // Ramps start from zero; only a profile that opens with a hold starts at its target.
  const startVUs = PROFILES[PROFILE].stages[0][0] === 'hold' ? shape[0].target : 0;
  return { executor: 'ramping-vus', exec: name, startVUs, stages: shape, gracefulRampDown: '30s' };
}

function scenarios() {
  if (PROFILE === 'throughput') {
    // The single-endpoint benchmark: every user reads /users/me as fast as it can.
    return { throughput: { executor: 'constant-vus', exec: 'throughput', vus: VUS, duration: __ENV.DURATION || '30s' } };
  }
  if (!PROFILES[PROFILE]) {
    throw new Error(`Unknown PROFILE '${PROFILE}'. Use ${Object.keys(PROFILES).join(', ')} or throughput.`);
  }
  const result = {
    browse: journey('browse', VUS),
    signup: journey('signup', Math.ceil(VUS / 10)),
    anonymous: journey('anonymous', Math.ceil(VUS / 10)),
  };
  if (ADMIN_USERNAME && ADMIN_PASSWORD) {
    result.admin = journey('admin', 1);
  }
  return result;
}

export const options = {
  scenarios: scenarios(),
  setupTimeout: '5m',
  teardownTimeout: '5m',
  // Every threshold is its own pass/fail line, so one slow kind of request cannot hide behind fast ones.
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    journey_success: ['rate>0.99'],
    rate_limited: ['count==0'],
    // Token-checked reads and writes.
    'http_req_duration{kind:read}': ['p(95)<300'],
    'http_req_duration{kind:write}': ['p(95)<500'],
    // Requests that hash or check a password with Argon2id (login, register, password change, account deletion).
    'http_req_duration{kind:password}': ['p(95)<1500'],
  },
};

// Sends a JSON request tagged with a stable name (ids and query strings removed) and a kind, for the
// thresholds above. Statuses in `expected` are not counted as failed requests (e.g. 401 for a wrong password).
function call(method, path, { body, token, name, kind = 'read', expected } = {}) {
  const params = {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: name || `${method} ${path.split('?')[0]}`, kind },
  };
  if (token) {
    params.headers.Authorization = `Bearer ${token}`;
  }
  if (expected) {
    params.responseCallback = http.expectedStatuses(...expected);
  }
  const response = http.request(method, `${BASE_URL}${path}`, body === undefined ? null : JSON.stringify(body), params);
  if (response.status === 429) {
    rateLimited.add(1);
  }
  return response;
}

function expect(response, status, label) {
  return check(response, { [`${label}: ${status}`]: (r) => r.status === status });
}

function think() {
  if (THINK_TIME > 0) {
    sleep(THINK_TIME * (0.5 + Math.random()));
  }
}

function pick(items) {
  return items[Math.floor(Math.random() * items.length)];
}

function login(identifier, password, rememberMe = false) {
  const response = call('POST', '/api/v1/auth/login', { body: { identifier, password, rememberMe }, kind: 'password' });
  return expect(response, 200, 'login') ? response.json() : null;
}

function register(username) {
  const response = call('POST', '/api/v1/auth/register',
    { body: { username, email: `${username}@example.com`, password: PASSWORD }, kind: 'password' });
  return expect(response, 202, 'register') && confirmEmail(`${username}@example.com`);
}

// Opens the newest verification link sent to an address, which creates the account. The outbox may send a
// moment after the response, so Mailpit is polled briefly.
function confirmEmail(address) {
  const tags = { tags: { name: 'mailpit', kind: 'mail' } };
  for (let attempt = 0; attempt < 10; attempt++) {
    const search = http.get(`${MAILPIT_URL}/api/v1/search?limit=1&query=${encodeURIComponent(`to:${address}`)}`, tags);
    const id = search.status === 200 ? search.json('messages.0.ID') : null;
    if (id) {
      const text = http.get(`${MAILPIT_URL}/api/v1/message/${id}`, tags).json('Text') || '';
      const link = /verify-email\?token=([A-Za-z0-9_-]+)/.exec(text);
      return link !== null
        && expect(call('POST', '/api/v1/auth/verify-email', { body: { token: link[1] }, kind: 'write' }), 204, 'verify email');
    }
    sleep(0.2);
  }
  return false;
}

// Runs one journey, recording whether every step passed and how long it took.
function run(name, steps) {
  // Each iteration is a new client: drop the refresh token cookie of the previous one.
  http.cookieJar().clear(BASE_URL);
  const start = Date.now();
  let ok = false;
  group(name, () => {
    ok = steps() !== false;
  });
  journeySuccess.add(ok, { journey: name });
  journeyDuration.add(Date.now() - start, { journey: name });
}

export function setup() {
  const runId = Date.now().toString(36);
  const users = [];
  for (let i = 0; i < USERS; i++) {
    const username = `lt${runId}u${i}`;
    if (!register(username)) {
      throw new Error(`Registering ${username} failed. Is the target app running with RATE_LIMIT_ENABLED=false?`);
    }
    users.push(username);
  }
  if (ADMIN_USERNAME && ADMIN_PASSWORD && login(ADMIN_USERNAME, ADMIN_PASSWORD) === null) {
    throw new Error(`Logging in as ${ADMIN_USERNAME} failed; check ADMIN_USERNAME and ADMIN_PASSWORD.`);
  }
  const tokens = login(users[0], PASSWORD);
  if (tokens === null) {
    throw new Error('Login failed. Is REQUIRE_VERIFIED_EMAIL off on the target app?');
  }
  return { runId, users, token: tokens.accessToken };
}

// A returning user: logs in by username or email, reads the profile a few times, sometimes exports
// their data, renews the access token with the refresh token and usually logs out.
export function browse(data) {
  run('browse', () => {
    const username = pick(data.users);
    const identifier = Math.random() < 0.5 ? username : `${username}@example.com`;
    let tokens = login(identifier, PASSWORD, Math.random() < 0.3);
    if (!tokens) return false;
    think();

    const reads = 2 + Math.floor(Math.random() * 5);
    for (let i = 0; i < reads; i++) {
      if (!expect(call('GET', '/api/v1/users/me', { token: tokens.accessToken }), 200, 'read profile')) return false;
      think();
    }
    if (Math.random() < 0.2) {
      if (!expect(call('GET', '/api/v1/users/me/export', { token: tokens.accessToken }), 200, 'export data')) return false;
      think();
    }

    const refreshed = call('POST', '/api/v1/auth/refresh', { body: { refreshToken: tokens.refreshToken }, kind: 'write' });
    if (!expect(refreshed, 200, 'refresh token')) return false;
    tokens = refreshed.json();
    if (!expect(call('GET', '/api/v1/users/me', { token: tokens.accessToken }), 200, 'read profile')) return false;
    think();

    // Some users close the tab instead of logging out; their sessions simply expire.
    if (Math.random() < 0.7) {
      const logout = call('POST', '/api/v1/auth/logout', { body: { refreshToken: tokens.refreshToken }, kind: 'write' });
      return expect(logout, 204, 'logout');
    }
    return true;
  });
}

// A new user: signs up, edits the profile, changes the password (which ends every session),
// logs in again and finally deletes the account, so the test leaves no users behind.
export function signup(data) {
  run('signup', () => {
    const username = `lt${data.runId}s${exec.vu.idInTest}i${exec.vu.iterationInScenario}`;
    if (!register(username)) return false;
    think();
    let tokens = login(username, PASSWORD);
    if (!tokens) return false;
    if (!expect(call('GET', '/api/v1/users/me', { token: tokens.accessToken }), 200, 'read profile')) return false;
    think();

    const renamed = `${username}r`;
    const profile = { username: renamed };
    if (Math.random() < 0.3) {
      // Changing the email needs the current password, so it costs a password check.
      profile.email = `${renamed}@example.com`;
      profile.currentPassword = PASSWORD;
    }
    const updated = call('PATCH', '/api/v1/users/me',
      { body: profile, token: tokens.accessToken, kind: profile.email ? 'password' : 'write' });
    if (!expect(updated, 200, 'update profile')) return false;
    think();
    if (!expect(call('GET', '/api/v1/users/me/export', { token: tokens.accessToken }), 200, 'export data')) return false;
    think();

    const changed = call('PUT', '/api/v1/users/me/password',
      { body: { currentPassword: PASSWORD, newPassword: NEW_PASSWORD }, token: tokens.accessToken, kind: 'password' });
    if (!expect(changed, 204, 'change password')) return false;
    think();
    tokens = login(renamed, NEW_PASSWORD);
    if (!tokens) return false;
    think();

    const deleted = call('DELETE', '/api/v1/users/me',
      { body: { password: NEW_PASSWORD }, token: tokens.accessToken, kind: 'password' });
    return expect(deleted, 204, 'delete account');
  });
}

// The owner browsing the user list: random pages, sizes and sort orders, then opening accounts.
// Accounts can vanish between listing and opening (signup deletes its users), so 404 is fine there.
export function admin() {
  run('admin', () => {
    const tokens = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    if (!tokens) return false;
    think();

    let totalPages = 1;
    const pages = 2 + Math.floor(Math.random() * 3);
    for (let i = 0; i < pages; i++) {
      const page = Math.floor(Math.random() * totalPages);
      const size = pick([10, 20, 50]);
      const sort = `${pick(SORTABLE)},${pick(['asc', 'desc'])}`;
      const list = call('GET', `/api/v1/users?page=${page}&size=${size}&sort=${sort}`, { token: tokens.accessToken });
      if (!expect(list, 200, 'list users')) return false;
      totalPages = Math.max(1, list.json('page.totalPages'));
      think();

      const users = list.json('content');
      if (users.length > 0) {
        const user = call('GET', `/api/v1/users/${pick(users).id}`,
          { token: tokens.accessToken, name: 'GET /api/v1/users/{id}', expected: [200, 404] });
        if (!check(user, { 'open user: 200 or 404': (r) => r.status === 200 || r.status === 404 })) return false;
        think();
      }
    }

    const logout = call('POST', '/api/v1/auth/logout', { body: { refreshToken: tokens.refreshToken }, kind: 'write' });
    return expect(logout, 204, 'logout');
  });
}

// Traffic without a session: forgotten-password requests (202 whether or not the address exists),
// clients calling without a token and, with FAILED_LOGINS=true, mistyped passwords.
export function anonymous(data) {
  run('anonymous', () => {
    const roll = Math.random();
    let ok;
    if (FAILED_LOGINS && roll < 0.4) {
      // Unknown accounts only: wrong passwords for a test user would lock that user out of browse.
      const response = call('POST', '/api/v1/auth/login',
        { body: { identifier: `nobody${data.runId}${Math.floor(Math.random() * 1e6)}`, password: 'Wrong#123' },
          name: 'POST /api/v1/auth/login (wrong password)', kind: 'password', expected: [401] });
      ok = expect(response, 401, 'wrong password');
    } else if (roll < 0.7) {
      const response = call('POST', '/api/v1/auth/password-reset',
        { body: { email: `nobody${data.runId}@example.com` }, kind: 'write' });
      ok = expect(response, 202, 'request password reset');
    } else {
      const response = call('GET', '/api/v1/users/me', { name: 'GET /api/v1/users/me (no token)', expected: [401] });
      ok = expect(response, 401, 'no token');
    }
    think();
    return ok;
  });
}

// PROFILE=throughput: one endpoint as fast as possible, to compare raw capacity between versions.
export function throughput(data) {
  expect(call('GET', '/api/v1/users/me', { token: data.token }), 200, 'read profile');
  think();
}

// Deletes the returning users registered in setup.
export function teardown(data) {
  for (const username of data.users) {
    const tokens = login(username, PASSWORD);
    if (tokens) {
      call('DELETE', '/api/v1/users/me', { body: { password: PASSWORD }, token: tokens.accessToken, kind: 'password' });
    }
  }
}
