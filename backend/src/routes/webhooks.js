import { constructStripeEvent } from '../services/stripe.js';
import { logger } from '../logger.js';

/**
 * Stripe webhook receiver. Mounted with express.raw() so the exact bytes are
 * available for signature verification. Used to reconcile completed payments
 * out-of-band from the app.
 */
export const stripeWebhook = (req, res) => {
  let event;
  try {
    event = constructStripeEvent(req.body, req.get('stripe-signature'));
  } catch (err) {
    logger.warn('stripe.webhook_rejected', { reason: err?.message });
    return res.status(400).json({ error: { code: 'invalid_signature', message: 'Invalid signature' } });
  }

  // Handle only what we care about; acknowledge everything else.
  switch (event.type) {
    case 'payment_intent.succeeded':
      // No PII / no amounts logged — just the event id for reconciliation.
      logger.info('stripe.payment_succeeded', { eventId: event.id });
      break;
    case 'payment_intent.payment_failed':
      logger.info('stripe.payment_failed', { eventId: event.id });
      break;
    default:
      break;
  }

  res.json({ received: true });
};
