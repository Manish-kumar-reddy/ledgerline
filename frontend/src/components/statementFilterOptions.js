// Values must match the backend's accepted filter values exactly; '' means "all" and is never sent.
export const STATUS_OPTIONS = [['', 'All statuses'], ['POSTED', 'Posted'], ['REFUSED', 'Refused']];
export const OPERATION_OPTIONS = [['', 'All types'], ['DEPOSIT', 'Deposits'], ['WITHDRAWAL', 'Withdrawals'], ['TRANSFER', 'Transfers']];

export const NO_FILTERS = { status: '', operation: '' };

export function isFiltered(filters) {
  return Boolean(filters.status || filters.operation);
}
