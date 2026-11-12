# PhoneBridge Protocol — v1

> **This document is the single source of truth.** Android and macOS implementations MUST follow it.
> Every example block tagged `fixture=` or `frame=` is extracted into `protocol/fixtures/` by
> `protocol/tools/extract_fixtures.py`; both platforms' test suites decode and re-encode those
> fixtures to prove conformance. Change the protocol here first, regenerate fixtures, then update code.

Status: **v1.0 (draft, pre-release)** · Protocol major version: `1`

---

## 1. Conventions

| Item | Rule |
|---|---|
| Keywords | MUST / SHOULD / MAY as in RFC 2119 |
| Text encoding | UTF-8 JSON, one message per WebSocket **text** frame |
| Field names | `camelCase`. Unknown fields MUST be ignored. Missing required fields → `invalid_payload` |
| Binary values in JSON | **base64url, no padding** (RFC 4648 §5) — nonces, tokens, signatures, icons |
| Hashes | SHA-256, **lowercase hex** (64 chars) |
| Key fingerprint | SHA-256 over the DER-encoded **SubjectPublicKeyInfo**, lowercase hex |
| IDs | UUID v4, lowercase, hyphenated |
| Timestamps | Unix epoch **milliseconds** (integer) |
| Integers in binary frames | **Big-endian** |

---

## 2. Roles, discovery, transport

- **Mac = server**, **Android phone = client**. The Mac listens; the phone discovers, connects and reconnects.
- **Discovery:** DNS-SD service type `_phonebridge._tcp` on the local link.
  TXT record keys: `v=1`, `id=<macDeviceId>`, `name=<url-encoded display name>`.
  The phone SHOULD first try its cached `host:port` for the paired Mac, then fall back to discovery and
  match on `id`.
- **Transport:** WebSocket (RFC 6455) over **TLS 1.3**, path `/v1`.
  - Mac presents a self-signed P-256 certificate. The phone verifies the certificate's SPKI fingerprint
    against the pinned value (from the pairing QR code). CA validation and hostname checks are NOT used.
  - Phone presents a self-signed P-256 **client certificate** whose private key lives in the Android
    Keystore (non-exportable). The Mac verifies its SPKI fingerprint against its trusted-device list.
  - Exception: while a pairing token is active (§5), the Mac MAY accept an unknown client certificate;
    such a connection is in **pairing mode** and only `pair.*` and `error` messages are allowed on it.
- Limits: text frame ≤ **2 MiB**; binary frame ≤ **36 + 262 144** bytes. Larger frames → close `4008`.

---

## 3. Envelope

Every text frame is one envelope:

| Field | Type | Req | Meaning |
|---|---|---|---|
| `v` | int | ✓ | Protocol major version (`1`) |
| `type` | string | ✓ | Message type, e.g. `clipboard.set` |
| `id` | uuid | ✓ | Unique message id |
| `seq` | int | ✓ | Per-connection, per-direction counter. First message sent on a connection is `1`, then +1 |
| `re` | uuid | – | Id of the message this one answers |
| `ts` | int | ✓ | Sender clock, ms. **Informational only** (never used for security decisions) |
| `payload` | object | ✓ | Type-specific; `{}` if empty |

**Sequence rule (replay / injection protection):** a receiver MUST track the last `seq` received on the
connection and require `seq == last + 1`. Any other value → `error{code:"sequence_violation", fatal:true}`
and close `4004`. Binary frames are not sequenced (ordering is guaranteed by TCP + TLS).

```json fixture=envelope/minimal.json
{"v":1,"type":"ping","id":"3f8e1c2a-0b4d-4e6f-8a9b-1c2d3e4f5a6b","seq":7,"ts":1762934400000,"payload":{}}
```

```json fixture=envelope/reply.json
{"v":1,"type":"pong","id":"9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d","seq":7,"re":"3f8e1c2a-0b4d-4e6f-8a9b-1c2d3e4f5a6b","ts":1762934400012,"payload":{}}
```

