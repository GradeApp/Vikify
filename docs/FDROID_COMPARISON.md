# F-Droid differentiation review — 2026-11-24

**Decision: a new main-catalog request is not justified by the evidence yet.**
[Request !58350](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/58350#note_4300905331)
was closed on November 23 because the app did not provide unique features.
Account verification and a successful build cannot resolve that product objection.
The [inclusion policy](https://f-droid.org/en/docs/Inclusion_Policy/) requires
meaningful unique value and also sets expectations for maintenance history.

## Scope and evidence

This is a documentation and source audit of concrete user scenarios, not a
hands-on usability benchmark of the other clients. Their absence from a checked
file is not evidence that they lack a feature. Vikify was tested on Galaxy A03s;
XiVPN and Forkray were not installed on the user's phone.

- Vikify 1.6.1 build 24: `d1707cbe1eb7041dafd39a0f7938bf18226b52cc`.
- [XiVPN 1.11.1](https://github.com/Exclude0122/xivpn/tree/eeac17bf12c4a1fbee2980a2cd64fb4f55900c69):
  `eeac17bf12c4a1fbee2980a2cd64fb4f55900c69`.
- [Forkray 2.4.0](https://github.com/AcideFluorhydrique/v2rayNG/tree/63ed7fbc24aea307f2d317ecc529a90ce4538b6f):
  `63ed7fbc24aea307f2d317ecc529a90ce4538b6f`, also pinned in its
  [F-Droid recipe](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/io.github.acidefluorhydrique.v2rayng.yml).

## Scenario comparison

| User scenario | Vikify 1.6.1 | Comparable evidence | Conclusion |
|---|---|---|---|
| Configure and use an Xray server without writing JSON | VLESS import by clipboard, camera or image; one active subscription; friendly labels and technical details | [XiVPN](https://f-droid.org/en/packages/io.github.exclude0122.xivpn/) explicitly targets nontechnical configuration and adds groups, chains and routing rules | A simpler interface is useful but not a demonstrated unique capability |
| Find a potentially reachable endpoint | Short-lived TCP reachability states, two concurrent probes and one retry; green is not proof of an authenticated tunnel | Forkray has [TCP socket timing](https://github.com/AcideFluorhydrique/v2rayNG/blob/63ed7fbc24aea307f2d317ecc529a90ce4538b6f/V2rayNG/app/src/main/java/com/v2ray/ang/handler/SpeedtestManager.kt) | Color and reachability alone do not establish differentiation |
| Establish whether a selected proxy works | Vikify checks HTTPS 204 through explicit local SOCKS routing before reporting success | Forkray has [real outbound delay measurement](https://github.com/AcideFluorhydrique/v2rayNG/blob/63ed7fbc24aea307f2d317ecc529a90ce4538b6f/V2rayNG/app/src/main/java/com/v2ray/ang/service/RealPingWorkerService.kt), separate from TCP timing | A connectivity test is already a common capability |
| Understand why subscription or traffic access failed | Bounded imports, mixed-list preview and localized errors; no unified staged diagnosis or shareable sanitized report | XiVPN has subscription management; [Forkray](https://f-droid.org/en/packages/io.github.acidefluorhydrique.v2rayng/) documents explicit handling of direct retry and background updates | Investigate guided recovery as a hypothesis; do not claim competitors lack it |
| Install an auditable build and retain updates | Pinned source builds, verified publisher signature, own F-Droid repository | Both are already listed in F-Droid; Forkray documents source-built native code and reproducibility | Necessary release quality, not sufficient uniqueness |

Vikify implementation references: [EndpointAvailability](../app/src/main/java/vikify/vpn/core/EndpointAvailability.kt),
[ProfileReachability](../app/src/main/java/vikify/vpn/core/ProfileReachability.kt),
[ProxyHealthCheck](../app/src/main/java/vikify/vpn/core/ProxyHealthCheck.kt).
The seven-profile independent traffic matrix is a **developer test tool**;
it is not a user-facing diagnostic feature in build 24.

## Next development stage: guided, privacy-preserving recovery

This is proposed work for a new version, not functionality included in 1.6.1.
Keep build 24, its tag and APK immutable; any implementation needs a new
versionCode, upgrade tests and a new signed release with the existing key.

1. Design a short guided flow separating subscription retrieval/parsing, endpoint
   reachability, proxy handshake and real DNS/HTTPS traffic. Show what was
   observed, which network was used, and what the user can try next. Do not infer
   a precise cause such as invalid credentials from an ambiguous timeout.
2. Make checks explicit and bounded. Explain external probe destinations and
   direct-network requests; avoid silent direct retry. Cancellation, interruption
   and completion must restore the initial selected profile and VPN state.
3. Provide an on-device report preview with an explicit share action. Exclude
   subscription URLs/tokens, UUIDs, keys, raw configuration, server addresses,
   personally identifying profile names and IP addresses. No automatic upload.
4. Test offline access, invalid DNS, closed port, TLS/REALITY failure, HTTPS probe
   failure, working tunnel, expired/invalid subscription, mixed imports,
   cancellation and process death. A green endpoint must never masquerade as
   proof of traffic. Test redaction with planted secrets and malformed input.

## Reconsideration gate

- Demonstrate a completed end-to-end user task that the compared stable clients
  cannot achieve equivalently, or a substantial validated improvement. Record
  versions, identical inputs, steps, screenshots and reproducible outcomes.
- Obtain real usability feedback; do not invent user counts or development age.
- Publish implementation, privacy changes, tests and a short credential-free demo.
- Repeat source/scanner/reproducibility/signature tests and data-preserving
  upgrade tests on Galaxy A03s. Obtain hosted F-Droid CI results, or clearly
  identify pending maintainer-assisted checks.
- Then ask maintainers to reconsider !58350 with concrete changes and links;
  do not submit the unchanged build as a new request. Acceptance remains the
  maintainers' decision.

As of this review, the differentiation gate is **not met**. No replacement
request or appeal has been sent. The publisher's independent repository remains
the supported F-Droid distribution channel.
