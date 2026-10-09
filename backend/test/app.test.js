import { describe, it, expect } from 'vitest';
import request from 'supertest';
import { createApp } from '../src/app.js';
import { apiKeyGate } from '../src/middleware/apiKey.js';

// NOTE: these tests assume no provider credentials are present in the
// environment, so the payment endpoints are "not configured" (503). Validation
// and security paths are what we exercise here; the provider round-trips are
// verified against Stripe test mode / PayPal sandbox in CI/deploy.
const app = createApp();

describe('health', () => {
  it('reports ok and provider capability flags', async () => {
    const res = await request(app).get('/health');
    expect(res.status).toBe(200);
    expect(res.body.status).toBe('ok');
    expect(typeof res.body.stripe).toBe('boolean');
    expect(typeof res.body.paypal).toBe('boolean');
  });
});

describe('routing & error envelope', () => {
  it('404 for an unknown route', async () => {
    const res = await request(app).get('/does-not-exist');
    expect(res.status).toBe(404);
    expect(res.body.error.code).toBe('not_found');
  });

  it('400 on malformed JSON', async () => {
    const res = await request(app)
      .post('/create-payment-intent')
      .set('Content-Type', 'application/json')
      .send('{not json');
    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('bad_json');
  });
});

describe('POST /create-payment-intent', () => {
  const post = (body) => request(app).post('/create-payment-intent').send(body);

  it('422 when amount is missing', async () => {
    expect((await post({ currency: 'usd' })).status).toBe(422);
  });

  it('422 when amount is below the minimum', async () => {
    expect((await post({ amount: 50, currency: 'usd' })).status).toBe(422);
  });

  it('422 when amount is not an integer', async () => {
    expect((await post({ amount: 10.5, currency: 'usd' })).status).toBe(422);
  });

  it('422 when currency is not usd', async () => {
    expect((await post({ amount: 500, currency: 'eur' })).status).toBe(422);
  });

  it('422 when unexpected fields are supplied (strict schema)', async () => {
    expect((await post({ amount: 500, currency: 'usd', extra: 1 })).status).toBe(422);
  });

  it('503 (not configured) for a valid request with no Stripe key', async () => {
    const res = await post({ amount: 500, currency: 'usd' });
    expect(res.status).toBe(503);
    expect(res.body.error.code).toBe('not_configured');
  });
});

describe('POST /create-paypal-order', () => {
  const post = (body) => request(app).post('/create-paypal-order').send(body);

  it('422 for a non-positive amount', async () => {
    expect((await post({ amount: 0, currency: 'USD' })).status).toBe(422);
  });

  it('503 for a valid amount with no PayPal credentials', async () => {
    expect((await post({ amount: 5, currency: 'USD' })).status).toBe(503);
  });
});

describe('POST /capture-paypal-order', () => {
  it('422 for a malformed order id', async () => {
    const res = await request(app).post('/capture-paypal-order').send({ orderId: 'x' });
    expect(res.status).toBe(422);
  });
});

describe('apiKeyGate', () => {
  const run = (gate, headers = {}) =>
    new Promise((resolve) => {
      const req = { get: (h) => headers[h.toLowerCase()] };
      gate(req, {}, (err) => resolve(err ?? null));
    });

  it('is a no-op when no key is configured', async () => {
    expect(await run(apiKeyGate(''))).toBeNull();
  });

  it('passes with the correct key', async () => {
    expect(await run(apiKeyGate('s3cret'), { 'x-api-key': 's3cret' })).toBeNull();
  });

  it('rejects a wrong key with 401', async () => {
    const err = await run(apiKeyGate('s3cret'), { 'x-api-key': 'nope' });
    expect(err?.status).toBe(401);
  });

  it('rejects a missing key with 401', async () => {
    const err = await run(apiKeyGate('s3cret'));
    expect(err?.status).toBe(401);
  });
});
