# Railflag SDKs

OpenFeature providers for [Railflag](https://railflag.com), the hosted feature flag platform with local evaluation, approvals and [Release Watch](https://railflag.com/release-watch).

| Language | Package | Install | Evaluation |
| --- | --- | --- | --- |
| Node.js | [`@railflag/sdk-node`](https://www.npmjs.com/package/@railflag/sdk-node) | `npm install @openfeature/server-sdk @railflag/sdk-node` | Local, streaming updates |
| Browser | [`@railflag/sdk-js`](https://www.npmjs.com/package/@railflag/sdk-js) | `npm install @openfeature/web-sdk @railflag/sdk-js` | Remote, live updates |
| Python | [`railflag-openfeature`](https://pypi.org/project/railflag-openfeature/) | `pip install railflag-openfeature` | Local, streaming updates |
| Go | [`railflag.com/go/openfeature`](https://pkg.go.dev/railflag.com/go/openfeature) | `go get railflag.com/go/openfeature` | Local, streaming updates |
| Java | [`com.railflag:railflag-openfeature`](https://central.sonatype.com/artifact/com.railflag/railflag-openfeature) | Maven Central | Local, streaming updates |

Each SDK's README has a quick start: [Node](packages/sdk-node), [browser](packages/sdk-js), [Python](packages/sdk-python), [Go](packages/sdk-go), [Java](packages/sdk-java).

## One evaluator, five languages

Server SDKs download the environment's rules and decide in memory, so a flag check never waits on the network. `packages/eval` is the reference evaluator (TypeScript). The Python, Go and Java SDKs are ports of it, and every SDK is tested against the same vectors in `packages/eval/vectors/eval-vectors.json`, so a user gets the same variation whichever language asks.

## Running the tests

```sh
pnpm install && pnpm test                         # evaluator, schema, Node and browser SDKs
cd packages/sdk-python && uv run pytest           # Python
cd packages/sdk-go && go test ./...               # Go
cd packages/sdk-java && ./gradlew test            # Java
```

## About this repository

This is a read-only mirror of the SDK folders in Railflag's main repository, updated automatically on every change. Issues are welcome here; pull requests are reviewed and applied in the main repository, with credit. The Go module is served from [railflag/go-openfeature](https://github.com/railflag/go-openfeature), because Go needs the module at a repository root.

Licensed under the [Apache License 2.0](LICENSE).
