import express from 'express';
import helmet from 'helmet';
import { config } from './config.js';
import { AppError } from './errors.js';
import { logger } from './logger.js';
import { apiKeyGate } from './middleware/apiKey.js';
import { globalLimiter, paymentLimiter } from './middleware/rateLimit.js';
import healthRouter from './routes/health.js';
import paymentsRouter from './routes/payments.js';
import { stripeWebhook } from './routes/webhooks.js';

/** Build the Express app. Exported separately so tests can mount it directly. */
export function createApp() {
  const app = express();
  app.disable('x-powered-by');
  if (config.trustProxy) app.set('trust proxy', 1);
  app.use(helmet());

  // Stripe needs the RAW body for signature verification — mount it before the
  // JSON parser so it isn't consumed/parsed first.
  app.post('/webhooks/stripe', express.raw({ type: 'application/json', limit: '256kb' }), stripeWebhook);

  app.use(express.json({ limit: '10kb', strict: true }));
  app.use(globalLimiter);

  app.use(healthRouter);
  app.use(apiKeyGate(config.appApiKey), paymentLimiter, paymentsRouter);

  // Unknown route
  app.use((_req, _res, next) => next(new AppError('Not found', 404, 'not_found')));

  // Terminal error handler — never leaks stack traces or provider internals.
  // eslint-disable-next-line no-unused-vars
  app.use((err, _req, res, _next) => {
    if (err?.type === 'entity.parse.failed') {
      return res.status(400).json({ error: { code: 'bad_json', message: 'Malformed JSON body' } });
    }
    const isApp = err instanceof AppError;
    const status = isApp ? err.status : 500;
    if (status >= 500) logger.error('request_failed', { status });
    return res.status(status).json({
      error: {
        code: isApp ? err.code : 'internal_error',
        message: isApp ? err.message : 'An internal error occurred',
      },
    });
  });

  return app;
}
