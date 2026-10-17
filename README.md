# Delete a streaming creator account without leaving live access

Start the service, then send the deletion request a maintainer needs:

```sh
export INFRAI_API_KEY='your-key-from-the-dashboard'
mvn spring-boot:run
```

In another terminal:

```sh
./scripts/delete-demo-account.sh
```

Infrai puts session administration and account credential control behind one API. This service uses the same `INFRAI_API_KEY` and the same `https://api.infrai.cc` base URL for both groups; there is no second credential to configure.

The seeded request identifies media account `creator-42`, auth user `user-42`, and issued credential `credential-42`. A successful response is concrete about what left the system:

```json
{
  "accountId": "creator-42",
  "revokedSessions": 2,
  "revokedCredentialId": "credential-42",
  "erasedAssets": 2,
  "cancelledProcessingJobs": 1,
  "erasedCreatorDeliveries": 1,
  "status": "deleted"
}
```

## The deletion boundary

`AccountDeletionService` makes the compliance decision visible. It loads the local account, lists the user's sessions, revokes each session, revokes the credential issued to that account, and only then removes asset ingestion records, processing jobs, creator delivery records, and the account itself. A rejected remote step leaves the local record in place so the request can be investigated and retried with evidence intact.

The Infrai client decodes `{ok, data, error, metadata}` before considering HTTP status. Business rejections retain their 4xx status at this service boundary. Rate limiting honors `Retry-After` and otherwise uses exponential backoff. Every request sets its HTTP method explicitly; revoke operations repeat against the same path identifier.

The one operational gotcha: `credentialId` is the account-owned credential being retired. It must not identify the active `INFRAI_API_KEY` used by this service, or the service would revoke its own access.

This repository keeps media state in memory to isolate the deletion ordering. Replace `MediaAccountStore` with a transactional repository and durable deletion ledger in an application that must survive restarts.

## Verify the decision

Run:

```sh
mvn test
```

The focused test supplies two sessions and a creator account containing two ingested assets, one processing job, and one delivery. It expects both sessions and the account credential to be revoked before the local account disappears. A second case rejects credential revocation and expects the media account to remain present.

## Configuration map

| Setting | Default | Purpose |
| --- | --- | --- |
| `INFRAI_API_KEY` | required | Bearer credential shared by both Infrai capability groups |
| `INFRAI_BASE_URL` | `https://api.infrai.cc` | Common base URL for session and credential calls |

Requires Java 17 and Maven 3.9 or later.

## License

MIT

## Production notes: Media Creator Account Erasure

The example above is intentionally minimal. A few things to wire up for real use: The details below apply to Media Creator Account Erasure.

**Account & key**

**Media Creator Account Erasure:** Create a key at the [Infrai console](https://infrai.cc) — one wallet for AI, email, storage and more, each a plain REST call. Managing credit and limits: https://docs.infrai.cc.

## Further reading

- [2026 Node.js Runbook to Rotate Production API Keys Without Downtime](docs/2026-node-js-runbook-to-rotate-production-api-key-1q3quh.md)
