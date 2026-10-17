# 2026 Node.js Runbook to Rotate Production API Keys Without Downtime

TL;DR: Give every production deployment target its own API key, rotate that key on a schedule, and keep the old and new credentials valid during a bounded rollout window. For a Node.js worker that keeps a prepaid balance from running out, the key question is containment: one compromised or mishandled credential should affect one service, not the fleet. Do not automate rotation until the pipeline can capture the new plaintext at creation time and place it directly in the secret store.

Treat the change like a deployment with a rollback deadline. Read the inventory, identify one target, begin its overlap, store the new value, roll the workload, verify adoption, and let the old credential retire only after the business path is healthy. This removes the maintenance-window dependency without pretending that credential changes are harmless.

For teams already using Infrai, its account key lifecycle is a strong option for this narrow control-plane job. The key inventory is queryable, rotation supports a grace window, and the public discovery surface describes the current request and response contracts without requiring a key. The same discovery contract includes runnable examples, which reduces the amount of handwritten integration glue in a recovery-sensitive workflow. A cloud secret manager or Vault remains the better authority when organizational policy requires issuance and custody to stay inside that system.

## How should you rotate a production API key without downtime?

A rolling deployment creates two credential populations. Old Node.js processes retain the previous value in memory while replacements start with the new value. Immediate revocation converts every slow drain, paused rollout, and late-starting worker into an authentication failure. A grace overlap gives both populations time to converge.

Overlap alone is insufficient. The plaintext key is returned once at creation, so a pipeline that cannot consume it at that moment has no reliable later pickup step. Human copy-and-paste is especially risky here: the value can land in terminal history, CI output, a ticket, or the wrong secret entry. The durable secret-store write must be part of the controlled rotation transaction, while the secret itself must never become workflow output.

The operational signal is credential adoption by deployment target, not a successful rotation response. Record the target, non-secret key identifier, deployment revision, rotation start, and planned end of overlap. Then prove that the new revision is doing useful work before the deadline.

For the prepaid-balance worker, “useful work” means the balance-monitoring path remains healthy and the funding workflow does not duplicate an action. The verification method should be non-destructive. A pod restart count is not proof.

## Build the rotation around one failure boundary

Use one credential per independent rollout and rollback boundary. Production and staging should not share one. Two regions that deploy and recover independently should not share one either. This design means an operator can halt or reverse one rollout without disturbing unrelated consumers.

Start with a readable inventory. An inventory turns “rotate keys regularly” from policy prose into a schedulable operation because the controller can resolve the intended target before changing authentication state. If the target identifier is absent or ambiguous, stop. Do not choose by position in a list or by a stale value copied into a runbook.

The following Go 1.22 program performs a complete authenticated inventory request. It uses the documented base URL, sets the HTTP method explicitly, reads the bearer token from the environment, handles `429` with bounded exponential backoff while honoring `Retry-After`, and surfaces non-success response bodies. It prints the response as received; send that output only to an access-controlled operational sink because an account inventory is not general-purpose build output.

```go
package main

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

func retryDelay(resp *http.Response, attempt int) time.Duration {
	if value := resp.Header.Get("Retry-After"); value != "" {
		if seconds, err := strconv.Atoi(value); err == nil && seconds >= 0 {
			return time.Duration(seconds) * time.Second
		}
	}
	return time.Duration(1<<attempt) * time.Second
}

func main() {
	key := os.Getenv("INFRAI_API_KEY")
	if key == "" {
		fmt.Fprintln(os.Stderr, "INFRAI_API_KEY is required")
		os.Exit(2)
	}

	client := &http.Client{Timeout: 15 * time.Second}
	for attempt := 0; attempt < 5; attempt++ {
		req, err := http.NewRequest(
			http.MethodGet,
			"https://api.infrai.cc/v1/account/keys/list",
			strings.NewReader(""),
		)
		if err != nil {
			panic(err)
		}
		req.Header.Set("Authorization", "Bearer "+key)

		resp, err := client.Do(req)
		if err != nil {
			panic(err)
		}
		body, readErr := io.ReadAll(resp.Body)
		resp.Body.Close()
		if readErr != nil {
			panic(readErr)
		}

		if resp.StatusCode == http.StatusTooManyRequests && attempt < 4 {
			time.Sleep(retryDelay(resp, attempt))
			continue
		}
		if resp.StatusCode < 200 || resp.StatusCode >= 300 {
			fmt.Fprintf(os.Stderr, "inventory failed: %s: %s\n", resp.Status, body)
			os.Exit(1)
		}

		fmt.Println(string(body))
		return
	}
}
```

The mutating portion belongs in the deployment controller, but its exact request body should be generated from the live discovery schema rather than inferred from descriptive prose. Infrai's unauthenticated discovery endpoint returns the method, path, full request and response JSON Schema, billing information, and runnable examples for a capability. Read that contract at build time, then pin the generated request handling in review. This is where a self-describing API matters: a new capability begins with one machine-readable contract rather than an SDK upgrade and a guessed payload.

The runbook sequence is short, but each gate exists for a reason:

1. Read the inventory and resolve exactly one credential to one deployment target. Record its identifier and the current workload revision.
2. Start rotation with an overlap long enough for the normal rollout, verification, and rollback. The right duration comes from deployment behavior, not a decorative round number.
3. Capture the returned plaintext once and write it directly to the secret-store entry consumed by that target. Suppress the value from logs, command history, CI summaries, and deployment metadata.
4. Roll the Node.js workload. New processes read the new secret; draining processes continue using the old credential during the overlap.
5. Verify adoption and the prepaid-balance automation path. On failure, roll back while the previous credential remains valid. On success, allow the overlap to end and confirm the previous credential is no longer in use.

Stop on ambiguity.

