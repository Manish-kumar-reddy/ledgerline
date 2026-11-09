-- Phase 5 hardening after the V5 audit. V5 is unchanged; everything here replaces or adds to it.
--
--   M1  Statement timestamps are taken with clock_timestamp() AFTER the account locks, so on every account they
--       never decrease as the sequence number increases.
--   L2  The initiator's user row is locked FOR SHARE while posting, so a concurrent role change or disable
--       (set-user-role.sql uses FOR UPDATE) either completes first and is seen, or waits until the posting commits.
--   L3  Deferred checks also require: the outcome's initiator and operation match its idempotency key; a refusal is
--       on a CUSTOMER account owned by its initiator; a journal's customer side is owned by its initiator.
--   L4  At most one SYSTEM account, and it is the fixed settlement account.
--   L5  The posting function restores the caller's lock_timeout and only touches its own deferred constraints.
--   L6  A transaction that posts and then sits idle is ended after 30 s (releasing its locks).
--   L7  An empty description is rejected (the API sends NULL for "no description"), so NULL and '' can never share
--       an idempotency fingerprint. The fingerprint formula itself is unchanged, so existing keys still replay.
--
-- CREATE OR REPLACE keeps each function's owner and privileges (EXECUTE stays revoked from PUBLIC; the runtime role
-- keeps EXECUTE only on the three posting functions and reconciliation).

-- ---------------------------------------------------------------------------------------------------------------
-- L4: exactly one SYSTEM account, the settlement account
-- ---------------------------------------------------------------------------------------------------------------
ALTER TABLE public.accounts
    ADD CONSTRAINT ck_accounts_system_is_settlement
        CHECK (kind <> 'SYSTEM' OR id = 'a0000000-0000-4000-8000-000000000001');
CREATE UNIQUE INDEX uq_accounts_one_system ON public.accounts (kind) WHERE kind = 'SYSTEM';

-- ---------------------------------------------------------------------------------------------------------------
-- L7: no empty descriptions (NULL means "none")
-- ---------------------------------------------------------------------------------------------------------------
ALTER TABLE public.journal_transactions
    ADD CONSTRAINT ck_journal_description_not_empty CHECK (description IS NULL OR description <> '');
ALTER TABLE public.refused_attempts
    ADD CONSTRAINT ck_refused_description_not_empty CHECK (description IS NULL OR description <> '');

-- ---------------------------------------------------------------------------------------------------------------
-- L3: stronger deferred checks
-- ---------------------------------------------------------------------------------------------------------------

-- Exactly one outcome per key, and that outcome was made by the key's user for the key's operation.
CREATE OR REPLACE FUNCTION public.ledger_check_key_outcome(p_key uuid) RETURNS void
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_key      public.idempotency_keys%ROWTYPE;
    v_outcomes int;
    v_mismatch int;
BEGIN
    SELECT * INTO v_key FROM public.idempotency_keys k WHERE k.id = p_key;
    SELECT (SELECT count(*) FROM public.journal_transactions j WHERE j.idempotency_key_id = p_key)
         + (SELECT count(*) FROM public.refused_attempts r WHERE r.idempotency_key_id = p_key)
      INTO v_outcomes;
    IF v_outcomes <> 1 THEN
        RAISE EXCEPTION 'ledger invariant: idempotency key % has % outcomes (expected exactly 1)', p_key, v_outcomes
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT (SELECT count(*) FROM public.journal_transactions j
             WHERE j.idempotency_key_id = p_key AND (j.initiated_by <> v_key.user_id OR j.kind <> v_key.operation))
         + (SELECT count(*) FROM public.refused_attempts r
             WHERE r.idempotency_key_id = p_key AND (r.initiated_by <> v_key.user_id OR r.kind <> v_key.operation))
      INTO v_mismatch;
    IF v_mismatch <> 0 THEN
        RAISE EXCEPTION 'ledger invariant: the outcome of idempotency key % does not match its user or operation', p_key
            USING ERRCODE = 'check_violation';
    END IF;
