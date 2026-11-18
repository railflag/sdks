/**
 * End-to-end check of both SDKs against a running Railflag (production by default).
 *
 *   SY_EMAIL=you@example.com SY_PASSWORD=... node --experimental-eventsource packages/sdk-node/examples/live-check.ts
 *
 * Signs in to the api, rotates the Development server key (the raw key is only shown once),
 * creates or reuses the flag `sdk-live-check` in Development, then:
 *  - Node SDK (server key, local evaluation, SSE): toggle and rule changes arrive, rule and rollout results are right.
 *  - Browser SDK (client key, remote evaluation, SSE): the same changes arrive through /sdk/v1/evaluate.
 * At the end the flag is switched off and archived.
 */
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { OpenFeature, ProviderEvents } from '@openfeature/server-sdk';
import { RailflagWebProvider } from '../../sdk-js/src/index.ts';
import { RailflagProvider } from '../src/index.ts';

const API = process.env.SY_API ?? 'https://api.railflag.com';
const RELAY = process.env.SY_RELAY ?? 'https://relay.railflag.com';
const FLAG = 'sdk-live-check';
const ENV = 'development';
const p = (rest: string) => `/v1/projects/default${rest}`;

let cookie = '';
async function api<T = any>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(API + path, {
    method,
    headers: { cookie, ...(body === undefined ? {} : { 'content-type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const set = res.headers.get('set-cookie');
  if (set) cookie = set.split(';')[0]!;
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status} ${text}`);
  return text ? JSON.parse(text) : (undefined as T);
}

let failures = 0;
function check(label: string, ok: boolean, detail = '') {
  if (!ok) failures++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? `  (${detail})` : ''}`);
}

/** Resolves when `test()` is true, polling every 5 ms; returns elapsed ms or -1 on timeout. */
async function waitFor(test: () => boolean | Promise<boolean>, timeoutMs = 10_000) {
  const t0 = performance.now();
  while (performance.now() - t0 < timeoutMs) {
    if (await test()) return Math.round(performance.now() - t0);
    await new Promise((r) => setTimeout(r, 5));
  }
  return -1;
}

async function setTargeting(targeting: unknown) {
  const flag = await api('GET', p(`/flags/${FLAG}`));
  const env = flag.environments.find((e: any) => e.key === ENV);
  return api('PUT', p(`/flags/${FLAG}/environments/${ENV}/targeting`), { targeting, baseVersion: env.version });
}

const proRule = (fallthrough: unknown, enabled = true) => ({
  enabled, offVariation: 0, fallthrough, targets: [], prerequisites: [],
  rules: [{ id: 'pro', name: 'Pro plan', clauses: [{ attr: 'plan', op: 'in', values: ['pro'] }], serve: { variation: 1 } }],
});

// ---- setup -----------------------------------------------------------------------------
const email = process.env.SY_EMAIL;
const password = process.env.SY_PASSWORD;
if (!email || !password) throw new Error('Set SY_EMAIL and SY_PASSWORD');
await api('POST', '/v1/auth/login', { email, password });
const { environments } = await api('GET', p('/environments'));
const clientKey = environments.find((e: any) => e.key === ENV).clientKey as string;
const { key: serverKey } = await api('POST', p(`/environments/${ENV}/keys/server/rotate`));
console.log(`signed in; ${ENV} server key rotated (${serverKey.slice(0, 12)}…), client key ${clientKey.slice(0, 12)}…`);

try {
  await api('POST', p('/flags'), { key: FLAG, name: 'SDK live check', type: 'boolean', description: 'Created by packages/sdk-node/examples/live-check.ts' });
  console.log(`created flag ${FLAG}`);
} catch (e) {
  if (!String(e).includes('409')) throw e;
  await api('PATCH', p(`/flags/${FLAG}`), { archived: false });
  console.log(`reusing flag ${FLAG}`);
}
await setTargeting({ ...proRule({ variation: 1 }, false), rules: [] });

// ---- Node SDK --------------------------------------------------------------------------
console.log('\nNode SDK (@railflag/sdk-node, local evaluation)');
const provider = new RailflagProvider({ sdkKey: serverKey, baseUrl: RELAY });
let changes = 0;
provider.events.addHandler(ProviderEvents.ConfigurationChanged, () => void changes++);
const t0 = performance.now();
await OpenFeature.setProviderAndWait(provider);
const client = OpenFeature.getClient();
check('provider ready', true, `${Math.round(performance.now() - t0)} ms, config v${provider.source.snapshot?.version}`);
await waitFor(() => provider.source.streaming, 5000);
check('SSE stream connected', provider.source.streaming);

const off = await client.getBooleanDetails(FLAG, true, { targetingKey: 'user-1' });
check('flag off → false', off.value === false, `reason ${off.reason}`);

await api('POST', p(`/flags/${FLAG}/environments/${ENV}/toggle`), { enabled: true });
let ms = await waitFor(async () => (await client.getBooleanValue(FLAG, false, { targetingKey: 'user-1' })) === true);
check('toggle on reaches the SDK over SSE', ms >= 0, `${ms} ms after the api answered`);
check('ConfigurationChanged event fired', changes > 0);

await setTargeting(proRule({ variation: 0 }));
ms = await waitFor(async () => (await client.getBooleanValue(FLAG, true, { targetingKey: 'user-1', plan: 'free' })) === false);
check('rule change arrives', ms >= 0, `${ms} ms`);
const pro = await client.getBooleanDetails(FLAG, false, { targetingKey: 'user-1', plan: 'pro' });
check('plan=pro → true via rule', pro.value === true && pro.reason === 'TARGETING_MATCH', `reason ${pro.reason}, rule ${pro.flagMetadata.ruleId}`);
const free = await client.getBooleanDetails(FLAG, true, { targetingKey: 'user-1', plan: 'free' });
check('plan=free → false via fallthrough', free.value === false, `reason ${free.reason}`);

await setTargeting(proRule({ rollout: { weights: [70_000, 30_000] } }));
await waitFor(() => (provider.source.snapshot?.version ?? 0) > (pro.flagMetadata.configVersion as number));
let on = 0;
const N = 10_000;
const t1 = performance.now();
for (let i = 0; i < N; i++) if (await client.getBooleanValue(FLAG, false, { targetingKey: `user-${i}`, plan: 'free' })) on++;
const perEval = ((performance.now() - t1) * 1000) / N;
check('30% rollout over 10,000 users', Math.abs(on / N - 0.3) < 0.02, `${((on / N) * 100).toFixed(1)}% true, ${perEval.toFixed(1)} µs per evaluation`);
const a1 = await client.getBooleanValue(FLAG, false, { targetingKey: 'user-42', plan: 'free' });
const a2 = await client.getBooleanValue(FLAG, false, { targetingKey: 'user-42', plan: 'free' });
check('same user gets the same answer', a1 === a2);
const missing = await client.getBooleanDetails('does-not-exist', true, { targetingKey: 'user-1' });
check('unknown flag → default value', missing.value === true && missing.errorCode === 'FLAG_NOT_FOUND', missing.errorCode);
await OpenFeature.close();

// ---- Browser SDK (run in Node with --experimental-eventsource) -------------------------
console.log('\nBrowser SDK (@railflag/sdk-js, remote evaluation)');
if (typeof EventSource === 'undefined') console.log('note: no EventSource; run with --experimental-eventsource to test streaming');
const require = createRequire(new URL('../../sdk-js/package.json', import.meta.url));
const webMod = await import(pathToFileURL(require.resolve('@openfeature/web-sdk')).href);
const { OpenFeature: Web, ProviderEvents: WebEvents } = (webMod.OpenFeature ? webMod : webMod.default) as typeof import('@openfeature/web-sdk');
const web = new RailflagWebProvider({ clientKey, baseUrl: RELAY });
let webChanges = 0;
web.events.addHandler(WebEvents.ConfigurationChanged, () => void webChanges++);
await Web.setContext({ targetingKey: 'user-1', plan: 'pro' });
const t2 = performance.now();
await Web.setProviderAndWait(web);
const wc = Web.getClient();
const w1 = wc.getBooleanDetails(FLAG, false);
check('provider ready, plan=pro → true', w1.value === true, `${Math.round(performance.now() - t2)} ms, reason ${w1.reason}`);

await api('POST', p(`/flags/${FLAG}/environments/${ENV}/toggle`), { enabled: false });
ms = await waitFor(() => wc.getBooleanValue(FLAG, true) === false);
check('toggle off reaches the browser SDK over SSE', ms >= 0, `${ms} ms`);
check('ConfigurationChanged event fired', webChanges > 0);

await api('POST', p(`/flags/${FLAG}/environments/${ENV}/toggle`), { enabled: true });
await waitFor(() => wc.getBooleanValue(FLAG, false) === true);
await Web.setContext({ targetingKey: 'user-1', plan: 'free' });
const w2 = wc.getBooleanDetails(FLAG, true);
check('context change re-evaluates (plan=free → rollout)', w2.reason === 'SPLIT', `value ${w2.value}, reason ${w2.reason}`);

const cors = await fetch(`${RELAY}/sdk/v1/evaluate`, { method: 'OPTIONS', headers: { origin: 'https://shop.example', 'access-control-request-method': 'POST', 'access-control-request-headers': 'authorization,content-type' } });
check('CORS preflight allows browser calls', cors.ok && cors.headers.get('access-control-allow-origin') === 'https://shop.example', `${cors.status}`);
const bad = await fetch(`${RELAY}/sdk/v1/evaluate`, { method: 'POST', headers: { authorization: 'cli_nope', 'content-type': 'application/json' }, body: '{}' });
check('wrong client key is rejected', bad.status === 401, `${bad.status}`);
await Web.close();

// ---- cleanup ---------------------------------------------------------------------------
await api('POST', p(`/flags/${FLAG}/environments/${ENV}/toggle`), { enabled: false });
await api('PATCH', p(`/flags/${FLAG}`), { archived: true });
console.log(`\n${FLAG} switched off and archived. ${failures ? `${failures} check(s) failed` : 'All checks passed.'}`);
process.exit(failures ? 1 : 0);
