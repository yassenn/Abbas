import { AppError } from '../errors.js';

/**
 * Validate `req.body` against a zod schema. On failure we return a generic
 * 422 — zod's detailed issues are never echoed to the client (they can reveal
 * schema internals). The parsed value is placed on `req.validated`.
 */
export const validateBody = (schema) => (req, _res, next) => {
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) {
    return next(new AppError('Invalid request body', 422, 'validation_error'));
  }
  req.validated = parsed.data;
  next();
};
