# ⚡ AcidCouponBot v2 — Randgo Integration

WhatsApp coupon distribution bot integrated with the Randgo API.
Customers scan a QR code → send "Coupon" on WhatsApp → instantly receive grocery coupon codes.

---

## Architecture

```
Customer scans QR → WhatsApp → "Coupon"
         ↓
AcidCouponBot (this system)
  1. Deduplication check (own DB)
  2. Register member in Randgo
  3. PRIMARY: Randgo CouponBasketCheckout (live codes)
  4. FALLBACK: Local cached codes (if Randgo down)
  5. Send codes via WhatsApp
  6. Record redemption
         ↓
Randgo API (Account B — bot's own credentials)
         ↓
Retailer POS validates code at till
         ↓
Nightly: Randgo Issued API confirms till redemption
```

---

## Quick Start (Dev)

### Requirements
- Java 17+, Maven 3.8+

### Run
```bash
mvn spring-boot:run
```

Starts in mock mode — no credentials needed.

| URL | Description |
|-----|-------------|
| http://localhost:8080 | Admin Dashboard |
| http://localhost:8080/login.html | Login |
| http://localhost:8080/h2-console | DB Browser |
| http://localhost:8080/api/health | Health |

**Default login:** `admin` / `AcidAdmin@2024`

---

## Configuration

### application.properties
```properties
# Randgo QA credentials (get from Randgo)
randgo.api.url=https://api.randgoqa.dev
randgo.username=YOUR_USERNAME
randgo.password=YOUR_PASSWORD
randgo.client.scheme.guid=YOUR_CLIENT_SCHEME_GUID
randgo.member.identifier.guid=YOUR_MEMBER_IDENTIFIER_GUID

# Switch off mock mode when credentials are ready
randgo.mock.mode=false

# WhatsApp
whatsapp.phone.number.id=YOUR_PHONE_NUMBER_ID
whatsapp.access.token=YOUR_ACCESS_TOKEN
whatsapp.webhook.verify.token=your_verify_token
whatsapp.app.secret=your_app_secret
```

---

## Randgo Endpoints Used

| Endpoint | When | Rate Limit |
|---|---|---|
| `POST /api/Account/Login` | On startup / token expiry | **1/day max** |
| `POST /api/Voucher/VouchersGet` | Monthly (1st of month) | **10/week max** |
| `POST /api/Voucher/Coupon/Basket/Checkout` | Per customer claim (live) | No limit |
| `POST /api/Member/Import` | Per new customer | No limit |
| `POST /api/Member/Import/Batch/GetByBatchGuid` | After import | 1/20s per batch |
| `POST /api/Voucher/Issued` | Nightly 23:00-05:00 | After hours only |

**All rate limits are enforced automatically in code.**

---

## Fallback Cache Strategy

```
Normal: Customer → live Randgo API → codes returned instantly
Fallback: If Randgo API fails → local DB codes used

Local cache populated nightly at 23:30 via CodesGet.
Fallback codes marked as used + Randgo notified when API recovers.
```

---

## Sync Schedule

| Job | Time | What it does |
|---|---|---|
| VouchersGet sync | 1st of month, 02:00 | Pulls voucher metadata |
| CodesGet cache | Nightly 23:30 | Builds fallback code pool |
| Issued stats | Nightly 00:30 | Updates till redemption data |
| Retry failed sends | Every 5 min | Resends failed WhatsApp messages |

---

## Production Checklist

```
□ Set spring.profiles.active=prod
□ Configure MySQL (DB_URL, DB_USERNAME, DB_PASSWORD)
□ Set Randgo production credentials (after QA sign-off)
□ Set randgo.mock.mode=false
□ Set WhatsApp credentials
□ Deploy to HTTPS server
□ Set webhook URL in Meta Developer Console
□ Delete DataSeeder.java
□ Change admin password
```

---

## Key Design Decisions

- **Two Randgo accounts** — bot has its own credentials, independent from backend system
- **SessionToken stored in DB** — survives restarts, never calls Login more than once/day
- **VouchersGet cached monthly** — respects 10/week rate limit with room to spare
- **Live API first, cache fallback** — customers always get codes even if Randgo is down
- **Pessimistic DB lock on fallback** — prevents race conditions on concurrent claims
- **Retry job every 5 min** — no customer ever loses their coupons due to WhatsApp failure




Customer scans QR → sends "Coupon"
↓
1. ACID's bot receives the WhatsApp message
   ↓
2. Bot checks: is this phone already in OUR database?
   YES → already claimed, stop here
   NO  → continue
   ↓
3. Bot calls Member Import with:
    - ClientSchemeGuid = ACID's programme GUID (same always)
    - PrimaryKeyName = "Cell Phone"
    - UniqueUserKey = customer's phone number
      This registers the CUSTOMER as a member under ACID's programme
      ↓
4. Bot calls CouponBasketCheckout with:
    - PrimaryKeyName = "Cell Phone"
    - PrimaryKeyValue = customer's phone number
    - Coupons = ACID's VoucherGuids (same 5 always)
      Randgo looks up: "Does this Cell Phone exist under ACID's scheme?"
      Issues codes tied to that phone number
      ↓
5. Randgo deduplication:
   Same phone tries again → Randgo knows it already issued to this phone
   Our DB deduplication:
   Same phone tries again → caught before even calling Randgo