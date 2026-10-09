import { randomUUID } from 'node:crypto';
import { config } from '../config.js';
import { AppError, notConfigured } from '../errors.js';
import { logger } from '../logger.js';

// PayPal order ids are alphanumeric with dashes; constrain before URL-building.
const ORDER_ID_RE = /^[A-Za-z0-9-]{6,64}$/;

let tokenCache = { value: '', expiresAt: 0 };

async function accessToken() {
  if (!config.paypal.clientId || !config.paypal.clientSecret) {
    throw notConfigured('PayPal is not configured');
  }
  if (tokenCache.value && Date.now() < tokenCache.expiresAt) return tokenCache.value;

  const basic = Buffer.from(
    `${config.paypal.clientId}:${config.paypal.clientSecret}`,
  ).toString('base64');
  const res = await fetch(`${config.paypal.base}/v1/oauth2/token`, {
    method: 'POST',
    headers: {
      Authorization: `Basic ${basic}`,
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: 'grant_type=client_credentials',
  });
  if (!res.ok) {
    logger.error('paypal.oauth_failed', { status: res.status });
    throw new AppError('PayPal is unavailable', 502, 'provider_error');
  }
  const json = await res.json();
  tokenCache = {
    value: json.access_token,
    expiresAt: Date.now() + Math.max(0, (json.expires_in - 60)) * 1000,
  };
  return tokenCache.value;
}

/** Create a CAPTURE-intent PayPal order and return its id. */
export async function createOrder({ amountDollars, currency }) {
  const token = await accessToken();
  const res = await fetch(`${config.paypal.base}/v2/checkout/orders`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      'PayPal-Request-Id': randomUUID(),
    },
    body: JSON.stringify({
      intent: 'CAPTURE',
      purchase_units: [
        { amount: { currency_code: currency, value: amountDollars.toFixed(2) } },
      ],
    }),
  });
  if (!res.ok) {
    logger.error('paypal.create_order_failed', { status: res.status });
    throw new AppError('Could not create the PayPal order', 502, 'provider_error');
  }
  const order = await res.json();
  return { orderId: order.id };
}

/** Capture an approved order — this is the step that actually moves money. */
export async function captureOrder({ orderId }) {
  if (!ORDER_ID_RE.test(orderId)) {
    throw new AppError('Invalid order id', 422, 'validation_error');
  }
  const token = await accessToken();
  const res = await fetch(
    `${config.paypal.base}/v2/checkout/orders/${encodeURIComponent(orderId)}/capture`,
    {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    },
  );
  const json = await res.json().catch(() => ({}));
  if (!res.ok) {
    logger.error('paypal.capture_failed', { status: res.status });
    throw new AppError('Could not capture the PayPal order', 502, 'provider_error');
  }
  return { id: json.id, status: json.status };
}
