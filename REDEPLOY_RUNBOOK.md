# Coupon-bot redeploy runbook — bring live service to repo HEAD

**Goal:** move the live `acidls-coupon-bot` container from its unversioned, hand-built jar
(dated 19 May 2026, no commit marker) to a clean build of repo **HEAD** (`64cb863`), so we
have a known baseline **before** adding the JIT `/internal/member/ensure` endpoint.

**Scope discipline**
- This runbook **only brings the build current**. It does **NOT** change the environment.
- ⚠ `/opt/acid/coupon-bot/.env` is the **PROD** profile: `api.iingxoxo.com`, prod GUIDs
  (`ClientSchemeGuid E5B4AB2E-…`, `MemberIdentifierGuid FA90EE5C-…`), `mock.mode=false`.
  Our SSO is currently tested against **QA** (`randgoqa.dev`). The QA/prod SSO-vs-coupon-bot
  alignment is a **separate launch decision** — do **not** touch `.env` or the profile here.
- Do not execute steps blindly. Read §0, then work top to bottom. Every destructive step has
  a rollback in §1.

Server: droplet `188.166.88.45`. Container: `acidls-coupon-bot`. Network: `acidls`.
Env-file: `/opt/acid/coupon-bot/.env`. App port (in-container): `8090`.

---

## 0. Pre-flight — capture the current state (read-only)

Run on the droplet. Record all output somewhere before proceeding.

```bash
# What is running, and from which image?
docker ps --filter name=acidls-coupon-bot \
  --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'

# Exact image ID/digest the live container is running (this is your rollback target)
LIVE_IMAGE_ID=$(docker inspect --format '{{.Image}}' acidls-coupon-bot)
echo "LIVE_IMAGE_ID=$LIVE_IMAGE_ID"
docker inspect --format '{{.Config.Image}} {{.Image}}' acidls-coupon-bot

# Freeze the full run configuration (network, env, ports, entrypoint) so a rollback
# reproduces it EXACTLY. Keep this file.
docker inspect acidls-coupon-bot > ~/coupon-bot-live-inspect-$(date +%F).json
echo "Saved run config to ~/coupon-bot-live-inspect-$(date +%F).json"

# Confirm the app is currently healthy on the REAL endpoint (actuator does not exist)
curl -si http://localhost:8090/api/health | head -n 1     # expect: HTTP/1.1 200
```

Note the current `Status` (should be `Up ~2 months`) and the `Ports` mapping
(`0.0.0.0:8090->8090/tcp` today — we will drop that in §3).

---

## 1. ROLLBACK SNAPSHOT FIRST — do this before touching anything

Two independent snapshots (belt and suspenders), because the live image has no version tag
and may be pruned. Do **both**.

```bash
DATE=$(date +%F)   # e.g. 2026-07-27

# (a) Tag the live IMAGE by ID — cheap, instant, exact.
docker tag "$LIVE_IMAGE_ID" acidls-coupon-bot:rollback-$DATE

# (b) Commit the RUNNING CONTAINER to a new image — captures the exact on-disk jar even if
#     the underlying image is later pruned. Preserves the image's ENTRYPOINT/env config.
docker commit acidls-coupon-bot acidls-coupon-bot:rollback-live-$DATE

# Verify both snapshots exist
docker images | grep 'acidls-coupon-bot.*rollback'
```

**How to roll back** (if §4 verification fails, or coupons break after redeploy):

```bash
DATE=<the date you used above>

docker stop acidls-coupon-bot 2>/dev/null || true
docker rm   acidls-coupon-bot 2>/dev/null || true

# Re-run from the snapshot with the SAME run args used originally. If the live container
# had the public port published, restore it too so behaviour is byte-for-byte identical
# to "before". (Cross-check ~/coupon-bot-live-inspect-$DATE.json for the exact flags.)
docker run -d \
  --name acidls-coupon-bot \
  --restart unless-stopped \
  --network acidls \
  -p 8090:8090 \
  --dns 8.8.8.8 --dns 8.8.4.4 \
  --env-file /opt/acid/coupon-bot/.env \
  acidls-coupon-bot:rollback-live-$DATE     # or :rollback-$DATE (the tagged live image)

sleep 20
curl -si http://localhost:8090/api/health | head -n 1     # expect 200
```

> Note on RandGo Login (1/day limit): a container restart does **NOT** cost a Login. The
> session token lives in the DB (`randgo_sessions`), so on boot the app re-reads the cached
> token via `RandgoSessionManager.getSessionToken()`. Rolling back is safe from the RandGo
> rate-limit standpoint.

---

## 2. Build HEAD properly — verify (with tests) THEN build the image

Do this on a machine with the checkout (droplet temp clone, or a build box). The multi-stage
Dockerfile builds with `-DskipTests` internally, so **run `mvn verify` first as the quality
gate** — do not let the image build be your only check.

```bash
# Get exactly HEAD (64cb863) into a throwaway dir
git clone <coupon-bot-remote> coupon-bot-build && cd coupon-bot-build
git checkout 64cb863
git rev-parse HEAD        # confirm: 64cb863…

# 2a. QUALITY GATE — run the full build WITH tests, under the DEV profile.
#     dev profile keeps mock.mode on for tests and NEVER touches prod RandGo/iingxoxo.
#     If you have Java 21 + Maven on the host:
SPRING_PROFILES_ACTIVE=dev mvn -B verify -DskipDependencyCheck=true --no-transfer-progress

#     …or, with no host Maven, run it in a throwaway JDK container (same as CI):
docker run --rm -v "$PWD":/app -w /app -e SPRING_PROFILES_ACTIVE=dev \
  eclipse-temurin:21-jdk-alpine \
  sh -c "apk add --no-cache maven && mvn -B verify -DskipDependencyCheck=true --no-transfer-progress"
```

