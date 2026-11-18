# @railflag/sdk-js

Railflag's OpenFeature provider for browsers. It sends the evaluation context to your Railflag
relay and gets values back, so targeting rules and segment lists never reach the page. Values are
re-evaluated when flags change (Server-Sent Events) and when you change the context.

```sh
npm install @railflag/sdk-js @openfeature/web-sdk @openfeature/core
```

```ts
import { OpenFeature } from '@openfeature/web-sdk';
import { RailflagWebProvider } from '@railflag/sdk-js';

await OpenFeature.setProviderAndWait(
  new RailflagWebProvider({
    clientKey: 'cli_…',                                       // a client key: public, safe in a web page
    baseUrl: 'https://relay.railflag.com',  // required: your Railflag relay
  }),
  { targetingKey: 'user-42', plan: 'pro' },
);
const client = OpenFeature.getClient();
client.getBooleanValue('new-checkout', false);

// When the user changes, re-evaluate:
await OpenFeature.setContext({ targetingKey: 'user-42', plan: 'enterprise' });
```

- `baseUrl` is required and must start with `http://` or `https://`. The constructor throws otherwise.
- **No flicker:** pass `bootstrap: { 'new-checkout': true, … }` with values your server rendered. The provider is then ready at once and serves those values while the first evaluation runs. When it returns you get `ProviderEvents.ConfigurationChanged`. If that evaluation fails, the bootstrapped values keep serving and the provider reports `ERROR` for a rejected key or `STALE` when Railflag can't be reached.
- Without `bootstrap`, `setProviderAndWait` waits for the first evaluation and rejects on a bad key (`Railflag evaluate failed: 401`). Until a first answer arrives, flag checks return your default with `PROVIDER_NOT_READY`.
- `stream: false` turns off live updates.
- Listen for `ProviderEvents.ConfigurationChanged` to re-render when flags change.
- Once a minute, and when the page is hidden, the provider sends how many times each flag returned each variation, for [Release Watch](https://railflag.com/release-watch). It sends counts only, such as `{ "new-checkout": { "1": 3 } }`, and never the context or user ids. Turn it off with `sendExposures: false`.

`@openfeature/core` is a peer dependency of `@openfeature/web-sdk`. npm 7+ installs it for you,
but list it yourself if you use pnpm or set `legacy-peer-deps`.

Browser users: each distinct `targetingKey` evaluated through a client key in a calendar month (UTC) counts as one browser user towards your plan. Use a stable id, such as your user id or an anonymous id you persist in a cookie, so a returning visitor counts once. Contexts without a `targetingKey` are not counted. Server SDKs evaluate locally and are never metered.
