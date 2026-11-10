// Turns any failed request into one predictable shape for the UI. Never includes the request body or token.

export class ApiError extends Error {
  constructor({ status, title, detail, fieldErrors = [], retryAfterSeconds = null, network = false }) {
    super(title || 'Request failed');
    this.status = status;
    this.title = title;
    this.detail = detail;
    this.fieldErrors = fieldErrors;
    this.retryAfterSeconds = retryAfterSeconds;
    this.network = network;
  }

  /** Safe to resend with the same Idempotency-Key: nothing was committed, or we cannot know (no response). */
  get retryable() {
    return this.network || this.status === 503 || this.status >= 500;
  }
}

const FALLBACK_TITLES = {
  400: 'Please check the details and try again.',
  401: 'Your session has ended. Please sign in again.',
  403: 'You do not have permission to do this.',
  404: 'Not found.',
  409: 'This request conflicts with an earlier one.',
  429: 'Too many attempts. Please wait and try again.',
  503: 'The service is busy. Please try again.',
};

export function toApiError(error) {
  const response = error?.response;
  if (!response) {
    return new ApiError({ status: 0, title: 'Cannot reach the LedgerLine server. Check your connection.', network: true });
  }
  const body = response.data && typeof response.data === 'object' ? response.data : {};
  const retryHeader = response.headers?.['retry-after'];
  const retryAfterSeconds = retryHeader != null && /^\d+$/.test(String(retryHeader)) ? Number(retryHeader) : null;
  const fieldErrors = Array.isArray(body.errors)
    ? body.errors.filter((e) => e && typeof e.field === 'string').map((e) => ({ field: e.field, message: String(e.message) }))
    : [];
  return new ApiError({
    status: response.status,
    title: typeof body.title === 'string' ? body.title : FALLBACK_TITLES[response.status] ?? 'Something went wrong.',
    detail: typeof body.detail === 'string' ? body.detail : undefined,
    fieldErrors,
    retryAfterSeconds,
  });
}
