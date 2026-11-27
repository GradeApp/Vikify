# F-Droid diagnostics follow-up — 2026-11-26

> Dated source-comparison snapshot. Later [1.7.0 validation](RELEASE_1.7.0_BUILD25.ru.md), [publication status](RELEASE_1.7.0_PUBLICATION.ru.md) and [submission status](../fdroid/SUBMISSION.md) are recorded separately; the comparison below remains historical evidence.

**Status: implementation evidence has improved; a differentiated user benefit
and main-catalog acceptance are not established.** This is a dated follow-up to
[the November 24 comparison](FDROID_COMPARISON.md), which remains unchanged as a
historical record. It describes the Vikify 1.7.0 implementation, version code 25,
under review. It does not claim a published release, a new F-Droid request or a
successful CI result.

The earlier record describes why request !58350 was closed and why build
quality alone did not address the maintainers’ product concern. This document
does not recheck the current GitLab discussion or replace that historical
record. Any reconsideration needs evidence of user value and maintainer
evaluation, not simply a new version number or a differently named feature.

## Scope and pinned evidence

The comparison is based on source and documentation at the same pinned
competitor revisions used in the historical audit. These are comparison
baselines, not a claim about the latest available releases:

- **XiVPN 1.11.1:** `eeac17bf12c4a1fbee2980a2cd64fb4f55900c69`.
  Reviewed its [README](https://github.com/Exclude0122/xivpn/blob/eeac17bf12c4a1fbee2980a2cd64fb4f55900c69/README.md)
  and [SubscriptionWork.java](https://github.com/Exclude0122/xivpn/blob/eeac17bf12c4a1fbee2980a2cd64fb4f55900c69/app/src/main/java/cn/gov/xivpn2/service/SubscriptionWork.java).
- **Forkray 2.4.0:** `63ed7fbc24aea307f2d317ecc529a90ce4538b6f`.
  Reviewed its [README](https://github.com/AcideFluorhydrique/v2rayNG/blob/63ed7fbc24aea307f2d317ecc529a90ce4538b6f/README.md),
  [SpeedtestManager.kt](https://github.com/AcideFluorhydrique/v2rayNG/blob/63ed7fbc24aea307f2d317ecc529a90ce4538b6f/V2rayNG/app/src/main/java/com/v2ray/ang/handler/SpeedtestManager.kt)
  and [SubscriptionUpdater.kt](https://github.com/AcideFluorhydrique/v2rayNG/blob/63ed7fbc24aea307f2d317ecc529a90ce4538b6f/V2rayNG/app/src/main/java/com/v2ray/ang/handler/SubscriptionUpdater.kt).

The five local snapshots in
`release-artifacts/stable-1.6.1-20261124/comparison-sources/` were read and their
SHA-256 values checked against that directory’s `sources.json`. All five
matched. This verifies the local evidence set; it is not a security, build or
runtime test of either competitor. The directory is a local audit artifact;
the pinned public source links above allow reviewers to inspect the same code.

Neither XiVPN nor Forkray was installed or run for this follow-up. No completion
time, user preference, error rate, traffic behavior or report-sharing outcome
was measured for those apps. Absence of a feature from the inspected files does
not prove that the app lacks it elsewhere. No current-version superiority or
unique-feature claim is made.

## What changed in Vikify

Build 24 had reachability indicators, import errors and proxy health checks, but
no unified user-facing diagnosis or shareable typed report. Version 1.7.0 adds a
**Diagnostics** screen that links observed results to explanations and next
steps. The implementation is documented in [Diagnostics](DIAGNOSTICS.md).

- Six stages separate saved-format validation, subscription retrieval/parsing,
  DNS, TCP, proxy HTTPS and the untested Android TUN path. The screen distinguishes
  passed, failed, partial, not-tested and stopped work. A completed sequence is
  not presented as proof that all VPN traffic works.
- Direct diagnostic requests require an initially unchecked choice. A matching
  connected session can be checked through its local SOCKS proxy; a failed
  proxy request does not retry directly. The guard stops when Android exposes
  an unconfirmed VPN owner or route. Its conservative API 26–29 behavior can
  also block diagnosis with Vikify connected; Android’s visibility limits
  remain a limitation, not a universal detection guarantee.
- Diagnostics parses a downloaded subscription without saving it. It does not
  replace access, choose another profile, swap sessions or start/stop VPN.
  The separate connection button invokes the normal connection flow.
- TXT and JSON reports use a fixed enum/numeric/boolean allowlist, rather than
  attempting to scrub arbitrary logs. They omit access secrets, profile names,
  endpoints, device identifiers, raw responses and exception messages. The user
  previews the exact serialization and explicitly chooses a sharing recipient.
  There is no automatic upload.
- Stops and unexpected integration errors are represented separately with
  `CANCELLED` or `INTERNAL_ERROR`. Reports retained after interruption are
  incomplete. Exported files use a scoped private-cache FileProvider; cleanup
  occurs at the next startup/share for files at least one hour old, with at
  most eight cached files.

These are source-level implementation statements, not claims that a release
candidate has passed every device or distribution check. The final commit,
artifact hashes, phone results, signature and upgrade evidence belong in the
separate release audit.

## Comparison of concrete tasks

| Task | Vikify 1.7.0 implementation | Evidence from the pinned clients | Supported conclusion |
|---|---|---|---|
| Use an Xray client without editing JSON | VLESS access with one active subscription/local set and a guided diagnostic screen | XiVPN’s README describes nontechnical configuration, proxy groups, chains and routing rules | A simpler scope or friendly interface alone is not unique; XiVPN serves broader configuration tasks |
| Determine whether a server port answers | A consented TCP stage explains that port reachability does not establish authentication or tunnel traffic | Forkray’s `socketConnectTime` measures a TCP connection and returns a duration or failure | TCP reachability is shared functionality, not a new capability |
| Obtain information through a proxy | HTTPS 204 probes through the matching local SOCKS session; other-app TUN traffic stays `NOT_TESTED` | Forkray’s `getRemoteIPInfo` uses a configured HTTP proxy to retrieve remote IP/country information | Proxy-based network observations already exist; the narrower Vikify result and its limits are the relevant UX to evaluate |
| Understand a failed subscription request | Staged observations, localized explanations, bounded codes and next steps; the saved snapshot remains unchanged | XiVPN’s inspected worker downloads/parses subscriptions, issues error notifications and opens a cached detail file in `CrashLogActivity` | Competitors already explain failures; “has diagnostics” is not sufficient differentiation |
| Prepare details for support without sharing the access configuration | Preview and explicit export of typed TXT/JSON without arbitrary response text or identifiers | XiVPN’s inspected error path writes the subscription label, exception message and, when available, HTTP status and response body to a local cache file | The inspected error-file formats differ materially. This does not prove what a competitor’s final sharing UI sends or establish an app-wide privacy ranking |
| Diagnose without updating subscription data | The diagnostic runner inspects a snapshot and a temporary response; it does not call repository refresh/select or VPN connect/disconnect | XiVPN’s inspected subscription worker is an update path that writes proxy records. Forkray’s updater schedules work and sends update requests to its subscription service | These source paths serve different jobs. A runtime search for equivalent read-only diagnostic workflows is still required |
| Control subscription maintenance | Existing refresh remains separate from the diagnostic screen | Forkray’s `SubscriptionUpdater` supports per-subscription scheduled work, cancellation and rescheduling | Scheduling and basic subscription management are established features, not differentiation claims |

The most concrete hypothesis is the combined task: **understand a failed
subscription or connection, distinguish what was actually tested, and prepare
a useful report without copying access credentials or changing the session**.
The source shows an implementation of that task in Vikify and a narrower
comparison with specific competitor code paths. It does not yet show that
users complete it more successfully, or that other clients cannot achieve it
equivalently through another path.

## Remaining evidence before reconsideration

| Gate | Required evidence | Status in this follow-up |
|---|---|---|
| Identical-scenario runtime comparison | Same device/network and controlled subscription fixtures; pinned APK versions; steps, outputs and screenshots for all three apps | Pending; no competitor runtime tests performed here |
| Usability | Real feedback on understanding failures, selecting the next action and sharing a useful report; record limitations and participant evidence honestly | Pending; no user study or measured advantage claimed |
| Final-source audit and scanners | Checks against the final immutable 1.7.0 source/release recipe; source and binary scan outputs with versions and hashes | The [release audit](RELEASE_1.7.0_BUILD25.ru.md) identifies the final APK check and the separate source-ZIP scanner protocol; a submitted catalogue recipe and hosted CI remain separate gates |
| Reproducibility and update compatibility | Repeat builds from the final source, reproducibility comparison, publisher signature and data-preserving Galaxy A03s update evidence | Local clean APK/AAB reproducibility, signature-copy verification and Galaxy update checks passed; hashes and scope are recorded in the [release audit](RELEASE_1.7.0_BUILD25.ru.md) |
| Hosted F-Droid CI | Actual pipeline link and job results for the final submitted recipe, or explicit maintainer-assisted pending checks | Pending; local success cannot be labelled hosted CI success |
| New or reopened request | A concise, evidence-backed request that addresses the prior concern after the product and technical gates are assessed | Pending; this documentation does not submit or claim a new request |
| Acceptance | F-Droid maintainer assessment of the implementation, user value, maintenance and applicable inclusion requirements | Not established; the decision belongs to maintainers |

The scenario set should include expired/denied and malformed subscriptions,
partially valid lists, offline/DNS/port/TLS failures, failed HTTPS probes,
working proxy access, an unconfirmed VPN route, cancellation and session
changes. Seed only controlled test credentials. Compare the user-visible
explanation and exported fields, not just whether a technical test exists.
Record the initial/final saved access, selected profile and VPN state. Measure
whether users can identify the next step without mistaking TCP or SOCKS success
for other-app tunnel verification.

Actual Vikify device and report-privacy results are recorded in the
[release audit](RELEASE_1.7.0_BUILD25.ru.md).
Even successful results do not replace the identical-scenario comparison or
usability gate. The independent F-Droid repository remains a separate
distribution channel; its availability is not evidence of main-catalog
acceptance.