END $$;

-- As V5, plus: the customer side of the journal (credited for a deposit, debited otherwise) belongs to the initiator.
CREATE OR REPLACE FUNCTION public.ledger_check_journal(p_txn uuid) RETURNS void
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_journal   public.journal_transactions%ROWTYPE;
    v_count     int;
    v_debits    int;
    v_credits   int;
    v_debit_sum bigint;
    v_credit_sum bigint;
    v_wrong_amount int;
    v_accounts  int;
    v_debit_kind  text;
    v_credit_kind text;
    v_customer_owner uuid;
BEGIN
    SELECT * INTO v_journal FROM public.journal_transactions j WHERE j.id = p_txn;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'ledger invariant: journal % does not exist', p_txn USING ERRCODE = 'check_violation';
    END IF;

    SELECT count(*),
           count(*) FILTER (WHERE e.direction = 'DEBIT'),
           count(*) FILTER (WHERE e.direction = 'CREDIT'),
           coalesce(sum(e.amount_minor) FILTER (WHERE e.direction = 'DEBIT'), 0),
           coalesce(sum(e.amount_minor) FILTER (WHERE e.direction = 'CREDIT'), 0),
           count(*) FILTER (WHERE e.amount_minor <> v_journal.amount_minor),
           count(DISTINCT e.account_id),
           max(a.kind) FILTER (WHERE e.direction = 'DEBIT'),
           max(a.kind) FILTER (WHERE e.direction = 'CREDIT')
      INTO v_count, v_debits, v_credits, v_debit_sum, v_credit_sum, v_wrong_amount, v_accounts,
           v_debit_kind, v_credit_kind
      FROM public.ledger_entries e
      JOIN public.accounts a ON a.id = e.account_id
     WHERE e.transaction_id = p_txn;

    IF v_count <> 2 OR v_debits <> 1 OR v_credits <> 1 THEN
        RAISE EXCEPTION 'ledger invariant: journal % must have exactly one debit and one credit entry (has %)',
            p_txn, v_count USING ERRCODE = 'check_violation';
    END IF;
    IF v_debit_sum <> v_credit_sum THEN
        RAISE EXCEPTION 'ledger invariant: journal % debits % do not equal credits %', p_txn, v_debit_sum, v_credit_sum
            USING ERRCODE = 'check_violation';
    END IF;
    IF v_wrong_amount > 0 THEN
        RAISE EXCEPTION 'ledger invariant: journal % entry amounts must equal the journal amount', p_txn
            USING ERRCODE = 'check_violation';
    END IF;
    IF v_accounts <> 2 THEN
        RAISE EXCEPTION 'ledger invariant: journal % must move money between two different accounts', p_txn
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT ((v_journal.kind = 'DEPOSIT' AND v_debit_kind = 'SYSTEM' AND v_credit_kind = 'CUSTOMER')
         OR (v_journal.kind = 'WITHDRAWAL' AND v_debit_kind = 'CUSTOMER' AND v_credit_kind = 'SYSTEM')
         OR (v_journal.kind = 'TRANSFER' AND v_debit_kind = 'CUSTOMER' AND v_credit_kind = 'CUSTOMER')) THEN
        RAISE EXCEPTION 'ledger invariant: journal % account kinds do not match a %', p_txn, v_journal.kind
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT a.owner_id INTO v_customer_owner
      FROM public.ledger_entries e JOIN public.accounts a ON a.id = e.account_id
     WHERE e.transaction_id = p_txn
       AND e.direction = CASE v_journal.kind WHEN 'DEPOSIT' THEN 'CREDIT' ELSE 'DEBIT' END;
    IF v_customer_owner IS DISTINCT FROM v_journal.initiated_by THEN
        RAISE EXCEPTION 'ledger invariant: journal % initiator does not own the account it operates on', p_txn
            USING ERRCODE = 'check_violation';
    END IF;

    PERFORM public.ledger_check_key_outcome(v_journal.idempotency_key_id);
END $$;

