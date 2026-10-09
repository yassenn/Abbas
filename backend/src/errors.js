/**
 * Operational error with an HTTP status and a stable machine code.
 * Messages are safe to return to clients (never include secrets / stack traces).
 */
export class AppError extends Error {
  constructor(message, status = 400, code = 'bad_request') {
    super(message);
    this.name = 'AppError';
    this.status = status;
    this.code = code;
  }
}

export const unauthorized = (m = 'Unauthorized') => new AppError(m, 401, 'unauthorized');
export const notFound = (m = 'Not found') => new AppError(m, 404, 'not_found');
export const notConfigured = (m = 'Payment provider is not configured') =>
  new AppError(m, 503, 'not_configured');
