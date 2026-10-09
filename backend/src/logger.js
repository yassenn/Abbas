/**
 * Minimal structured logger (JSON lines to stdout). Never log request bodies,
 * secrets, or full error objects — only safe, bounded fields.
 */
const write = (level, msg, meta) => {
  const line = { ts: new Date().toISOString(), level, msg, ...(meta || {}) };
  process.stdout.write(JSON.stringify(line) + '\n');
};

export const logger = {
  info: (msg, meta) => write('info', msg, meta),
  warn: (msg, meta) => write('warn', msg, meta),
  error: (msg, meta) => write('error', msg, meta),
};