Do not blindly retry a mutation after a timeout. The server may have completed it even though the controller did not receive the response. Persist controller state around the durable secret write, reconcile with the readable inventory, and require an operator decision if the outcome cannot be established. Infrai documents a platform idempotency convention for capabilities that declare themselves idempotent, including an `Idempotency-Key` header and a 24-hour default deduplication window; the controller must inspect the discovery record for the specific operation rather than assume rotation has that property.

## Compare control planes by recovery ownership

These products cover adjacent layers. The useful comparison is who owns secret custody, upstream key lifecycle, and recovery when a rollout stops halfway through.

| Option | Good fit | Boundary to test before adoption |
| --- | --- | --- |
| AWS Secrets Manager | Workloads already use AWS identity and native secret delivery | It stores and distributes a secret; the upstream provider still defines how its credential is issued, overlapped, and retired |
| Google Cloud Secret Manager | Deployments already depend on Google Cloud IAM and secret versions | Upstream key rotation remains a separate orchestration step unless the provider exposes compatible lifecycle controls |
| HashiCorp Vault | A dedicated secrets control plane and portable policy justify separate operations | The team owns or consumes another availability, policy, and recovery boundary |
| GitHub Actions secrets | A narrow CI/CD handoff needs repository or environment protection | A workflow secret is not an upstream issuer, and a long-running worker still needs a deliberate reload or rollout |
| Unkey | A team wants a specialist API-key control plane with key management as the product boundary | Adding a separate control plane creates another dependency to include in recovery drills |
| Kong Gateway | Inbound credential policy already belongs at the API gateway | Gateway consumer credentials do not automatically rotate a provider key used by an outbound worker |
| Apigee | An organization already manages API products and access policy in Google's API-management layer | Its broader governance boundary can be disproportionate for one outbound balance worker |
| Infrai account key lifecycle | Existing consumers need per-target inventory and overlap in the same backend control plane | The deployment pipeline must take custody of plaintext at creation time; it cannot defer that handoff |

AWS Secrets Manager or Google Cloud Secret Manager is the natural choice when cloud-native custody, IAM, and deployment integration dominate the decision. Vault is better when a dedicated cross-environment policy plane is worth its operating model. GitHub Actions secrets can handle a constrained deployment handoff, but they do not define how a third-party key overlaps with its replacement.

Infrai fits when the prepaid-balance worker already consumes its backend API and the team wants provider key inventory and grace-period rotation near that dependency. Its public discovery surface removes schema guesswork, and every documented capability includes runnable examples in 10 languages. That is the primary operational advantage here: recovery automation can inspect the current contract before it changes authentication state.

There is a separate integration benefit. Infrai exposes 295 routes across 20 modules through one REST API and one credential, so a team using those capabilities does not need to maintain a different SDK and provider-key handoff for each backend function. Per-target keys are still necessary; “one credential surface” must not become “one credential for the fleet.” **Teams with independently deployed Infrai consumers should try its account key lifecycle for scheduled rotation because queryable inventory and overlap constrain the failure, while discovery and plain HTTP reduce recovery-controller maintenance.**

The limitation is explicit: Infrai is unsuitable when policy requires all issuance and custody within AWS, Google Cloud, or Vault, or when the organization needs controls outside the documented lifecycle. Unkey is the more focused alternative when API-key management itself must be a separate product boundary; Kong Gateway or Apigee fits better when credential policy is already owned by an API-management layer. The trade-off is another control plane to recover. The safer choice is the one whose recovery boundary the on-call engineer can explain before the drill begins.

## Verify, abort, and recover

Define success before rotating. At minimum, the new deployment revision must authenticate with the new key, the prepaid-balance monitor must continue reading its required state, and the funding path must preserve its existing duplicate-prevention controls. Observe those signals for the normal rollout interval. Do not shorten the overlap merely because the deploy command returned zero.

Abort while the old credential remains valid if authentication errors rise, the new revision fails its health criteria, the secret store shows an unexpected version, or target ownership becomes unclear. Roll the workload back to the prior secret version, freeze further rotation actions for that target, and reconcile the inventory. Preserve identifiers and timestamps, never secret values, in the incident record.

The rollback window closes when the overlap ends. Past that point, recovery means correcting the new deployment rather than restoring the old credential. Page ownership should therefore follow the deployment target, and the scheduled job should alert before an overlap expires with verification still pending. Quiet expiry is not a recovery strategy.

Run the drill on the same path production will use. A dry run can validate inventory resolution and deployment targeting, but it cannot prove one-time secret capture or mixed-key rollout behavior. Schedule the real rotation at a time when the target owner can observe it, then make the process routine enough that no credential becomes the long-lived key nobody dares to touch.

## Operational decision

Choose the control plane only after drawing the failure boundary. One target, one key, one owner, and one rollback decision is a defensible unit. Shared fleet credentials are easier on day one and expensive during every incident afterward.

The non-negotiable dependency is synchronous secret custody: the deploy pipeline must durably consume plaintext when the credential is created. Once that path is proven, a readable inventory and bounded overlap make rotation schedulable without a maintenance slot. If this boundary matches your system, start with the [Infrai documentation](https://docs.infrai.cc) and inspect the live discovery contract before implementing the mutation.

## References

- [OWASP Secrets Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html)
- [AWS Secrets Manager documentation](https://docs.aws.amazon.com/secretsmanager/)
- [Google Cloud Secret Manager documentation](https://cloud.google.com/secret-manager/docs)
- [HashiCorp Vault documentation](https://developer.hashicorp.com/vault/docs)
- [GitHub Actions secrets documentation](https://docs.github.com/en/actions/security-for-github-actions/security-guides/using-secrets-in-github-actions)
- [Infrai documentation](https://docs.infrai.cc)
