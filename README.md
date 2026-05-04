# ⚡ AcidCouponBot v2.1

WhatsApp coupon distribution bot for ACID Financial Services.
Customers scan a QR code in-store → send "Coupon" on WhatsApp → receive 5 grocery coupon codes instantly.

---

## Quick Start

```bash
# Requires Java 21+
mvn spring-boot:run
```

| URL | Description |
|---|---|
| http://localhost:8080 | Admin dashboard |
| http://localhost:8080/h2-console | Database browser (dev only) |
| http://localhost:8080/api/health | Health check (public) |
| http://localhost:8080/webhook | WhatsApp webhook endpoint |

**Default login:** `admin` / `AcidAdmin@2024`

---

## Architecture

```
Customer scans QR → WhatsApp → sends "Coupon"
          ↓
  WebhookController (HMAC verified)
          ↓
  Rate limiter (3/min per phone)
          ↓
  CouponService
    1. Deduplication check (own DB)
    2. Register member in Randgo (Member Import)
    3. PRIMARY: Randgo CouponBasketCheckout → live codes
    4. FALLBACK: local cached codes if Randgo down
    5. Send via WhatsApp
    6. Record redemption in DB
```

---

## Configuration

### Dev (local testing)
All Randgo QA credentials are set in `application.properties`.
To use mock data instead, set `randgo.mock.mode=true` in `application-dev.properties`.

### Production
Set these as environment variables — never hardcode in files:

```bash
# Database
DB_URL=jdbc:mysql://host:3306/acidcouponbot
DB_USERNAME=your_db_user
DB_PASSWORD=your_db_password

# WhatsApp
WHATSAPP_PHONE_NUMBER_ID=your_phone_number_id
WHATSAPP_ACCESS_TOKEN=your_permanent_token
WHATSAPP_APP_SECRET=your_app_secret
WHATSAPP_WEBHOOK_VERIFY_TOKEN=your_verify_token

# Randgo (update to production URL after QA sign-off)
RANDGO_API_URL=https://api.randgoprod.com
RANDGO_USERNAME=ACID
RANDGO_PASSWORD=your_prod_password

# Admin
ADMIN_USERNAME=admin
ADMIN_PASSWORD=your_strong_password

# Profile
SPRING_PROFILES_ACTIVE=prod
```

---

## Randgo Integration

| Endpoint | When called | Rate limit |
|---|---|---|
| `POST /api/Account/Login` | Startup / token expiry | **1/day** |
| `POST /api/Voucher/VouchersGet` | 1st of month (monthly) | **10/week** |
| `POST /api/Voucher/Coupon/Basket/Checkout` | Per customer claim | No limit |
| `POST /api/Member/Import` | Per new customer | No limit |
| `POST /api/Member/Import/Batch/GetByBatchGuid` | After import | **1/20s** |
| `POST /api/Voucher/CodesGet` | Nightly 23:30 | **1/10min** |
| `POST /api/Voucher/Issued` | Nightly 00:30 | **23:00–05:00 only** |

**All rate limits are enforced in code. All limits are respected automatically.**

### QA Credentials
```
URL:      https://api.randgoqa.dev
Username: ACID
ClientSchemeGuid:           1E37B9EC-AB2C-4E17-A7F9-AAEB975949F9
MemberIdentifierGuid:       56583EE8-DC7D-4C7F-83DA-19B5C52E03CF
PrimaryKeyName:             Cellphone
```

---

## Admin API

All endpoints require authentication. Use Basic Auth or form session cookie.

```
Basic Auth: Authorization: Basic base64(admin:AcidAdmin@2024)
```

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/health` | Health check (public) |
| `GET` | `/api/stats` | Dashboard stats |
| `GET/POST` | `/api/campaigns` | List / create campaigns |
| `GET/PUT/DELETE` | `/api/campaigns/{id}` | Get / update / delete |
| `PUT` | `/api/campaigns/{id}/activate` | Activate campaign |
| `GET` | `/api/vouchers` | List Randgo voucher cache |
| `PUT` | `/api/vouchers/{id}/toggle` | Toggle in/out of bundle |
| `POST` | `/api/sync/vouchers` | Trigger VouchersGet sync |
| `POST` | `/api/sync/codes` | Trigger CodesGet cache |
| `GET` | `/api/redemptions` | List all redemptions |
| `GET` | `/api/redemptions/failed` | List failed sends |
| `GET` | `/api/redemptions/phone/{phone}` | By phone number |
| `GET` | `/api/randgo/status` | Randgo connection status |
| `POST` | `/webhook` | WhatsApp inbound (public) |
| `GET` | `/webhook` | Meta webhook verification (public) |

---

## Scheduled Jobs

| Schedule | Job |
|---|---|
| 1st of month, 02:00 | VouchersGet sync |
| Nightly 23:30 | CodesGet fallback cache |
| Nightly 00:30 | Till redemption stats |
| Every 5 min | Retry failed WhatsApp sends |
| Every 10 min | Notify Randgo of fallback code usage |

---

## Production Checklist

```
Infrastructure:
  □ MySQL provisioned, credentials set as env vars
  □ Server with HTTPS (Meta requires HTTPS for webhook)
  □ Automated daily database backups
  □ UptimeRobot monitoring /api/health

WhatsApp:
  □ Meta Business Account approved
  □ Permanent System User token (not 24hr test token)
  □ Webhook URL registered in Meta Developer Console
  □ App Secret configured

Randgo:
  □ QA sign-off completed
  □ Production credentials received and configured
  □ VouchersGet sync run — vouchers visible in dashboard
  □ End-to-end test with real phone number

Security:
  □ Admin password changed from default
  □ All secrets in environment variables
  □ application-prod.properties in .gitignore
  □ DataSeeder removed or confirmed dev-profile-only

Testing:
  □ mvn test — all tests pass
  □ Real WhatsApp message tested on physical phone
  □ Duplicate claim prevention verified
  □ Fallback codes work when Randgo is mocked as down
```

---

## Profiles

| Profile | Database | Randgo | WhatsApp |
|---|---|---|---|
| `dev` | H2 file | QA API (real) | Console log |
| `test` | H2 memory | Mock | Console log |
| `prod` | MySQL | Production API | Real API |

---

## Running Tests

```bash
mvn test

# Specific class
mvn test -Dtest=CouponServiceTest
mvn test -Dtest=WebhookControllerTest
```