-- As V5, plus: the refusal is on a CUSTOMER account owned by its initiator.
CREATE OR REPLACE FUNCTION public.ledger_check_refusal(p_refusal uuid) RETURNS void
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_refusal public.refused_attempts%ROWTYPE;
    v_line    public.account_statement_lines%ROWTYPE;
    v_account public.accounts%ROWTYPE;
BEGIN
    SELECT * INTO v_refusal FROM public.refused_attempts r WHERE r.id = p_refusal;
    SELECT * INTO v_line FROM public.account_statement_lines l WHERE l.refused_attempt_id = p_refusal;
    IF NOT FOUND OR v_line.account_id <> v_refusal.account_id THEN
        RAISE EXCEPTION 'ledger invariant: refused attempt % needs exactly one statement line on its own account',
            p_refusal USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO v_account FROM public.accounts a WHERE a.id = v_refusal.account_id;
    IF v_account.kind <> 'CUSTOMER' OR v_account.owner_id IS DISTINCT FROM v_refusal.initiated_by THEN
        RAISE EXCEPTION 'ledger invariant: refused attempt % must be on a customer account owned by its initiator',
            p_refusal USING ERRCODE = 'check_violation';
    END IF;
    IF v_refusal.balance_at_attempt_minor <> public.ledger_balance_before(v_refusal.account_id, v_line.seq) THEN
        RAISE EXCEPTION 'ledger invariant: refused attempt % reports a balance that is not the account balance',
            p_refusal USING ERRCODE = 'check_violation';
    END IF;
    PERFORM public.ledger_check_key_outcome(v_refusal.idempotency_key_id);
END $$;

-- ---------------------------------------------------------------------------------------------------------------
-- M1, L2, L5, L6, L7: the posting routine (same signature, owner and privileges as V5)
-- ---------------------------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.ledger_post(p_kind text, p_initiator uuid, p_account uuid, p_counterparty uuid,
                                              p_amount bigint, p_description text, p_key text)
    RETURNS TABLE (r_outcome text, r_replayed boolean, r_kind text, r_journal_id uuid, r_refused_id uuid,
                   r_reason text, r_account_id uuid, r_counterparty_account_id uuid, r_amount_minor bigint,
                   r_balance_minor bigint, r_seq bigint, r_occurred_at timestamptz, r_initiated_by uuid)
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    c_settlement  CONSTANT uuid   := 'a0000000-0000-4000-8000-000000000001';
    c_max_amount  CONSTANT bigint := 100000000;      -- Rs 10,00,000.00
    c_max_balance CONSTANT bigint := 1000000000;     -- Rs 1,00,00,000.00
    v_caller_lock_timeout text := current_setting('lock_timeout');
    v_hash        bytea;
    v_key_id      uuid;
    v_existing    public.idempotency_keys%ROWTYPE;
    v_user        public.users%ROWTYPE;
    v_account     public.accounts%ROWTYPE;
    v_other       public.accounts%ROWTYPE;
    v_other_found boolean := false;
    v_counterparty uuid;
    v_lock_id     uuid;
    v_reason      text;
    v_seq         bigint;
    v_refused_id  uuid;
    v_journal_id  uuid;
    v_debit       uuid;
    v_credit      uuid;
    v_balance     bigint;
    v_entry_id    uuid;
    v_now         timestamptz;
