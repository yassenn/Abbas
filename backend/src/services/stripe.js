import Stripe from 'stripe';
import { config } from '../config.js';
import { AppError, notConfigured } from '../errors.js';
import { logger } from '../logger.js';

let client;

const getClient = () => {
  if (!config.stripe.secretKey) throw notConfigured('Stripe is not configured');
  client ??= new Stripe(config.stripe.secretKey, { apiVersion: config.stripe.apiVersion });
  return client;
};

/**
 * Create a PaymentIntent together with the Customer and EphemeralKey the
 * mobile PaymentSheet needs. Returns the exact shape the Abbas app reads:
 * { paymentIntent, ephemeralKey, customer }.
 *
 * The ephemeral key is issued with the same API version as the client SDK
 * (STRIPE_API_VERSION) — a mismatch makes PaymentSheet reject it.
 */
export async function createPaymentIntent({ amountCents, currency, idempotencyKey }) {
  const stripe = getClient();
  const idem = idempotencyKey ? { idempotencyKey } : {};
  try {
    const customer = await stripe.customers.create({ metadata: { app: 'abbas-ai' } }, idem);
    const ephemeralKey = await stripe.ephemeralKeys.create(
      { customer: customer.id },
      { apiVersion: config.stripe.apiVersion },
    );
    const intent = await stripe.paymentIntents.create(
      {
        amount: amountCents,
        currency,
        customer: customer.id,
        automatic_payment_methods: { enabled: true },
        metadata: { app: 'abbas-ai' },
      },
      idem,
    );
    return {
      paymentIntent: intent.client_secret,
      ephemeralKey: ephemeralKey.secret,
      customer: customer.id,
    };
  } catch (e) {
    // Log only the provider's error type/code — never the key or request body.
    logger.error('stripe.create_payment_intent_failed', { type: e?.type, code: e?.code });
    throw new AppError('Could not initialise the payment', 502, 'provider_error');
  }
}

/** Verify a Stripe webhook signature and return the parsed event. */
export function constructStripeEvent(rawBody, signature) {
  if (!config.stripe.webhookSecret) throw new AppError('Webhook not configured', 503, 'not_configured');
  return getClient().webhooks.constructEvent(rawBody, signature, config.stripe.webhookSecret);
}
