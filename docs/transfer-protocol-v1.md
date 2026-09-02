# Tavern Shelf Transfer Protocol v1

Tavern Shelf uses a short-lived HTTP URL as its QR payload. The receiving app scans the QR code, fetches the transfer manifest, downloads the original source, verifies its SHA-256 digest, and then imports it using its own parser.

The protocol transfers one source file per session. It does not expose the Shelf library API or a filesystem path.

## QR payload

The QR payload is a plain HTTP URL on the local network:

```text
http://192.168.1.20:49152/v1/transfers/<opaque-token>
```

The sender and receiver must be able to reach each other on the same LAN. Shelf advertises only RFC 1918 private IPv4 addresses and prefers physical LAN interfaces over recognized VPN and virtual adapters. The token is URL-safe, randomly generated, and valid for ten minutes unless the user stops sharing earlier.

## Read the manifest

```http
GET /v1/transfers/<opaque-token> HTTP/1.1
Host: 192.168.1.20:49152
Accept: application/json
```

Example response:

```json
{
  "protocol": "tavern-shelf-transfer",
  "version": 1,
  "kind": "character",
  "name": "Lantern Keeper",
  "filename": "lantern-keeper.png",
  "size": 482193,
  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "mediaType": "image/png",
  "sourceUrl": "http://192.168.1.20:49152/v1/transfers/<opaque-token>/source",
  "expiresAt": "2026-09-01T12:10:00+08:00"
}
```

Fields:

| Field | Meaning |
| --- | --- |
| `protocol` | Always `tavern-shelf-transfer`. |
| `version` | Integer protocol version. Reject unsupported versions. |
| `kind` | `character`, `worldbook`, or `preset`. |
| `subtype` | Optional preset subtype, such as `openai` or `instruct`. |
| `name` | Display name. Do not use as a unique identifier. |
| `filename` | Suggested source filename. Sanitize before writing. |
| `size` | Expected source byte length. |
| `sha256` | Lowercase SHA-256 digest of the source bytes. |
| `mediaType` | Source MIME type. Treat it as a hint and still parse safely. |
| `sourceUrl` | Absolute URL for the original source bytes. Its authority is selected from the addresses advertised by the session, never from an untrusted HTTP `Host` value. |
| `expiresAt` | RFC 3339 session expiry. |

## Download and import

```http
GET /v1/transfers/<opaque-token>/source HTTP/1.1
Host: 192.168.1.20:49152
```

The source response includes `Content-Type` and an attachment `Content-Disposition`. The receiver should:

1. Download to a temporary file with a size limit appropriate for its platform.
2. Verify both the byte count and SHA-256 from the manifest.
3. Parse and validate the source according to `kind`.
4. Atomically commit it into the receiver's managed storage.
5. Keep the temporary/source data when parsing or import fails, according to the receiver's recovery policy.

Do not execute HTML, JavaScript, regex, or extension content while inspecting or importing a source.

## Errors and lifetime

Both endpoints support `GET` and `HEAD`. Successful manifest and source responses use HTTP 200. An unknown, expired, or revoked token returns HTTP 404 with a JSON error. A source removed after session creation returns HTTP 410. Other methods return HTTP 405.

Responses use `Cache-Control: no-store`. The transfer endpoint allows cross-origin reads so a browser-based receiver can implement the same flow.

The receiver must treat the URL token as a secret for the lifetime of the session and must not persist it after import.

Before serving the source, Shelf reopens the managed file and verifies that its byte length and SHA-256 still match the session manifest. A changed source returns HTTP 410 rather than transferring bytes under stale metadata.
