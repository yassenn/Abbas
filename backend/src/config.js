import 'dotenv/config';

const bool = (v) => String(v).toLowerCase() === 'true';
const num = (v, d) => (Number.isFinite(Number(v)) ? Number(v) : d);

/**
 * Central, immutable configuration. Every secret is read from the environment
 * and never logged. See `.env.example` for the full list.
 */
export const config = Object.freeze({
  env: process.env.APP_ENV || 'development',
  isProd: (process.env.APP_ENV || 'development') === 'production',
  port: num(process.env.PORT, 8080),
  trustProxy: bool(process.env.TRUST_PROXY),
  appApiKey: process.env.APP_API_KEY || '',
  stripe: Object.freeze({
    secretKey: process.env.STRIPE_SECRET_KEY || '',
    apiVersion: process.env.STRIPE_API_VERSION || '2024-12-18.acacia',
    webhookSecret: process.env.STRIPE_WEBHOOK_SECRET || '',
  }),
  paypal: Object.freeze({
    clientId: process.env.PAYPAL_CLIENT_ID || '',
    clientSecret: process.env.PAYPAL_CLIENT_SECRET || '',
    webhookId: process.env.PAYPAL_WEBHOOK_ID || '',
    base:
      (process.env.PAYPAL_ENV || 'sandbox') === 'live'
        ? 'https://api-m.paypal.com'
        : 'https://api-m.sandbox.paypal.com',
  }),
  limits: Object.freeze({
    minCents: num(process.env.MIN_AMOUNT_CENTS, 100),
    maxCents: num(process.env.MAX_AMOUNT_CENTS, 1_000_000),
    minDollars: num(process.env.MIN_AMOUNT_DOLLARS, 1),
    maxDollars: num(process.env.MAX_AMOUNT_DOLLARS, 10_000),
  }),
});

export const stripeConfigured = () => Boolean(config.stripe.secretKey);
export const paypalConfigured = () =>
  Boolean(config.paypal.clientId && config.paypal.clientSecret);

/** Names of required credentials missing for the current environment. */
export function missingCredentials() {
  const missing = [];
  if (!stripeConfigured()) {
    missing.push('STRIPE_SECRET_KEY');
  }
  if (!paypalConfigured()) {
    missing.push('PAYPAL_CLIENT_ID', 'PAYPAL_CLIENT_SECRET');
  }
  return missing;
}
