# Abbas AI — Donations Backend

Node/Express service that the **Abbas AI** Android app calls to create Stripe
PaymentIntents and PayPal orders. It holds all provider secrets; the app only
ever receives client-side material (a PaymentIntent client secret, an ephemeral
key, a customer id) or a PayPal order id.

> This is a standalone service for Abbas AI. It has nothing to do with any other
> project's backend.

## Endpoints

All request/response bodies are JSON. Errors use
`{"error":{"code":"…","message":"…"}}` with an appropriate HTTP status.

| Method | Path | Request | Success response |
|--------|------|---------|------------------|
| GET  | `/health` | — | `{"status":"ok","stripe":bool,"paypal":bool}` |
| POST | `/create-payment-intent` | `{"amount":<int cents>,"currency":"usd"}` | `{"paymentIntent":"<pi_secret>","ephemeralKey":"<ek_secret>","customer":"cus_…"}` |
| POST | `/create-paypal-order` | `{"amount":<number dollars>,"currency":"USD"}` | `{"orderId":"<id>"}` |
| POST | `/capture-paypal-order` | `{"orderId":"<id>"}` | `{"id":"<id>","status":"COMPLETED"}` |
| POST | `/webhooks/stripe` | raw Stripe event | `{"received":true}` |

Notes
- The app sends the Stripe amount in **cents** (`amount * 100`, integer) and the
  PayPal amount in **dollars** (a number). The server validates and bounds both.
- `capture-paypal-order` is the step that **actually moves money** for PayPal.
  PayPal's web-checkout only gets the donor's approval; you must capture.
- Optional `Idempotency-Key` header is forwarded to Stripe on
  `create-payment-intent` to make retries safe.

## Configuration

Copy `.env.example` to `.env` and fill it in. Nothing secret is ever committed
(`.env` is git-ignored; `.env.example` holds placeholders only).

| Variable | Purpose |
|----------|---------|
| `APP_ENV` | `development` / `production` (prod fails fast if creds are missing) |
| `PORT` | listen port (default 8080) |
| `TRUST_PROXY` | `true` only behind a trusted reverse proxy (real client IP for rate limits) |
| `APP_API_KEY` | optional shared secret; when set, payment routes require `X-Api-Key` |
| `STRIPE_SECRET_KEY` | server-side Stripe key (`sk_test_…` / `sk_live_…`) |
| `STRIPE_API_VERSION` | must match the Stripe Android SDK's API version |
| `STRIPE_WEBHOOK_SECRET` | for verifying `/webhooks/stripe` signatures |
| `PAYPAL_CLIENT_ID` / `PAYPAL_CLIENT_SECRET` | PayPal REST app credentials |
| `PAYPAL_ENV` | `sandbox` / `live` |
| `PAYPAL_WEBHOOK_ID` | reserved for PayPal webhook verification |
| `MIN/MAX_AMOUNT_CENTS`, `MIN/MAX_AMOUNT_DOLLARS` | server-side amount bounds |

## Security measures

- **Secrets stay server-side.** Only read from the environment; never logged;
  never sent to the app.
- **Fail-fast in production** — the process exits if credentials are missing.
- **Strict input validation** (zod, `.strict()`): amounts are typed and bounded;
  currency whitelisted; unexpected fields rejected. Validation details are not
  echoed to clients.
- **Optional shared-secret gate** (`X-Api-Key`) compared in **constant time**.
- **Rate limiting** — 120 req/min per IP globally, 20 req/min on payment routes.
- **Hardened HTTP** — Helmet headers (CSP, HSTS, `X-Content-Type-Options`,
  `X-Frame-Options`, …), `x-powered-by` disabled, 10 kB JSON body cap.
- **No information leakage** — the error handler returns generic messages and
  never stack traces or provider internals.
- **Outbound safety** — Stripe idempotency keys, PayPal order-id regex before
  URL interpolation, short-lived cached OAuth token, provider errors logged by
  type/code only.
- **Webhook integrity** — Stripe signatures verified against the raw body.

## Run locally

```bash
cd backend
cp .env.example .env        # then fill in keys
npm install
npm start                   # or: npm run dev   (watch mode)
npm test                    # vitest + supertest
```

Without credentials the server still boots; payment endpoints return `503
not_configured` so the app shows its "unavailable" state.

## Testing payments (sandbox)

- Use Stripe **test** keys and the PayPal **sandbox**; both are free.
- Forward Stripe webhooks locally:
  `stripe listen --forward-to localhost:8080/webhooks/stripe`
- Quick checks:
  ```bash
  curl -s localhost:8080/health
  curl -s -X POST localhost:8080/create-payment-intent \
       -H 'Content-Type: application/json' -d '{"amount":500,"currency":"usd"}'
  ```

## Deploy

This service speaks plain HTTP — **terminate TLS at a reverse proxy / load
balancer** and put it behind HTTPS only.

```bash
docker build -t abbas-backend .
docker run --env-file .env -p 8080:8080 abbas-backend
```

Production checklist: `APP_ENV=production`, real keys, `PAYPAL_ENV=live`,
`APP_API_KEY` set, `TRUST_PROXY=true` behind a trusted proxy, webhook secret
configured.

## App-side changes (in `androidai-experimental`)

The app is already coded to call these endpoints; it just needs real values.

1. `app/src/main/java/ai/abbas/app/data/PaymentConfig.kt`
   - `STRIPE_PUBLISHABLE_KEY` = your `pk_…`
   - `STRIPE_BACKEND_URL` = `https://<host>/create-payment-intent`
   - `PAYPAL_CLIENT_ID` = your PayPal client id
   - `PAYPAL_BACKEND_URL` = `https://<host>/create-paypal-order`
2. If `APP_API_KEY` is set, send `X-Api-Key` on both POSTs (the key is embedded
   in the APK and therefore extractable — which is exactly why rate limiting and
   server-side amount validation exist).
3. After `onPayPalWebSuccess`, call `POST /capture-paypal-order` — otherwise the
   PayPal donation is approved but never captured (no money moves).
4. For live: `DonationScreen.kt` → Stripe
   `GooglePayConfiguration.Environment.Test` → `.Production`, and PayPal
   `Environment.SANDBOX` → `Environment.LIVE`.

## Threat model / limits

- The amount originates on the client and can be tampered with; the server
  enforces min/max bounds. For strict integrity, reconcile against the Stripe
  `payment_intent.succeeded` webhook and consider pinning allowed amounts.
- The app's `ai.abbas.app://paypalpay` deep link is exported and spoofable
  (see `SECURITY_PENTEST_REPORT.md`) — server-side capture + webhook
  reconciliation is the source of truth, not the client callback.