**Gate:** all tests must pass. If `verify` is red, **stop** — do not build or deploy.

```bash
# 2b. Build the deployable image from the SAME source (Dockerfile:1-14). Tag it with the
#     commit so this deploy is, unlike the current live one, identifiable and revertable.
docker build -t acidls-coupon-bot:64cb863 -t acidls-coupon-bot:current .
```

If you built off-droplet, ship the image to the droplet (no registry needed):

```bash
docker save acidls-coupon-bot:64cb863 | ssh <user>@188.166.88.45 'docker load'
```

---

## 3. Redeploy — WITHOUT the public port

The gateway reaches coupon-bot over the `acidls` network **by container name**, so the
service does not need to be published on the host at all. Dropping `-p 8090:8090` removes the
internet exposure (the `0.0.0.0:8090` that is currently being scanned).

```bash
docker stop acidls-coupon-bot 2>/dev/null || true
docker rm   acidls-coupon-bot 2>/dev/null || true

docker run -d \
  --name acidls-coupon-bot \
  --restart unless-stopped \
  --network acidls \
  --dns 8.8.8.8 --dns 8.8.4.4 \
  --env-file /opt/acid/coupon-bot/.env \
  acidls-coupon-bot:64cb863
#  ↑ NO -p 8090:8090. Reachable in-cluster as http://acidls-coupon-bot:8090
```

> If you need host access temporarily for the §4 checks and don't want to `docker exec`, bind
> to loopback only — **never** `0.0.0.0`:  `-p 127.0.0.1:8090:8090`. Prefer running the §4
> curls from inside the network instead (see below), and keep the port unpublished.

---

## 4. Verify — health AND real coupon issuance (the "did we break coupons" gate)

App booting is not enough. You must confirm a coupon actually **issues** end-to-end through
the RandGo path (`ensureMemberRegistered → importMember → checkout`).

**4.1 Real health endpoint** (actuator does not exist on this service):

```bash
# From inside the acidls network (port is not published on the host):
docker run --rm --network acidls curlimages/curl:latest \
  -si http://acidls-coupon-bot:8090/api/health | head -n 1     # expect HTTP/1.1 200
```

**4.2 No unexpected RandGo Login** (protect the 1/day limit). A healthy boot should reuse the
DB token, not authenticate:

```bash
docker logs acidls-coupon-bot --since 5m 2>&1 | grep -i randgo
# EXPECT to see: "Using cached Randgo session token"
# ALARM if you see repeated: "Calling Randgo Login API..." (only OK once if token truly expired)
```

**4.3 RandGo connectivity** (authenticated admin API; creds are in `.env`):

```bash
docker run --rm --network acidls curlimages/curl:latest \
  -su "$ADMIN_USER:$ADMIN_PASS" http://acidls-coupon-bot:8090/api/randgo/status
# expect a connected/OK status, not an auth or connection error
```

**4.4 End-to-end coupon issuance (the actual gate).** Trigger a real coupon action from a
**designated test WhatsApp number** against the bot, and confirm a coupon/voucher is returned
to the user **and** a redemption is recorded:

- Send the coupon trigger to the bot's WhatsApp number from the test phone and complete the
  flow so it reaches issuance/checkout.
- Confirm the user receives a voucher/coupon in WhatsApp.
- Confirm a redemption row was written and the member registered:

```bash
docker run --rm --network acidls curlimages/curl:latest \
  -su "$ADMIN_USER:$ADMIN_PASS" http://acidls-coupon-bot:8090/api/redemptions | tail
docker logs acidls-coupon-bot --since 10m 2>&1 \
  | grep -iE "registered successfully|batch created|checkout|redemption"
```

> ⚠ This is **prod** — a successful test consumes a real voucher. Use a low-value or dedicated
> test campaign if one exists, and a known test member, to keep the cost to one voucher.

**Pass criteria (all must hold):** `/api/health` = 200; no Login storm; `/api/randgo/status`
OK; a coupon issued to the test user and a redemption recorded.
**If any fail → roll back via §1** and investigate before retrying.

---

## 5. Follow-ups — flag only, do NOT do in this runbook

- **(a) Adopt the existing pipeline.** `.github/workflows/ci.yml` already does
  build→test→publish `ghcr.io/888projects/acidls-coupon-bot`→SSH deploy, but was never the
  live deploy path. Adopting it makes deploys versioned and repeatable. **Fix its post-deploy
  health probe first:** `ci.yml:166` curls `/actuator/health`, which does not exist on this
  service and always fails the check — change it to `/api/health`.
- **(b) `.env` is the only copy of the prod contract.** `/opt/acid/coupon-bot/.env` holds the
  prod GUIDs, `api.iingxoxo.com`, and admin/RandGo credentials, and is **unversioned / not
  backed up**. If the droplet is lost, the prod contract is lost. Flag as a risk: back it up
  to the secrets store and document each key. (Rotate the admin creds if this runbook's
  examples ever expose them.)
```