```json fixture=envelope/unknown_fields.json
{"v":1,"type":"ping","id":"3f8e1c2a-0b4d-4e6f-8a9b-1c2d3e4f5a6b","seq":1,"ts":1762934400000,"payload":{},"futureField":true}
```

Invalid examples (receivers MUST reject with the stated code):

```json fixture=invalid/missing_type.json
{"v":1,"id":"3f8e1c2a-0b4d-4e6f-8a9b-1c2d3e4f5a6b","seq":1,"ts":1762934400000,"payload":{}}
```
→ `invalid_payload`

```json fixture=invalid/wrong_version.json
{"v":2,"type":"ping","id":"3f8e1c2a-0b4d-4e6f-8a9b-1c2d3e4f5a6b","seq":1,"ts":1762934400000,"payload":{}}
```
→ `version_mismatch`

```json fixture=invalid/bad_id.json
{"v":1,"type":"ping","id":"not-a-uuid","seq":1,"ts":1762934400000,"payload":{}}
```
→ `invalid_payload`

---

## 4. Session handshake

After TLS succeeds with a **trusted** client certificate:

```
Mac                                   Phone
 │──── hello {mode:"session", nonce} ──▶│
 │◀─── auth {sig over both nonces} ─────│
 │──── ready {sessionId, enabled} ─────▶│   (or error + close)
```

### 4.1 `hello` (Mac → phone, always the first message, `seq` 1)

```json fixture=session/hello.json
{"v":1,"type":"hello","id":"11111111-1111-4111-8111-111111111111","seq":1,"ts":1762934400000,"payload":{"mode":"session","protocolVersion":1,"minProtocolVersion":1,"deviceId":"6f1c2a9e-3b7d-4e5f-9a10-2b3c4d5e6f70","deviceName":"My MacBook Air","platform":"macos","appVersion":"1.0.0","nonce":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","capabilities":[{"id":"status","v":1,"state":"available"},{"id":"findphone","v":1,"state":"available"},{"id":"clipboard","v":1,"state":"available"},{"id":"files","v":1,"state":"available"},{"id":"notifications","v":1,"state":"permission_missing"}]}}
```

- `mode`: `"session"` (trusted client) or `"pairing"` (unknown client, pairing window open).
- `nonce`: 32 fresh random bytes per connection.
- `capabilities[].state`: `available` | `permission_missing` | `disabled` (turned off by user).

### 4.2 `auth` (phone → Mac)

```json fixture=session/auth.json
{"v":1,"type":"auth","id":"22222222-2222-4222-8222-222222222222","seq":1,"re":"11111111-1111-4111-8111-111111111111","ts":1762934400050,"payload":{"deviceId":"a1b2c3d4-e5f6-4789-8abc-def012345678","protocolVersion":1,"appVersion":"1.0.0","clientNonce":"ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8","sig":"yMnKy8zNzs_Q0dLT1NXW19jZ2tvc3d7f4OHi4-Tl5ufo6err7O3u7_Dx8vP09fb3-Pn6-_z9_v8AAQIDBAUGBwgJCgsMDQ4P","capabilities":[{"id":"status","v":1,"state":"available"},{"id":"findphone","v":1,"state":"available"},{"id":"clipboard","v":1,"state":"available"},{"id":"files","v":1,"state":"available"},{"id":"notifications","v":1,"state":"permission_missing"}]}}
```

**Signature** — ECDSA P-256 with SHA-256, DER-encoded, by the phone's device key (the same key as its TLS
client certificate). Signed bytes, concatenated:

```
UTF8("pb-auth-v1") ‖ 0x00 ‖ macNonce(32) ‖ clientNonce(32) ‖ UTF8(macDeviceId) ‖ 0x00 ‖ UTF8(phoneDeviceId)
```

