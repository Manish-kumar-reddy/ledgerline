import { NO_FILTERS, OPERATION_OPTIONS, STATUS_OPTIONS, isFiltered } from './statementFilterOptions.js';

/** Two dropdowns that narrow a statement on the server. onChange receives the complete new filter object. */
export default function StatementFilters({ value, onChange, idPrefix = 'statement' }) {
  function select(id, key, label, options) {
    return (
      <div className="field">
        <label htmlFor={id}>{label}</label>
        <select id={id} value={value[key]} onChange={(event) => onChange({ ...value, [key]: event.target.value })}>
          {options.map(([optionValue, text]) => <option key={optionValue} value={optionValue}>{text}</option>)}
        </select>
      </div>
    );
  }
  return (
    <div className="statement-filters" role="group" aria-label="Filter statement">
      {select(`${idPrefix}-status`, 'status', 'Status', STATUS_OPTIONS)}
      {select(`${idPrefix}-operation`, 'operation', 'Type', OPERATION_OPTIONS)}
      {isFiltered(value) && (
        <button type="button" className="button button--ghost" onClick={() => onChange(NO_FILTERS)}>Clear filters</button>
      )}
    </div>
  );
}

function label(options, value) {
  return options.find(([optionValue]) => optionValue === value)?.[1];
}

/** Says which filters are applied and how many lines match; also printed, since the dropdowns are not. */
export function FilterSummary({ filters, totalLines }) {
  if (!isFiltered(filters)) return null;
  const parts = [label(STATUS_OPTIONS, filters.status), label(OPERATION_OPTIONS, filters.operation)].filter(Boolean);
  return (
    <p className="muted" role="status">
      Filtered: {parts.join(' · ')}. {totalLines} matching {totalLines === 1 ? 'line' : 'lines'}; sequence numbers and
      balances are those of the full statement.
    </p>
  );
}
