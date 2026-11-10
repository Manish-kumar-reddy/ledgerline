import { api } from './client.js';

// 201 (posted) and 422 (refused, recorded on the statement) are both outcomes, not errors.
const outcome = { validateStatus: (status) => status === 201 || status === 422 };

function toResult(response) {
  return { ...response.data, httpStatus: response.status, replayed: response.headers?.['idempotent-replayed'] === 'true' };
}

function post(path, body, idempotencyKey) {
  return api.post(path, body, { ...outcome, headers: { 'Idempotency-Key': idempotencyKey } }).then(toResult);
}

/** amount is the exact string the user typed, e.g. "500.00". */
export function deposit(accountId, { amount, description }, idempotencyKey) {
  return post(`/api/accounts/${encodeURIComponent(accountId)}/deposits`, body(amount, description), idempotencyKey);
}

export function withdraw(accountId, { amount, description }, idempotencyKey) {
  return post(`/api/accounts/${encodeURIComponent(accountId)}/withdrawals`, body(amount, description), idempotencyKey);
}

export function transfer(accountId, { toAccountId, amount, description }, idempotencyKey) {
  return post(`/api/accounts/${encodeURIComponent(accountId)}/transfers`,
    { toAccountId, ...body(amount, description) }, idempotencyKey);
}

export async function statement(accountId, page = 0, size = 25) {
  return (await api.get(`/api/accounts/${encodeURIComponent(accountId)}/statement`, { params: { page, size } })).data;
}

function body(amount, description) {
  const trimmed = description?.trim();
  return trimmed ? { amount, description: trimmed } : { amount };
}