Mac MUST verify, in order — failing any → `error{code:"auth_failed", fatal:true}` + close `4001`:
1. `protocolVersion` within `[minProtocolVersion, protocolVersion]` of the Mac, else `version_mismatch` / `4002`.
2. `deviceId` exists in the trusted list, **and** its stored fingerprint equals the TLS client cert fingerprint.
   A revoked/unknown device → `revoked` / `4003`.
3. `clientNonce` is 32 bytes; signature verifies with the TLS client cert's public key.

Because `macNonce` is fresh per connection, a recorded `auth` can never be replayed.

### 4.3 `ready` (Mac → phone)

```json fixture=session/ready.json
{"v":1,"type":"ready","id":"33333333-3333-4333-8333-333333333333","seq":2,"re":"22222222-2222-4222-8222-222222222222","ts":1762934400080,"payload":{"sessionId":"44444444-4444-4444-8444-444444444444","enabled":["status","findphone","clipboard","files"],"heartbeatSec":15}}
```

`enabled` = features where both sides are `available`. Only enabled features may exchange messages; others
receive `error{code:"feature_disabled"}` (non-fatal).

### 4.4 Heartbeat

- Phone sends `ping` every `heartbeatSec`; Mac replies `pong` with `re`.
- Either side treats **3 × heartbeatSec** with no inbound frame as a dead connection and closes it.
- While the phone is in Doze, missed heartbeats are expected; the Mac shows "Phone asleep" (not an error)
  for up to 15 min after the last frame before showing "Disconnected".

### 4.5 `capabilities.changed` (either direction)

Sent when a capability state changes mid-session (e.g. user grants notification access). The Mac answers
with a fresh `ready`-shaped `capabilities.enabled` message.

```json fixture=session/capabilities_changed.json
{"v":1,"type":"capabilities.changed","id":"55555555-5555-4555-8555-555555555555","seq":9,"ts":1762934460000,"payload":{"capabilities":[{"id":"notifications","v":1,"state":"available"}]}}
```

```json fixture=session/capabilities_enabled.json
{"v":1,"type":"capabilities.enabled","id":"66666666-6666-4666-8666-666666666666","seq":12,"re":"55555555-5555-4555-8555-555555555555","ts":1762934460010,"payload":{"enabled":["status","findphone","clipboard","files","notifications"]}}
```

### 4.6 `error`

```json fixture=session/error.json
{"v":1,"type":"error","id":"77777777-7777-4777-8777-777777777777","seq":3,"re":"22222222-2222-4222-8222-222222222222","ts":1762934400090,"payload":{"code":"auth_failed","message":"signature verification failed","fatal":true}}
```

If `fatal` is true, the sender closes the WebSocket with the matching close code right after sending.

---

## 5. Pairing

### 5.1 QR code (shown on the Mac)

```
phonebridge://pair?v=1&h=192.168.1.20&p=47321&fp=<macSpkiSha256Hex>&t=<token>&id=<macDeviceId>&n=<urlEncodedName>
```

- `t` = **32 random bytes**, base64url. The Mac stores only `SHA-256(token)`.
- Token lifetime **120 s**, **single use**. Only one token is active at a time; showing a new QR code
  invalidates the previous token.
- After **5 failed** `pair.request` attempts the active token is invalidated and pairing is blocked for
  60 s (`rate_limited`).

### 5.2 Flow

```
Phone (scans QR, creates new Keystore key + self-signed cert)
  └─ TLS connect; verify Mac SPKI fingerprint == fp from QR (abort otherwise)
Mac   ── hello {mode:"pairing", nonce} ──────────────────▶
Phone ◀─ … ── pair.request {token, deviceId, …} ─────────
Mac: constant-time compare SHA-256(token); check expiry; MARK TOKEN USED (before anything else)
Mac   ── pair.confirm_code {code} ───────────────────────▶   both screens show the same 6 digits
Mac UI: "Allow ‘Pixel 9’ to connect?  Code 482 913"  [Deny] [Allow]   (60 s timeout)
Mac   ── pair.ok {…}  or  pair.denied {reason} ──────────▶
Mac closes with 4000 ("paired"); phone reconnects in session mode (§4).
```

