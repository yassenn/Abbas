import crypto from 'node:crypto';
import { unauthorized } from '../errors.js';

/**
 * Optional shared-secret gate. When APP_API_KEY is set, every protected
 * request must carry a matching `X-Api-Key` header (compared in constant time
 * to avoid timing leaks). No-op when the key is unset (local development).
 */
export const apiKeyGate = (key) => (req, _res, next) => {
  if (!key) return next();
  const provided = Buffer.from(req.get('x-api-key') || '');
  const expected = Buffer.from(key);
  const ok = provided.length === expected.length && crypto.timingSafeEqual(provided, expected);
  return ok ? next() : next(unauthorized('Missing or invalid API key'));
};
