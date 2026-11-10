// One Idempotency-Key per intended operation.
//
// - The same request retried after a network error, 5xx or 503 reuses the same key, so the backend can never post
//   it twice (it either replays the stored result or posts it once).
// - Any change to the request gets a new key (reusing a key for a different request is a 409 by design).
// - After a definite outcome (posted, refused, rejected) the next submission is a new operation with a new key.

export class IdempotencyKeys {
  constructor(newKey = () => crypto.randomUUID()) {
    this.newKey = newKey;
    this.pending = null;   // { signature, key } awaiting a definite outcome
  }

  keyFor(request) {
    const signature = JSON.stringify(request);
    if (!this.pending || this.pending.signature !== signature) {
      this.pending = { signature, key: this.newKey() };
    }
    return this.pending.key;
  }

  /** Call with the error (or null on a definite outcome). Keeps the key only when a retry is safe and useful. */
  settle(error) {
    if (!error || !error.retryable) {
      this.pending = null;
    }
  }
}