### 5.3 Confirmation code (SAS)

Both sides compute independently (the phone MUST check that its code equals the one received; on mismatch
it aborts pairing and deletes its new key):

```
h    = SHA-256( UTF8("pb-sas-v1") ‖ macSpkiFp(32 raw bytes) ‖ phoneSpkiFp(32 raw bytes) ‖ macNonce(32) )
code = (big-endian uint32 of h[0..3]) mod 1_000_000, zero-padded to 6 digits
```

### 5.4 Messages

```json fixture=pairing/hello_pairing.json
{"v":1,"type":"hello","id":"88888888-8888-4888-8888-888888888888","seq":1,"ts":1762934400000,"payload":{"mode":"pairing","protocolVersion":1,"minProtocolVersion":1,"deviceId":"6f1c2a9e-3b7d-4e5f-9a10-2b3c4d5e6f70","deviceName":"My MacBook Air","platform":"macos","appVersion":"1.0.0","nonce":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","capabilities":[]}}
```

```json fixture=pairing/pair_request.json
{"v":1,"type":"pair.request","id":"99999999-9999-4999-8999-999999999999","seq":1,"re":"88888888-8888-4888-8888-888888888888","ts":1762934400100,"payload":{"token":"QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8","deviceId":"a1b2c3d4-e5f6-4789-8abc-def012345678","deviceName":"Pixel 9","manufacturer":"Google","model":"Pixel 9","platform":"android","osVersion":"16","sdkInt":36,"appVersion":"1.0.0"}}
```

```json fixture=pairing/pair_confirm_code.json
{"v":1,"type":"pair.confirm_code","id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","seq":2,"re":"99999999-9999-4999-8999-999999999999","ts":1762934400150,"payload":{"code":"482913","timeoutSec":60}}
```

```json fixture=pairing/pair_ok.json
{"v":1,"type":"pair.ok","id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","seq":3,"re":"99999999-9999-4999-8999-999999999999","ts":1762934405000,"payload":{"macDeviceId":"6f1c2a9e-3b7d-4e5f-9a10-2b3c4d5e6f70","macName":"My MacBook Air","port":47321}}
```

```json fixture=pairing/pair_denied.json
{"v":1,"type":"pair.denied","id":"cccccccc-cccc-4ccc-8ccc-cccccccccccc","seq":3,"re":"99999999-9999-4999-8999-999999999999","ts":1762934460150,"payload":{"reason":"timeout"}}
```
`reason`: `user_denied` | `timeout` | `token_invalid` | `token_expired` | `rate_limited` | `device_exists`.

### 5.5 Revocation / unpairing

- **Mac removes a device:** delete its fingerprint from the Keychain and its record from the DB; if connected,
  send `pair.revoked` then close `4003`. Future TLS handshakes from that key are rejected.
- **Phone unpairs:** if connected, send `pair.unpair` (Mac deletes the record and closes `4003`); then delete
  the Keystore key and stored Mac pin regardless of connectivity.
- Phone receiving close `4003` or a TLS rejection after previously succeeding → show "This Mac removed
  your phone — pair again", stop reconnecting.

```json fixture=pairing/pair_revoked.json
{"v":1,"type":"pair.revoked","id":"dddddddd-dddd-4ddd-8ddd-dddddddddddd","seq":40,"ts":1762935000000,"payload":{"reason":"removed_by_user"}}
```

```json fixture=pairing/pair_unpair.json
{"v":1,"type":"pair.unpair","id":"eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee","seq":41,"ts":1762935000000,"payload":{}}
```

---

## 6. Feature: `status`

Phone sends `status.device` once after `ready`, and `status.battery` after `ready` and whenever the level
changes by ≥ 1 % or charging state changes.

