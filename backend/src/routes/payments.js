import { Router } from 'express';
import { z } from 'zod';
import { config } from '../config.js';
import { validateBody } from '../middleware/validate.js';
import * as stripeService from '../services/stripe.js';
import * as paypalService from '../services/paypal.js';

const router = Router();

// ── Stripe ──────────────────────────────────────────────────────────────────
// The Android app sends the amount in CENTS (amount * 100) with currency "usd".
const stripeSchema = z
  .object({
    amount: z.number().int().min(config.limits.minCents).max(config.limits.maxCents),
    currency: z.string().trim().toLowerCase().pipe(z.literal('usd')),
  })
  .strict();

router.post('/create-payment-intent', validateBody(stripeSchema), async (req, res, next) => {
  try {
    const { amount, currency } = req.validated;
    const idempotencyKey = req.get('idempotency-key') || undefined;
    const result = await stripeService.createPaymentIntent({
      amountCents: amount,
      currency,
      idempotencyKey,
    });
    // Exact shape the app reads: { paymentIntent, ephemeralKey, customer }
    res.json(result);
  } catch (err) {
    next(err);
  }
});

// ── PayPal ──────────────────────────────────────────────────────────────────
// The Android app sends the amount in DOLLARS (a number) with currency "USD".
const paypalSchema = z
  .object({
    amount: z.number().positive().min(config.limits.minDollars).max(config.limits.maxDollars),
    currency: z.string().trim().toUpperCase().pipe(z.literal('USD')),
  })
  .strict();

router.post('/create-paypal-order', validateBody(paypalSchema), async (req, res, next) => {
  try {
    const { amount, currency } = req.validated;
    // Exact shape the app reads: { orderId }
    res.json(await paypalService.createOrder({ amountDollars: amount, currency }));
  } catch (err) {
    next(err);
  }
});

// Capture is what actually moves money after the donor approves in the browser.
const captureSchema = z.object({ orderId: z.string().trim().min(6).max(64) }).strict();

router.post('/capture-paypal-order', validateBody(captureSchema), async (req, res, next) => {
  try {
    res.json(await paypalService.captureOrder({ orderId: req.validated.orderId }));
  } catch (err) {
    next(err);
  }
});

export default router;
