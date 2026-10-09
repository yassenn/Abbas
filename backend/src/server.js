import { config, missingCredentials, stripeConfigured, paypalConfigured } from './config.js';
import { createApp } from './app.js';
import { logger } from './logger.js';

// Fail fast in production: never run a payment server with missing credentials.
if (config.isProd) {
  const missing = missingCredentials();
  if (missing.length > 0) {
    logger.error('startup_missing_credentials', { missing });
    process.exit(1);
  }
} else if (missingCredentials().length > 0) {
  logger.warn('running_unconfigured', {
    hint: 'Set Stripe/PayPal credentials in .env; payment endpoints return 503 until then.',
  });
}

const app = createApp();
const server = app.listen(config.port, () => {
  logger.info('listening', {
    port: config.port,
    env: config.env,
    stripe: stripeConfigured(),
    paypal: paypalConfigured(),
    apiKeyGate: Boolean(config.appApiKey),
  });
});

for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