```json fixture=status/device.json
{"v":1,"type":"status.device","id":"0a000000-0000-4000-8000-000000000001","seq":2,"ts":1762934400200,"payload":{"deviceName":"Pixel 9","manufacturer":"Google","model":"Pixel 9","osVersion":"16","sdkInt":36}}
```

```json fixture=status/battery.json
{"v":1,"type":"status.battery","id":"0a000000-0000-4000-8000-000000000002","seq":3,"ts":1762934400210,"payload":{"level":82,"charging":true,"plugged":"usb"}}
```
`plugged`: `ac` | `usb` | `wireless` | `dock` | `none`. `level`: 0–100.

## 7. Feature: `findphone`

```json fixture=status/ring.json
{"v":1,"type":"phone.ring","id":"0b000000-0000-4000-8000-000000000001","seq":20,"ts":1762934500000,"payload":{"durationSec":30}}
```
`durationSec` ∈ {15, 30, 60, 120}; other values → `invalid_payload`.

```json fixture=status/ring_stop.json
{"v":1,"type":"phone.ring.stop","id":"0b000000-0000-4000-8000-000000000002","seq":21,"ts":1762934505000,"payload":{}}
```

```json fixture=status/ring_state.json
{"v":1,"type":"phone.ring.state","id":"0b000000-0000-4000-8000-000000000003","seq":30,"re":"0b000000-0000-4000-8000-000000000002","ts":1762934505020,"payload":{"ringing":false,"remainingSec":0,"reason":"stopped_remotely"}}
```
`reason` (when `ringing` is false): `stopped_on_phone` | `stopped_remotely` | `timeout` | `audio_blocked`.

Phone requirements: play with `USAGE_ALARM`; persist the original alarm volume **before** raising it;
restore on stop, timeout, service destruction, and on next start if a persisted value is found.

## 8. Feature: `clipboard`

```json fixture=clipboard/set_from_mac.json
{"v":1,"type":"clipboard.set","id":"0c000000-0000-4000-8000-000000000001","seq":15,"ts":1762934600000,"payload":{"originDeviceId":"6f1c2a9e-3b7d-4e5f-9a10-2b3c4d5e6f70","hash":"aa8cab747d747afab4445c73e9816a3c0833b895c13eeb5b195762440368cdf3","mime":"text/plain","text":"Hello from Mac"}}
```

```json fixture=clipboard/set_from_phone.json
{"v":1,"type":"clipboard.set","id":"0c000000-0000-4000-8000-000000000002","seq":16,"ts":1762934601000,"payload":{"originDeviceId":"a1b2c3d4-e5f6-4789-8abc-def012345678","hash":"b1025d5049e87eccc728001b892913935adf6a82c4f8e1eb935695f5bc624a73","mime":"text/plain","text":"Hello from Pixel"}}
```

- `hash` = SHA-256 of the UTF-8 `text`. Receiver MUST verify; mismatch → `invalid_payload`.
- `mime`: v1 supports `text/plain` only. Max text **1 MiB** (UTF-8 bytes); larger content is sent as a file.
- **Loop prevention (MUST):**
  1. Each side stores `lastAppliedHash` — the hash of the last item it *received and applied*.
  2. A local clipboard change whose hash equals `lastAppliedHash` is never sent.
  3. Once a *different* local item is seen, `lastAppliedHash` is cleared — so re-copying an older text
     deliberately still syncs.
  4. The Mac also ignores the pasteboard `changeCount` produced by its own write.
- The Mac MUST NOT send items marked `org.nspasteboard.ConcealedType` or `org.nspasteboard.TransientType`.

## 9. Feature: `files`

### 9.1 Messages

