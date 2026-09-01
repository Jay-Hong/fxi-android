# Android v2 contract corpus

`export_client_fixtures.py` refreshes the checked-in corpus at
`app/src/test/resources/contracts/v2/` from the sibling `exchange-rate` server.
Ordinary Android CI reads only those checked-in files; it never installs or
imports the server's Python environment.

## Refresh

Use an isolated Python environment with the server's locked dependencies:

```bash
python3 -m venv /tmp/fxi-contract-venv
/tmp/fxi-contract-venv/bin/pip install -r ../exchange-rate/requirements.lock.txt
/tmp/fxi-contract-venv/bin/python tools/contracts/export_client_fixtures.py \
  --server-repo ../exchange-rate
```

The exporter refuses to describe a dirty `app/`, `nginx/`, `tests/`, or
`requirements.lock.txt` source tree as clean. The manifest records the full
server commit and dependency-lock SHA-256. `fixtureAsOf` is the deterministic
clock injected into fixture builders, not the wall-clock time when a developer
ran the exporter.

After a refresh, prove determinism before review:

```bash
/tmp/fxi-contract-venv/bin/python tools/contracts/export_client_fixtures.py \
  --server-repo ../exchange-rate --check
python3 -m unittest discover -s tools/contracts -p 'test_*.py'
./gradlew testDebugUnitTest
```

Do not hand-edit generated files. Make contract changes in the server or in the
exporter's explicit adversarial mutations, then regenerate.

## Origin labels

- `route`: a FastAPI `TestClient` request traversed routing and response
  serialization. Server lifespan is deliberately not started.
- `websocket-dispatcher`: the real topic dispatcher handled the frame, but the
  outer `/ws` ASGI route was not traversed.
- `builder`, `pydantic-schema`, `registry`: the named executable server symbol
  produced or validated the payload without a route claim.
- `external`: representative proxy shape; bytes are explicitly not exact.
- `adversarial`: one declared deterministic mutation of another checked valid
  fixture. Tests compare each payload to `derivedFrom` and fail on undeclared
differences. Duplicate-key JSON remains raw bytes and is checked as one exact
insertion into the canonical ACK.

`registry/allowed-identifiers.json` records graph identifiers under
`graphSeriesIdsByTabPeriod`, derived from the entitled server catalog. This
scoped map is authoritative; there is deliberately no flat graph-series
allowlist because the S2 contract requires `(tab, seriesId)` validation plus a
separate fail-closed KRX capability check. Its manifest config records the
entitled superset context (`premium=active`, G1/G2/G3 on); it is not permission to show
KRX without the runtime capability check. S2 must validate `(tab, seriesId)`
and apply that fail-closed capability check; S0-d records and tests the
requirement but does not install the production runtime filter. Source metadata
separately records that the exporter passed `krx_visible=true` directly and did
not traverse the entitlement evaluator.

FCM files are explicitly the client-visible notification/data projection before
Firebase Admin SDK serialization, not a captured `MulticastMessage`. Visible
data runs through `_normalize_data_payload`. Bank payloads execute the current
authoritative `crud.process_rate_alerts` path and parity-check the shadow
backend. Source payloads use the live evaluator storage backend and execute the
dormant legacy CRUD path only as a compatibility parity check. `sync_alerts`
executes `notify_user_devices_sync` with a recorder and omits the notification
field entirely.
