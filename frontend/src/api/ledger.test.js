import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { api } from './client.js';
import { anyStatement } from './oversight.js';
import { deposit, statement, transfer, withdraw } from './ledger.js';

let seen;
let reply;
const original = api.defaults.adapter;

beforeEach(() => {
  seen = [];
  api.defaults.adapter = async (config) => {
    seen.push(config);
    const response = { ...reply, config, statusText: '' };
    if (config.validateStatus(response.status)) return response;
    const error = new Error('failed');
    error.response = response;
    error.config = config;
    throw error;
  };
});
afterEach(() => { api.defaults.adapter = original; });

describe('ledger api', () => {
  it('sends the amount as the exact string, with the Idempotency-Key header', async () => {
    reply = { status: 201, headers: {}, data: { outcome: 'POSTED' } };
    const result = await deposit('acc-1', { amount: '1500.50', description: '  salary ' }, 'key-1');
    expect(seen[0].url).toBe('/api/accounts/acc-1/deposits');
    expect(seen[0].headers['Idempotency-Key']).toBe('key-1');
    expect(JSON.parse(seen[0].data)).toEqual({ amount: '1500.50', description: 'salary' });
    expect(result).toMatchObject({ outcome: 'POSTED', httpStatus: 201, replayed: false });
  });

  it('treats a 422 refusal as an outcome and reports replays', async () => {
    reply = { status: 422, headers: { 'idempotent-replayed': 'true' }, data: { outcome: 'REFUSED', reason: 'INSUFFICIENT_FUNDS' } };
    const result = await withdraw('acc-1', { amount: '10' }, 'key-2');
    expect(result).toMatchObject({ outcome: 'REFUSED', reason: 'INSUFFICIENT_FUNDS', httpStatus: 422, replayed: true });
    expect(JSON.parse(seen[0].data)).toEqual({ amount: '10' });
  });

  it('sends transfers with the destination and rejects a 409 as an error', async () => {
    reply = { status: 409, headers: {}, data: { title: 'Idempotency key reused' } };
    await expect(transfer('acc-1', { toAccountId: 'acc-2', amount: '5' }, 'key-3')).rejects.toMatchObject({ status: 409 });
    expect(JSON.parse(seen[0].data)).toEqual({ toAccountId: 'acc-2', amount: '5' });
  });

  it('sends only the statement filters that are set, on both statement endpoints', async () => {
    reply = { status: 200, headers: {}, data: { lines: [] } };
    await statement('acc 1', 2, 25, { status: 'REFUSED', operation: '' });
    await anyStatement('acc-1', 0, 25, { status: '', operation: 'TRANSFER' });
    await statement('acc-1');
    await anyStatement('acc-1', 1, 10, { status: 'POSTED', operation: 'DEPOSIT' });
    expect(seen.map((config) => [config.url, config.params])).toEqual([
      ['/api/accounts/acc%201/statement', { page: 2, size: 25, status: 'REFUSED' }],
      ['/api/oversight/accounts/acc-1/statement', { page: 0, size: 25, operation: 'TRANSFER' }],
      ['/api/accounts/acc-1/statement', { page: 0, size: 25 }],
      ['/api/oversight/accounts/acc-1/statement', { page: 1, size: 10, status: 'POSTED', operation: 'DEPOSIT' }],
    ]);
  });
});