| Type | Direction | Payload |
|---|---|---|
| `file.offer` | sender → receiver | `transferId, name, size, mime, sha256, chunkSize, modifiedAt?` |
| `file.accept` | receiver → sender | `transferId, offset` (0 = new; >0 = resume point) |
| `file.reject` | receiver → sender | `transferId, reason` (`user_declined` \| `insufficient_space` \| `too_large` \| `busy`) |
| `file.ack` | receiver → sender | `transferId, offset` = bytes durably written, contiguous |
| `file.nack` | receiver → sender | `transferId, offset` = expected offset (bad CRC or gap) |
| `file.pause` | either | `transferId` |
| `file.resume` | either | `transferId` → receiver answers with `file.accept{offset}` |
| `file.cancel` | either | `transferId, reason` → both delete partial data |
| `file.done` | sender → receiver | `transferId` — all bytes sent and acked |
| `file.result` | receiver → sender | `transferId, ok, savedName?, error?` (`hash_mismatch` \| `size_mismatch` \| `io_error`) |

```json fixture=files/offer.json
{"v":1,"type":"file.offer","id":"0d000000-0000-4000-8000-000000000001","seq":50,"ts":1762934700000,"payload":{"transferId":"0f0e0d0c-0b0a-4908-8706-050403020100","name":"PhoneBridge.txt","size":11,"mime":"text/plain","sha256":"2b994d804b5259cf73a3dfee5449ccd1b1bfc2b9c9fe121ffcd81d0115e15af2","chunkSize":262144,"modifiedAt":1762934000000}}
```

```json fixture=files/accept.json
{"v":1,"type":"file.accept","id":"0d000000-0000-4000-8000-000000000002","seq":60,"re":"0d000000-0000-4000-8000-000000000001","ts":1762934700020,"payload":{"transferId":"0f0e0d0c-0b0a-4908-8706-050403020100","offset":0}}
```

```json fixture=files/ack.json
{"v":1,"type":"file.ack","id":"0d000000-0000-4000-8000-000000000003","seq":61,"ts":1762934700060,"payload":{"transferId":"0f0e0d0c-0b0a-4908-8706-050403020100","offset":11}}
```

```json fixture=files/nack.json
{"v":1,"type":"file.nack","id":"0d000000-0000-4000-8000-000000000004","seq":62,"ts":1762934700061,"payload":{"transferId":"0f0e0d0c-0b0a-4908-8706-050403020100","offset":262144}}
```

```json fixture=files/done.json
{"v":1,"type":"file.done","id":"0d000000-0000-4000-8000-000000000005","seq":51,"ts":1762934700070,"payload":{"transferId":"0f0e0d0c-0b0a-4908-8706-050403020100"}}
```

```json fixture=files/result.json
{"v":1,"type":"file.result","id":"0d000000-0000-4000-8000-000000000006","seq":63,"re":"0d000000-0000-4000-8000-000000000005","ts":1762934700090,"payload":{"transferId":"0f0e0d0c-0b0a-4908-8706-050403020100","ok":true,"savedName":"PhoneBridge.txt"}}
```

```json fixture=files/cancel.json
{"v":1,"type":"file.cancel","id":"0d000000-0000-4000-8000-000000000007","seq":52,"ts":1762934700100,"payload":{"transferId":"0f0e0d0c-0b0a-4908-8706-050403020100","reason":"user_cancelled"}}
```

```json fixture=files/reject.json
{"v":1,"type":"file.reject","id":"0d000000-0000-4000-8000-000000000008","seq":64,"re":"0d000000-0000-4000-8000-000000000001","ts":1762934700020,"payload":{"transferId":"0f0e0d0c-0b0a-4908-8706-050403020100","reason":"insufficient_space"}}
```

```json fixture=files/pause.json
{"v":1,"type":"file.pause","id":"0d000000-0000-4000-8000-000000000009","seq":53,"ts":1762934700200,"payload":{"transferId":"0f0e0d0c-0b0a-4908-8706-050403020100"}}
```

```json fixture=files/resume.json
{"v":1,"type":"file.resume","id":"0d000000-0000-4000-8000-00000000000a","seq":54,"ts":1762934710200,"payload":{"transferId":"0f0e0d0c-0b0a-4908-8706-050403020100"}}
```

