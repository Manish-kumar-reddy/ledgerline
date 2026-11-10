import { describe, expect, it } from 'vitest';
import { ApiError } from './errors.js';
import { IdempotencyKeys } from './idempotency.js';

function keys() {
  let n = 0;
  return new IdempotencyKeys(() => `key-${++n}`);
}

describe('IdempotencyKeys', () => {
  const request = { kind: 'withdraw', accountId: 'a', amount: '10' };

  it('reuses the same key when the same request is retried after a retryable failure', () => {
    const k = keys();
    const first = k.keyFor(request);
    k.settle(new ApiError({ status: 0, network: true }));
    expect(k.keyFor({ ...request })).toBe(first);
    k.settle(new ApiError({ status: 503 }));
    expect(k.keyFor(request)).toBe(first);
  });

  it('uses a new key when the request changes', () => {
    const k = keys();
    const first = k.keyFor(request);
    k.settle(new ApiError({ status: 503 }));
    expect(k.keyFor({ ...request, amount: '11' })).not.toBe(first);
  });

  it('uses a new key after a definite outcome or a non-retryable error', () => {
    const k = keys();
    const first = k.keyFor(request);
    k.settle(null);
    const second = k.keyFor(request);
    expect(second).not.toBe(first);
    k.settle(new ApiError({ status: 409 }));
    expect(k.keyFor(request)).not.toBe(second);
  });
});
