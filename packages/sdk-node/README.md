# @railflag/sdk-node

Railflag's OpenFeature provider for Node.js 20+. It downloads your environment's flag config once,
evaluates every flag check in process (no network call per check), and picks up changes within a
second over Server-Sent Events. If the stream drops it polls every 30 s, and if Railflag can't be
reached it keeps serving the last config it had.

```sh
npm install @railflag/sdk-node @openfeature/server-sdk @openfeature/core
```

```ts
import { OpenFeature } from '@openfeature/server-sdk';
import { RailflagProvider } from '@railflag/sdk-node';

await OpenFeature.setProviderAndWait(new RailflagProvider({
  sdkKey: process.env.RAILFLAG_SERVER_KEY!,             // a server key: Environments → keys
  baseUrl: 'https://relay.railflag.com', // required: your Railflag relay
}));
const client = OpenFeature.getClient();

if (await client.getBooleanValue('new-checkout', false, { targetingKey: 'user-42', plan: 'pro', country: 'GR' })) {
  // ...
}
```

- `baseUrl` is required and must start with `http://` or `https://`. The constructor throws otherwise, so an unset environment variable fails at startup instead of sending your key somewhere else.
- `getBooleanValue`, `getStringValue`, `getNumberValue` and `getObjectValue` work as in any OpenFeature provider. A wrong type or unknown flag returns your default with an error code.
- `OpenFeature.addHandler(ProviderEvents.ConfigurationChanged, fn)` tells you when flags change.
- A wrong key makes `setProviderAndWait` reject with `Railflag config request failed: 401`.
- Works from ES modules and CommonJS, and ships its own type declarations.
- Server keys can read every rule. Keep them on the server; browsers use `@railflag/sdk-js` with a client key.

`@openfeature/core` is a peer dependency of `@openfeature/server-sdk`. npm 7+ installs it for you,
but list it yourself if you use pnpm or set `legacy-peer-deps`.

## Evaluation counts for Release Watch

Once a minute the provider sends how many times each flag returned each variation, so
[Release Watch](https://railflag.com/release-watch) can check that a rollout reaches the share you set and
wait for enough users before judging it. Only counts are sent, such as `{ "new-checkout": { "1": 42 } }`.
Contexts, user ids and attributes never leave your process. Sending never delays a flag check, and a failed
send is dropped. Turn it off with `sendExposures: false`. Pending counts are sent when you call
`OpenFeature.close()`.

## Same answers as every other SDK

Evaluation runs `packages/eval`, the same evaluator the relay uses for browsers, and the Python SDK
is tested against its vectors, so a user lands in the same variation whichever SDK evaluates the flag.
