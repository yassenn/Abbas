import rateLimit from 'express-rate-limit';

const base = { standardHeaders: true, legacyHeaders: false };

/** Broad limit for all traffic from one IP. */
export const globalLimiter = rateLimit({ windowMs: 60_000, max: 120, ...base });

/** Tighter limit for the payment endpoints (each call hits a provider). */
export const paymentLimiter = rateLimit({ windowMs: 60_000, max: 20, ...base });