### 9.2 Chunk frame (WebSocket **binary** frame)

```
offset  size  field
0       2     magic        0x50 0x42 ("PB")
2       1     version      0x01
3       1     kind         0x01 = file chunk
4       16    transferId   raw UUID bytes (RFC 4122 order)
20      8     offset       uint64 BE — byte offset of this chunk within the file
28      4     length       uint32 BE — payload length (≤ chunkSize)
32      4     crc32        uint32 BE — CRC-32 (IEEE 802.3, as zlib) of the payload
36      n     payload
```

```json frame=frames/chunk_basic
{"kind":1,"transferId":"0f0e0d0c-0b0a-4908-8706-050403020100","offset":0,"payloadHex":"50686f6e65427269646765"}
```

```json frame=frames/chunk_offset
{"kind":1,"transferId":"0f0e0d0c-0b0a-4908-8706-050403020100","offset":262144,"payloadHex":"00ff10ef"}
```

### 9.3 Rules

- **Window:** sender keeps at most **8 chunks** in flight beyond the last acked offset.
- **Receiver** writes only when `frame.offset == expectedOffset` and CRC matches; otherwise discards the
  frame and sends `file.nack{offset: expectedOffset}` (at most once per 1 s per transfer).
- **Retry:** on `nack`, or no ack progress for **10 s**, the sender rewinds to the last acked offset
  (go-back-N). After **5** consecutive retries with no progress (backoff 1, 2, 4, 8, 16 s) the transfer
  becomes `failed` (retryable by the user).
- **Resume:** receiver persists `{transferId, partPath, offset, sha256, size}`; after reconnect/restart the
  sender re-sends the same `file.offer` and the receiver answers `file.accept{offset}`.
  Offsets are only acked after data is flushed (`fsync` / `FileDescriptor.sync`) at least every 4 MiB and
  before `file.done` handling.
- **Completion:** receiver verifies size and SHA-256, renames `<name>.pbpart` to the final name, resolving
  duplicates as `name (1).ext`, `name (2).ext`, … then sends `file.result`. On mismatch it deletes the part
  file and reports `ok:false`.
- Auto-accept is the default between paired devices; the user MAY enable "Ask before receiving".

## 10. Feature: `notifications`

```json fixture=notifications/posted.json
{"v":1,"type":"notif.posted","id":"0e000000-0000-4000-8000-000000000001","seq":70,"ts":1762934800000,"payload":{"key":"0|com.whatsapp|1|null|10123","pkg":"com.whatsapp","app":"WhatsApp","title":"Ravi","text":"Lunch at 1?","postedAt":1762934799000,"redacted":false}}
```

```json fixture=notifications/removed.json
{"v":1,"type":"notif.removed","id":"0e000000-0000-4000-8000-000000000002","seq":71,"ts":1762934810000,"payload":{"key":"0|com.whatsapp|1|null|10123"}}
```

```json fixture=notifications/dismiss.json
{"v":1,"type":"notif.dismiss","id":"0e000000-0000-4000-8000-000000000003","seq":33,"ts":1762934805000,"payload":{"key":"0|com.whatsapp|1|null|10123"}}
```

```json fixture=notifications/sync.json
{"v":1,"type":"notif.sync","id":"0e000000-0000-4000-8000-000000000004","seq":4,"ts":1762934400300,"payload":{"items":[{"key":"0|com.whatsapp|1|null|10123","pkg":"com.whatsapp","app":"WhatsApp","title":"Ravi","text":"Lunch at 1?","postedAt":1762934799000,"redacted":false}]}}
```

```json fixture=notifications/icon.json
{"v":1,"type":"notif.icon","id":"0e000000-0000-4000-8000-000000000005","seq":5,"ts":1762934400310,"payload":{"pkg":"com.whatsapp","png":"iVBORw0KGgo"}}
```

- `notif.icon` is sent once per package per session, before the first `notif.posted` of that package
  (PNG ≤ 64×64, ≤ 16 KiB). Mac caches icons by `pkg`.
