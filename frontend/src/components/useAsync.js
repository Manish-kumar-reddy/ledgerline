import { useCallback, useEffect, useState } from 'react';

/** Loads data with loading/error state; reload() re-runs it. Ignores results that arrive after unmount. */
export function useAsync(load, deps) {
  const [state, setState] = useState({ loading: true, data: null, error: null });
  const [version, setVersion] = useState(0);

  useEffect(() => {
    let active = true;
    setState((s) => ({ ...s, loading: true, error: null }));
    load().then(
      (data) => active && setState({ loading: false, data, error: null }),
      (error) => active && setState({ loading: false, data: null, error }),
    );
    return () => { active = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, version]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);
  return { ...state, reload };
}
