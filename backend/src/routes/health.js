import { Router } from 'express';
import { stripeConfigured, paypalConfigured } from '../config.js';

const router = Router();

/** Liveness + capability probe. Never leaks keys or configuration values. */
router.get('/health', (_req, res) => {
  res.json({
    status: 'ok',
    stripe: stripeConfigured(),
    paypal: paypalConfigured(),
  });
});

export default router;