- `redacted: true` when Android has hidden the content (e.g. sensitive/OTP notifications, Android 15+).
- `notif.sync` is sent after `ready` (when `notifications` is enabled) and replaces the Mac's mirror state.
- Phone MUST skip: ongoing/foreground-service notifications, group summaries, its own notifications, and
  packages the user disabled.

---

## 11. Error codes and WebSocket close codes

| Code | Close | Fatal | Meaning |
|---|---|---|---|
| `paired` | 4000 | – | Pairing finished; reconnect in session mode |
| `auth_failed` | 4001 | ✓ | Bad signature / fingerprint mismatch |
| `version_mismatch` | 4002 | ✓ | No common protocol version |
| `revoked` | 4003 | ✓ | Device unknown or removed |
| `sequence_violation` | 4004 | ✓ | `seq` not `last+1` |
| `token_invalid` | 4005 | ✓ | Wrong pairing token |
| `token_expired` | 4006 | ✓ | Token used or older than 120 s |
| `rate_limited` | 4007 | ✓ | Too many attempts |
| `frame_too_large` | 4008 | ✓ | Frame exceeds limits |
| `not_allowed_in_mode` | 4009 | ✓ | Non-pairing message on a pairing connection (or vice versa) |
| `heartbeat_timeout` | 4010 | ✓ | No frames for 3 × heartbeat |
| `invalid_payload` | – | – | Malformed message (non-fatal unless during handshake) |
| `unsupported_type` | – | – | Unknown `type`; receiver ignores it |
| `feature_disabled` | – | – | Feature not in the `enabled` set |
| `permission_missing` | – | – | Feature needs a permission the user hasn't granted |
| `transfer_failed` | – | – | File transfer gave up after retries |

Any error during the handshake (before `ready`) is fatal.

## 12. Offline queue (phone)

The phone MAY queue outbound messages while disconnected. Queued messages get a fresh `id`/`seq`/`ts` at
send time. TTL per type: `clipboard.set` 5 min; `status.*`, `notif.*` are **never queued** (state is
re-sent after `ready`). File transfers are persisted separately (§9.3).

## 13. Versioning

- `v` / `protocolVersion` = **major**. Different majors with no overlap → `version_mismatch`.
- Minor evolution = adding optional fields or new message types guarded by a capability. No version bump.
- Features carry their own version (`files@1`); a feature is enabled at the highest version both support.

## 14. Test vectors

Cross-platform vectors for §4.2 (auth signing bytes), §5.1 (token hash) and §5.3 (confirmation code).
`macSpkiFp = SHA-256("mac-spki")`, `phoneSpkiFp = SHA-256("phone-spki")` (stand-in values).

```json fixture=crypto/vectors.json
{"sas":{"macSpkiFp":"7b84b55a60623a44821333388898ec365867833b4a97a2834c05dd43e71e1800","phoneSpkiFp":"817f2665082fb064a92a07b4859460be88d67cc62b285589f810c80f368b6f3b","macNonceHex":"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f","code":"546251"},"authSigningBytes":{"macNonceHex":"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f","clientNonceHex":"202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f","macDeviceId":"6f1c2a9e-3b7d-4e5f-9a10-2b3c4d5e6f70","phoneDeviceId":"a1b2c3d4-e5f6-4789-8abc-def012345678","bytesHex":"70622d617574682d763100000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f36663163326139652d336237642d346535662d396131302d3262336334643565366637300061316232633364342d653566362d343738392d386162632d646566303132333435363738","sha256":"8b9e52d9218ef89e4deb1618bb501152d9ac55ecfce6678f9306d78a7359afe9"},"tokenHash":{"tokenB64url":"QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8","sha256":"ca2a4fe727faaecf16ecd130a86e0885c5540c05375340445071c0657555fd42"}}
```

## 15. Changelog

- **1.0** — initial protocol.
