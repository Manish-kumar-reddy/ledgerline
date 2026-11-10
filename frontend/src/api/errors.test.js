import { describe, expect, it } from 'vitest';
import { toApiError } from './errors.js';

describe('toApiError', () => {
  it('maps a network failure to a retryable error', () => {
    const error = toApiError(new Error('Network Error'));
    expect(error.network).toBe(true);
    expect(error.retryable).toBe(true);
    expect(error.title).toMatch(/cannot reach/i);
  });

  it('reads problem details, field errors and Retry-After', () => {
    const error = toApiError({ response: { status: 429, headers: { 'retry-after': '60' },
      data: { title: 'Too many login attempts', detail: 'Try again later.' } } });
    expect(error).toMatchObject({ status: 429, title: 'Too many login attempts', detail: 'Try again later.', retryAfterSeconds: 60 });
    expect(error.retryable).toBe(false);

    const invalid = toApiError({ response: { status: 400, headers: {},
      data: { title: 'Invalid request', errors: [{ field: 'email', message: 'must be a well-formed email address' }] } } });
    expect(invalid.fieldErrors).toEqual([{ field: 'email', message: 'must be a well-formed email address' }]);
  });

  it('treats 503 and 5xx as retryable, 4xx as not', () => {
    expect(toApiError({ response: { status: 503, headers: { 'retry-after': '1' }, data: {} } }).retryable).toBe(true);
    expect(toApiError({ response: { status: 500, headers: {}, data: {} } }).retryable).toBe(true);
    expect(toApiError({ response: { status: 409, headers: {}, data: {} } }).retryable).toBe(false);
    expect(toApiError({ response: { status: 404, headers: {}, data: {} } }).title).toBe('Not found.');
  });
});