BEGIN
    -- Transaction-scoped: lock waits are bounded, and a transaction that posts and then idles is ended (L6).
    -- lock_timeout is restored to the caller's value before returning (L5).
    PERFORM set_config('lock_timeout', '3s', true);
    PERFORM set_config('idle_in_transaction_session_timeout', '30s', true);

    -- Input. The API validates all of this first; reaching here with bad input is a programming error.
    IF p_kind IS NULL OR p_kind NOT IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')
       OR p_initiator IS NULL OR p_account IS NULL
       OR p_amount IS NULL OR p_amount < 1 OR p_amount > 99999999999
       OR p_key IS NULL OR p_key !~ '^[!-~]{1,100}$'
       OR (p_description IS NOT NULL AND (p_description = '' OR char_length(p_description) > 140
                                          OR p_description ~ '[[:cntrl:]]'))
       OR ((p_kind = 'TRANSFER') <> (p_counterparty IS NOT NULL))
       OR p_counterparty = p_account THEN
        RAISE EXCEPTION 'invalid posting request' USING ERRCODE = 'LL400';
    END IF;

    -- The initiator id is supplied by the application (from the authenticated principal). This function can only
    -- check that THIS id may perform the operation; it cannot prove the id belongs to the HTTP caller.
    -- FOR SHARE (L2): a concurrent role change or disable (FOR UPDATE / UPDATE) waits for this posting, or, if it
    -- got there first, this read waits and then sees the new role.
    SELECT * INTO v_user FROM public.users u WHERE u.id = p_initiator FOR SHARE;
    IF NOT FOUND OR NOT v_user.enabled THEN
        RAISE EXCEPTION 'initiator is not an enabled user' USING ERRCODE = 'LL401';
    END IF;
    IF v_user.role <> 'ACCOUNT_HOLDER' THEN
        RAISE EXCEPTION 'only account holders can move money' USING ERRCODE = 'LL403';
    END IF;

    -- Idempotency. A concurrent identical request waits here until the first commits or rolls back.
    v_hash := sha256(convert_to(concat_ws('|', p_kind, p_account::text, coalesce(p_counterparty::text, ''),
                                          p_amount::text, coalesce(p_description, '')), 'UTF8'));
    INSERT INTO public.idempotency_keys (user_id, idem_key, operation, request_hash)
    VALUES (p_initiator, p_key, p_kind, v_hash)
    ON CONFLICT (user_id, idem_key) DO NOTHING
    RETURNING id INTO v_key_id;

    IF v_key_id IS NULL THEN
        SELECT * INTO v_existing FROM public.idempotency_keys k WHERE k.user_id = p_initiator AND k.idem_key = p_key;
        IF v_existing.request_hash <> v_hash OR v_existing.operation <> p_kind THEN
            RAISE EXCEPTION 'idempotency key reused with a different request' USING ERRCODE = 'LL409';
        END IF;
        PERFORM set_config('lock_timeout', v_caller_lock_timeout, true);
        RETURN QUERY SELECT * FROM public.ledger_outcome(v_existing.id, true);
        RETURN;
    END IF;

    -- Lock every affected account in ascending id order (prevents deadlocks between opposite transfers).
    v_counterparty := CASE WHEN p_kind = 'TRANSFER' THEN p_counterparty ELSE c_settlement END;
    FOR v_lock_id IN SELECT x FROM unnest(ARRAY[p_account, v_counterparty]) AS x ORDER BY x LOOP
        PERFORM 1 FROM public.accounts a WHERE a.id = v_lock_id FOR UPDATE;
    END LOOP;

    -- M1: the time of this line, taken after the locks. Any earlier line on these accounts was committed by a
    -- transaction that released these locks before we got them, so its time is not later than this one.
    v_now := clock_timestamp();

    SELECT * INTO v_account FROM public.accounts a WHERE a.id = p_account;
    IF NOT FOUND OR v_account.kind <> 'CUSTOMER' OR v_account.owner_id <> p_initiator THEN
        RAISE EXCEPTION 'initiator does not own this account' USING ERRCODE = 'LL403';
    END IF;
    SELECT * INTO v_other FROM public.accounts a WHERE a.id = v_counterparty;
    v_other_found := FOUND;

    -- Business rules, decided under the locks.
    IF v_account.status <> 'ACTIVE' THEN
        v_reason := 'ACCOUNT_NOT_ACTIVE';
    ELSIF p_amount > c_max_amount THEN
        v_reason := 'AMOUNT_LIMIT_EXCEEDED';
    ELSIF p_kind = 'DEPOSIT' AND v_account.balance_minor + p_amount > c_max_balance THEN
        v_reason := 'BALANCE_LIMIT_EXCEEDED';
    ELSIF p_kind = 'TRANSFER' AND (NOT v_other_found OR v_other.kind <> 'CUSTOMER' OR v_other.status <> 'ACTIVE'
                                   OR v_other.balance_minor + p_amount > c_max_balance) THEN
        -- One reason for "missing", "inactive", "not a customer account" and "recipient at its limit".
        v_reason := 'DESTINATION_UNAVAILABLE';
    ELSIF p_kind IN ('WITHDRAWAL', 'TRANSFER') AND v_account.balance_minor < p_amount THEN
        v_reason := 'INSUFFICIENT_FUNDS';
    END IF;

    IF v_reason IS NOT NULL THEN
        -- Refused: one statement line on the caller's own account; the balance does not change.
        UPDATE public.accounts a SET last_seq = a.last_seq + 1 WHERE a.id = p_account RETURNING a.last_seq INTO v_seq;
        INSERT INTO public.refused_attempts (kind, account_id, counterparty_account_id, amount_minor, reason,
                                             balance_at_attempt_minor, initiated_by, idempotency_key_id, description,
                                             attempted_at)
        VALUES (p_kind, p_account, v_counterparty, p_amount, v_reason, v_account.balance_minor, p_initiator,
                v_key_id, p_description, v_now)
        RETURNING id INTO v_refused_id;
        INSERT INTO public.account_statement_lines (account_id, seq, refused_attempt_id)
        VALUES (p_account, v_seq, v_refused_id);
    ELSE
        IF p_kind = 'DEPOSIT' THEN
            v_debit := c_settlement;  v_credit := p_account;
        ELSE
            v_debit := p_account;     v_credit := v_counterparty;
        END IF;

        INSERT INTO public.journal_transactions (kind, amount_minor, initiated_by, idempotency_key_id, description,
                                                 posted_at)
        VALUES (p_kind, p_amount, p_initiator, v_key_id, p_description, v_now)
        RETURNING id INTO v_journal_id;

        UPDATE public.accounts a SET balance_minor = a.balance_minor - p_amount, last_seq = a.last_seq + 1
         WHERE a.id = v_debit RETURNING a.balance_minor, a.last_seq INTO v_balance, v_seq;
        INSERT INTO public.ledger_entries (transaction_id, account_id, direction, amount_minor, balance_after_minor,
                                           created_at)
        VALUES (v_journal_id, v_debit, 'DEBIT', p_amount, v_balance, v_now) RETURNING id INTO v_entry_id;
        INSERT INTO public.account_statement_lines (account_id, seq, ledger_entry_id) VALUES (v_debit, v_seq, v_entry_id);

        UPDATE public.accounts a SET balance_minor = a.balance_minor + p_amount, last_seq = a.last_seq + 1
         WHERE a.id = v_credit RETURNING a.balance_minor, a.last_seq INTO v_balance, v_seq;
        INSERT INTO public.ledger_entries (transaction_id, account_id, direction, amount_minor, balance_after_minor,
                                           created_at)
        VALUES (v_journal_id, v_credit, 'CREDIT', p_amount, v_balance, v_now) RETURNING id INTO v_entry_id;
        INSERT INTO public.account_statement_lines (account_id, seq, ledger_entry_id) VALUES (v_credit, v_seq, v_entry_id);
    END IF;

    -- Run this posting's pending ledger checks now, so a violation surfaces as an error from this call; then put
    -- them back to their default (deferred) mode. Only the ledger's own constraint triggers are touched (L5); any
    -- that are still pending in the caller's transaction are checked again at commit.
    SET CONSTRAINTS public.journal_valid, public.entry_valid, public.refusal_valid, public.key_outcome,
        public.account_consistent IMMEDIATE;
    SET CONSTRAINTS public.journal_valid, public.entry_valid, public.refusal_valid, public.key_outcome,
        public.account_consistent DEFERRED;

    PERFORM set_config('lock_timeout', v_caller_lock_timeout, true);
    RETURN QUERY SELECT * FROM public.ledger_outcome(v_key_id, false);
END $$;
