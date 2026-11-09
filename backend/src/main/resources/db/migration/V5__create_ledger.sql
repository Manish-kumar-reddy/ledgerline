-- Phase 5: double-entry ledger, PostgreSQL-controlled posting, idempotency, refused attempts and statements.
--
-- Accounting model (README.md, "Financial model"): conventional double-entry bookkeeping. Every journal transaction
-- has exactly one DEBIT and one CREDIT of the same amount on two different accounts. Every account's balance is
-- defined the same way: balance_minor = SUM(credits) - SUM(debits), in paise. Customer accounts (the bank's
-- liabilities) are therefore >= 0; the simulated SETTLEMENT_CASH account (an asset) is <= 0, its magnitude being the
-- cash held. The sum of all balances is always 0.
--
-- Write path: the runtime role has NO write privilege on any ledger table and no UPDATE on accounts. Money moves only
-- through ledger_deposit / ledger_withdraw / ledger_transfer (SECURITY DEFINER, owned by the migration role).
-- These functions check the initiator id they are GIVEN; they cannot authenticate the HTTP user (THREATS.md).

-- ---------------------------------------------------------------------------------------------------------------
-- 1. accounts: kind, stored balance and per-account statement counter
-- ---------------------------------------------------------------------------------------------------------------
ALTER TABLE public.accounts
    ADD COLUMN kind          varchar(10) NOT NULL DEFAULT 'CUSTOMER',
    ADD COLUMN balance_minor bigint      NOT NULL DEFAULT 0,
    ADD COLUMN last_seq      bigint      NOT NULL DEFAULT 0;

ALTER TABLE public.accounts ALTER COLUMN owner_id DROP NOT NULL;

ALTER TABLE public.accounts
    ADD CONSTRAINT ck_accounts_kind CHECK (kind IN ('CUSTOMER', 'SYSTEM')),
    -- A customer account always has an owner; a system account never has one.
    ADD CONSTRAINT ck_accounts_owner_matches_kind CHECK ((kind = 'SYSTEM') = (owner_id IS NULL)),
    -- No overdraft; maximum customer balance Rs 1,00,00,000.00; the settlement account can never be positive.
    ADD CONSTRAINT ck_accounts_balance_range CHECK (
        (kind = 'CUSTOMER' AND balance_minor BETWEEN 0 AND 1000000000)
        OR (kind = 'SYSTEM' AND balance_minor <= 0)),
    ADD CONSTRAINT ck_accounts_last_seq CHECK (last_seq >= 0);

-- The single simulated settlement account. Fixed id, referenced by the posting function.
INSERT INTO public.accounts (id, owner_id, name, kind)
VALUES ('a0000000-0000-4000-8000-000000000001', NULL, 'Settlement cash (simulated)', 'SYSTEM');

-- ---------------------------------------------------------------------------------------------------------------
-- 2. Ledger tables (all append-only, see section 4)
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE public.idempotency_keys (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id      uuid        NOT NULL,
    idem_key     varchar(100) NOT NULL,
    operation    varchar(12) NOT NULL,
    request_hash bytea       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (id),
    CONSTRAINT fk_idempotency_keys_user FOREIGN KEY (user_id) REFERENCES public.users (id),
    CONSTRAINT uq_idempotency_keys_user_key UNIQUE (user_id, idem_key),
    CONSTRAINT ck_idempotency_keys_key CHECK (idem_key ~ '^[!-~]{1,100}$'),
    CONSTRAINT ck_idempotency_keys_operation CHECK (operation IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    CONSTRAINT ck_idempotency_keys_hash CHECK (octet_length(request_hash) = 32)
);

-- Successful postings only. A refusal never creates a journal transaction.
CREATE TABLE public.journal_transactions (
    id                 uuid         NOT NULL DEFAULT gen_random_uuid(),
    kind               varchar(12)  NOT NULL,
    amount_minor       bigint       NOT NULL,
    currency           varchar(3)   NOT NULL DEFAULT 'INR',
    initiated_by       uuid         NOT NULL,
    idempotency_key_id uuid         NOT NULL,
    description        varchar(140),
    posted_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_journal_transactions PRIMARY KEY (id),
    CONSTRAINT fk_journal_initiator FOREIGN KEY (initiated_by) REFERENCES public.users (id),
    CONSTRAINT fk_journal_key FOREIGN KEY (idempotency_key_id) REFERENCES public.idempotency_keys (id),
    CONSTRAINT uq_journal_key UNIQUE (idempotency_key_id),
    CONSTRAINT ck_journal_kind CHECK (kind IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    -- Maximum single operation Rs 10,00,000.00.
    CONSTRAINT ck_journal_amount CHECK (amount_minor BETWEEN 1 AND 100000000),
    CONSTRAINT ck_journal_currency CHECK (currency = 'INR')
);

CREATE TABLE public.ledger_entries (
    id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
    transaction_id      uuid        NOT NULL,
    account_id          uuid        NOT NULL,
    direction           varchar(6)  NOT NULL,
    amount_minor        bigint      NOT NULL,
    balance_after_minor bigint      NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_ledger_entries PRIMARY KEY (id),
    CONSTRAINT fk_ledger_entries_transaction FOREIGN KEY (transaction_id) REFERENCES public.journal_transactions (id),
    CONSTRAINT fk_ledger_entries_account FOREIGN KEY (account_id) REFERENCES public.accounts (id),
    CONSTRAINT ck_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entries_amount CHECK (amount_minor > 0),
    -- At most one debit and one credit per journal transaction.
    CONSTRAINT uq_ledger_entries_transaction_direction UNIQUE (transaction_id, direction)
);
CREATE INDEX ix_ledger_entries_account ON public.ledger_entries (account_id);

CREATE TABLE public.refused_attempts (
    id                       uuid        NOT NULL DEFAULT gen_random_uuid(),
    kind                     varchar(12) NOT NULL,
    account_id               uuid        NOT NULL,
    -- The settlement account, or the destination the caller asked for (which may not exist: no foreign key).
    counterparty_account_id  uuid,
    amount_minor             bigint      NOT NULL,
    reason                   varchar(30) NOT NULL,
    balance_at_attempt_minor bigint      NOT NULL,
    initiated_by             uuid        NOT NULL,
    idempotency_key_id       uuid        NOT NULL,
    description              varchar(140),
    attempted_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_refused_attempts PRIMARY KEY (id),
    CONSTRAINT fk_refused_account FOREIGN KEY (account_id) REFERENCES public.accounts (id),
    CONSTRAINT fk_refused_initiator FOREIGN KEY (initiated_by) REFERENCES public.users (id),
    CONSTRAINT fk_refused_key FOREIGN KEY (idempotency_key_id) REFERENCES public.idempotency_keys (id),
    CONSTRAINT uq_refused_key UNIQUE (idempotency_key_id),
    CONSTRAINT ck_refused_kind CHECK (kind IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    CONSTRAINT ck_refused_amount CHECK (amount_minor BETWEEN 1 AND 99999999999),
    CONSTRAINT ck_refused_reason CHECK (reason IN ('INSUFFICIENT_FUNDS', 'ACCOUNT_NOT_ACTIVE',
        'DESTINATION_UNAVAILABLE', 'AMOUNT_LIMIT_EXCEEDED', 'BALANCE_LIMIT_EXCEEDED'))
);

-- One shared numbering for every statement line of an account, posted or refused.
CREATE TABLE public.account_statement_lines (
    account_id         uuid   NOT NULL,
    seq                bigint NOT NULL,
    ledger_entry_id    uuid,
    refused_attempt_id uuid,
    CONSTRAINT pk_account_statement_lines PRIMARY KEY (account_id, seq),
    CONSTRAINT fk_lines_account FOREIGN KEY (account_id) REFERENCES public.accounts (id),
    CONSTRAINT fk_lines_entry FOREIGN KEY (ledger_entry_id) REFERENCES public.ledger_entries (id),
    CONSTRAINT fk_lines_refusal FOREIGN KEY (refused_attempt_id) REFERENCES public.refused_attempts (id),
    CONSTRAINT uq_lines_entry UNIQUE (ledger_entry_id),
    CONSTRAINT uq_lines_refusal UNIQUE (refused_attempt_id),
    CONSTRAINT ck_lines_seq CHECK (seq >= 1),
    CONSTRAINT ck_lines_exactly_one CHECK (num_nonnulls(ledger_entry_id, refused_attempt_id) = 1)
);
-- Finds an account's previous posted line quickly (balance chain), skipping refused lines.
CREATE INDEX ix_lines_entries ON public.account_statement_lines (account_id, seq) WHERE ledger_entry_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------------------
-- 3. Invariant checks (run by deferred constraint triggers at COMMIT; a failure rolls the whole transaction back)
-- ---------------------------------------------------------------------------------------------------------------

-- Balance of an account just before statement line p_seq: the balance_after of its latest earlier POSTED line,
-- or 0 if it has none. Refused lines are skipped: they never change a balance.
CREATE FUNCTION public.ledger_balance_before(p_account uuid, p_seq bigint) RETURNS bigint
    LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT coalesce((
        SELECT e.balance_after_minor
        FROM public.account_statement_lines l
        JOIN public.ledger_entries e ON e.id = l.ledger_entry_id
        WHERE l.account_id = p_account AND l.seq < p_seq AND l.ledger_entry_id IS NOT NULL
        ORDER BY l.seq DESC LIMIT 1), 0)
$$;

-- Exactly one outcome (a journal transaction OR a refused attempt) per idempotency key.
CREATE FUNCTION public.ledger_check_key_outcome(p_key uuid) RETURNS void
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_outcomes int;
BEGIN
    SELECT (SELECT count(*) FROM public.journal_transactions j WHERE j.idempotency_key_id = p_key)
         + (SELECT count(*) FROM public.refused_attempts r WHERE r.idempotency_key_id = p_key)
      INTO v_outcomes;
    IF v_outcomes <> 1 THEN
        RAISE EXCEPTION 'ledger invariant: idempotency key % has % outcomes (expected exactly 1)', p_key, v_outcomes
            USING ERRCODE = 'check_violation';
    END IF;
END $$;

-- A journal transaction: exactly one DEBIT and one CREDIT, both equal to the journal amount, on two different
-- accounts whose kinds match the operation, and exactly one outcome for its idempotency key.
CREATE FUNCTION public.ledger_check_journal(p_txn uuid) RETURNS void
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
    PERFORM public.ledger_check_key_outcome(v_journal.idempotency_key_id);
END $$;

-- A ledger entry: exactly one statement line on its own account, the balance chain
-- (balance_after = previous posted balance +/- amount) and, if it is the account's latest posted line, equality
-- with accounts.balance_minor. Also re-checks its journal (catches entries added to an existing journal).
CREATE FUNCTION public.ledger_check_entry(p_entry uuid) RETURNS void
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_entry   public.ledger_entries%ROWTYPE;
    v_line    public.account_statement_lines%ROWTYPE;
    v_prev    bigint;
    v_signed  bigint;
    v_balance bigint;
BEGIN
    SELECT * INTO v_entry FROM public.ledger_entries e WHERE e.id = p_entry;
    SELECT * INTO v_line FROM public.account_statement_lines l WHERE l.ledger_entry_id = p_entry;
    IF NOT FOUND OR v_line.account_id <> v_entry.account_id THEN
        RAISE EXCEPTION 'ledger invariant: entry % needs exactly one statement line on its own account', p_entry
            USING ERRCODE = 'check_violation';
    END IF;

    v_prev := public.ledger_balance_before(v_entry.account_id, v_line.seq);
    v_signed := CASE v_entry.direction WHEN 'CREDIT' THEN v_entry.amount_minor ELSE -v_entry.amount_minor END;
    IF v_entry.balance_after_minor <> v_prev + v_signed THEN
        RAISE EXCEPTION 'ledger invariant: entry % balance_after % <> previous balance % plus change %',
            p_entry, v_entry.balance_after_minor, v_prev, v_signed USING ERRCODE = 'check_violation';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM public.account_statement_lines l
                    WHERE l.account_id = v_entry.account_id AND l.seq > v_line.seq AND l.ledger_entry_id IS NOT NULL) THEN
        SELECT a.balance_minor INTO v_balance FROM public.accounts a WHERE a.id = v_entry.account_id;
        IF v_balance <> v_entry.balance_after_minor THEN
            RAISE EXCEPTION 'ledger invariant: account % balance % <> latest entry balance %',
                v_entry.account_id, v_balance, v_entry.balance_after_minor USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    PERFORM public.ledger_check_journal(v_entry.transaction_id);
END $$;

-- A refused attempt: exactly one statement line on its own account, the balance it reports equals the balance at
-- that point (it changed nothing), and exactly one outcome for its key.
CREATE FUNCTION public.ledger_check_refusal(p_refusal uuid) RETURNS void
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_refusal public.refused_attempts%ROWTYPE;
    v_line    public.account_statement_lines%ROWTYPE;
BEGIN
    SELECT * INTO v_refusal FROM public.refused_attempts r WHERE r.id = p_refusal;
    SELECT * INTO v_line FROM public.account_statement_lines l WHERE l.refused_attempt_id = p_refusal;
    IF NOT FOUND OR v_line.account_id <> v_refusal.account_id THEN
        RAISE EXCEPTION 'ledger invariant: refused attempt % needs exactly one statement line on its own account',
            p_refusal USING ERRCODE = 'check_violation';
    END IF;
    IF v_refusal.balance_at_attempt_minor <> public.ledger_balance_before(v_refusal.account_id, v_line.seq) THEN
        RAISE EXCEPTION 'ledger invariant: refused attempt % reports a balance that is not the account balance',
            p_refusal USING ERRCODE = 'check_violation';
    END IF;
    PERFORM public.ledger_check_key_outcome(v_refusal.idempotency_key_id);
END $$;

-- An account whose balance or counter changed: balance = latest posted balance (or 0), last_seq = highest line.
CREATE FUNCTION public.ledger_check_account(p_account uuid) RETURNS void
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_account  public.accounts%ROWTYPE;
    v_max_seq  bigint;
BEGIN
    SELECT * INTO v_account FROM public.accounts a WHERE a.id = p_account;
    SELECT coalesce(max(l.seq), 0) INTO v_max_seq FROM public.account_statement_lines l WHERE l.account_id = p_account;
    IF v_account.last_seq <> v_max_seq THEN
        RAISE EXCEPTION 'ledger invariant: account % last_seq % <> highest statement line %',
            p_account, v_account.last_seq, v_max_seq USING ERRCODE = 'check_violation';
    END IF;
    IF v_account.balance_minor <> public.ledger_balance_before(p_account, v_max_seq + 1) THEN
        RAISE EXCEPTION 'ledger invariant: account % balance does not match its latest ledger entry', p_account
            USING ERRCODE = 'check_violation';
    END IF;
END $$;

CREATE FUNCTION public.ledger_trg_journal() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM public.ledger_check_journal(NEW.id);
    RETURN NULL;
END $$;

CREATE FUNCTION public.ledger_trg_entry() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM public.ledger_check_entry(NEW.id);
    RETURN NULL;
END $$;

CREATE FUNCTION public.ledger_trg_refusal() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM public.ledger_check_refusal(NEW.id);
    RETURN NULL;
END $$;

CREATE FUNCTION public.ledger_trg_key() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM public.ledger_check_key_outcome(NEW.id);
    RETURN NULL;
END $$;

CREATE FUNCTION public.ledger_trg_account() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM public.ledger_check_account(NEW.id);
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER journal_valid AFTER INSERT ON public.journal_transactions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.ledger_trg_journal();
CREATE CONSTRAINT TRIGGER entry_valid AFTER INSERT ON public.ledger_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.ledger_trg_entry();
CREATE CONSTRAINT TRIGGER refusal_valid AFTER INSERT ON public.refused_attempts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.ledger_trg_refusal();
CREATE CONSTRAINT TRIGGER key_outcome AFTER INSERT ON public.idempotency_keys
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.ledger_trg_key();
CREATE CONSTRAINT TRIGGER account_consistent AFTER UPDATE OF balance_minor, last_seq ON public.accounts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.ledger_trg_account();

-- Immediate: statement lines of an account are numbered 1, 2, 3, ... with no gaps.
CREATE FUNCTION public.ledger_trg_line_contiguous() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.seq <> 1 AND NOT EXISTS (SELECT 1 FROM public.account_statement_lines l
                                     WHERE l.account_id = NEW.account_id AND l.seq = NEW.seq - 1) THEN
        RAISE EXCEPTION 'ledger invariant: statement line % of account % leaves a gap', NEW.seq, NEW.account_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER line_contiguous AFTER INSERT ON public.account_statement_lines
    FOR EACH ROW EXECUTE FUNCTION public.ledger_trg_line_contiguous();

-- Immediate: a new account starts empty; identity columns of an account never change.
CREATE FUNCTION public.ledger_trg_account_guard() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.balance_minor <> 0 OR NEW.last_seq <> 0 THEN
            RAISE EXCEPTION 'a new account must start with balance 0 and no statement lines'
                USING ERRCODE = 'check_violation';
        END IF;
    ELSIF NEW.id IS DISTINCT FROM OLD.id OR NEW.owner_id IS DISTINCT FROM OLD.owner_id
          OR NEW.kind IS DISTINCT FROM OLD.kind OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'account id, owner, kind and creation time cannot change' USING ERRCODE = 'LL900';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER account_guard BEFORE INSERT OR UPDATE ON public.accounts
    FOR EACH ROW EXECUTE FUNCTION public.ledger_trg_account_guard();

-- ---------------------------------------------------------------------------------------------------------------
-- 4. Append-only: nobody (including the owner) may update, delete or truncate ledger rows
-- ---------------------------------------------------------------------------------------------------------------
CREATE FUNCTION public.ledger_trg_append_only() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP USING ERRCODE = 'LL900';
END $$;

CREATE TRIGGER idempotency_keys_append_only BEFORE UPDATE OR DELETE ON public.idempotency_keys
    FOR EACH ROW EXECUTE FUNCTION public.ledger_trg_append_only();
CREATE TRIGGER idempotency_keys_no_truncate BEFORE TRUNCATE ON public.idempotency_keys
    FOR EACH STATEMENT EXECUTE FUNCTION public.ledger_trg_append_only();
CREATE TRIGGER journal_transactions_append_only BEFORE UPDATE OR DELETE ON public.journal_transactions
    FOR EACH ROW EXECUTE FUNCTION public.ledger_trg_append_only();
CREATE TRIGGER journal_transactions_no_truncate BEFORE TRUNCATE ON public.journal_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION public.ledger_trg_append_only();
CREATE TRIGGER ledger_entries_append_only BEFORE UPDATE OR DELETE ON public.ledger_entries
    FOR EACH ROW EXECUTE FUNCTION public.ledger_trg_append_only();
CREATE TRIGGER ledger_entries_no_truncate BEFORE TRUNCATE ON public.ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION public.ledger_trg_append_only();
CREATE TRIGGER refused_attempts_append_only BEFORE UPDATE OR DELETE ON public.refused_attempts
    FOR EACH ROW EXECUTE FUNCTION public.ledger_trg_append_only();
CREATE TRIGGER refused_attempts_no_truncate BEFORE TRUNCATE ON public.refused_attempts
    FOR EACH STATEMENT EXECUTE FUNCTION public.ledger_trg_append_only();
CREATE TRIGGER account_statement_lines_append_only BEFORE UPDATE OR DELETE ON public.account_statement_lines
    FOR EACH ROW EXECUTE FUNCTION public.ledger_trg_append_only();
CREATE TRIGGER account_statement_lines_no_truncate BEFORE TRUNCATE ON public.account_statement_lines
    FOR EACH STATEMENT EXECUTE FUNCTION public.ledger_trg_append_only();

-- ---------------------------------------------------------------------------------------------------------------
-- 5. Posting
-- ---------------------------------------------------------------------------------------------------------------

-- The stored outcome of an idempotency key, from the immutable rows (so a replay is identical to the original).
-- "Account" is the caller's account: credited for a deposit, debited for a withdrawal or transfer.
CREATE FUNCTION public.ledger_outcome(p_key uuid, p_replayed boolean)
    RETURNS TABLE (r_outcome text, r_replayed boolean, r_kind text, r_journal_id uuid, r_refused_id uuid,
                   r_reason text, r_account_id uuid, r_counterparty_account_id uuid, r_amount_minor bigint,
                   r_balance_minor bigint, r_seq bigint, r_occurred_at timestamptz, r_initiated_by uuid)
    LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT 'POSTED'::text, p_replayed, j.kind::text, j.id, NULL::uuid, NULL::text, mine.account_id,
           other.account_id, j.amount_minor, mine.balance_after_minor, l.seq, j.posted_at, j.initiated_by
      FROM public.journal_transactions j
      JOIN public.ledger_entries mine
        ON mine.transaction_id = j.id
       AND mine.direction = CASE j.kind WHEN 'DEPOSIT' THEN 'CREDIT' ELSE 'DEBIT' END
      JOIN public.ledger_entries other ON other.transaction_id = j.id AND other.id <> mine.id
      JOIN public.account_statement_lines l ON l.ledger_entry_id = mine.id
     WHERE j.idempotency_key_id = p_key
    UNION ALL
    SELECT 'REFUSED'::text, p_replayed, r.kind::text, NULL::uuid, r.id, r.reason::text, r.account_id,
           r.counterparty_account_id, r.amount_minor, r.balance_at_attempt_minor, l.seq, r.attempted_at,
           r.initiated_by
      FROM public.refused_attempts r
      JOIN public.account_statement_lines l ON l.refused_attempt_id = r.id
     WHERE r.idempotency_key_id = p_key
$$;

-- The single posting routine. One call = one database transaction (the caller's).
--
-- Error codes: LL400 invalid input, LL401 initiator unknown or disabled, LL403 initiator not an account holder or
-- not the owner of the account, LL409 idempotency key reused with a different request, 55P03 lock timeout.
-- Any error rolls back everything, including the idempotency key.
CREATE FUNCTION public.ledger_post(p_kind text, p_initiator uuid, p_account uuid, p_counterparty uuid,
                                   p_amount bigint, p_description text, p_key text)
    RETURNS TABLE (r_outcome text, r_replayed boolean, r_kind text, r_journal_id uuid, r_refused_id uuid,
                   r_reason text, r_account_id uuid, r_counterparty_account_id uuid, r_amount_minor bigint,
                   r_balance_minor bigint, r_seq bigint, r_occurred_at timestamptz, r_initiated_by uuid)
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    c_settlement  CONSTANT uuid   := 'a0000000-0000-4000-8000-000000000001';
    c_max_amount  CONSTANT bigint := 100000000;      -- Rs 10,00,000.00
    c_max_balance CONSTANT bigint := 1000000000;     -- Rs 1,00,00,000.00
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
BEGIN
    PERFORM set_config('lock_timeout', '3s', true);

    -- Input. The API validates all of this first; reaching here with bad input is a programming error.
    IF p_kind IS NULL OR p_kind NOT IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')
       OR p_initiator IS NULL OR p_account IS NULL
       OR p_amount IS NULL OR p_amount < 1 OR p_amount > 99999999999
       OR p_key IS NULL OR p_key !~ '^[!-~]{1,100}$'
       OR (p_description IS NOT NULL AND (char_length(p_description) > 140 OR p_description ~ '[[:cntrl:]]'))
       OR ((p_kind = 'TRANSFER') <> (p_counterparty IS NOT NULL))
       OR p_counterparty = p_account THEN
        RAISE EXCEPTION 'invalid posting request' USING ERRCODE = 'LL400';
    END IF;

    -- The initiator id is supplied by the application (from the authenticated principal). This function can only
    -- check that THIS id may perform the operation; it cannot prove the id belongs to the HTTP caller.
    SELECT * INTO v_user FROM public.users u WHERE u.id = p_initiator;
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
        RETURN QUERY SELECT * FROM public.ledger_outcome(v_existing.id, true);
        RETURN;
    END IF;

    -- Lock every affected account in ascending id order (prevents deadlocks between opposite transfers).
    v_counterparty := CASE WHEN p_kind = 'TRANSFER' THEN p_counterparty ELSE c_settlement END;
    FOR v_lock_id IN SELECT x FROM unnest(ARRAY[p_account, v_counterparty]) AS x ORDER BY x LOOP
        PERFORM 1 FROM public.accounts a WHERE a.id = v_lock_id FOR UPDATE;
    END LOOP;

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
                                             balance_at_attempt_minor, initiated_by, idempotency_key_id, description)
        VALUES (p_kind, p_account, v_counterparty, p_amount, v_reason, v_account.balance_minor, p_initiator,
                v_key_id, p_description)
        RETURNING id INTO v_refused_id;
        INSERT INTO public.account_statement_lines (account_id, seq, refused_attempt_id)
        VALUES (p_account, v_seq, v_refused_id);
    ELSE
        IF p_kind = 'DEPOSIT' THEN
            v_debit := c_settlement;  v_credit := p_account;
        ELSE
            v_debit := p_account;     v_credit := v_counterparty;
        END IF;

        INSERT INTO public.journal_transactions (kind, amount_minor, initiated_by, idempotency_key_id, description)
        VALUES (p_kind, p_amount, p_initiator, v_key_id, p_description)
        RETURNING id INTO v_journal_id;

        -- Debit side: balance and counter updated under the row lock, entry records the balance after.
        UPDATE public.accounts a SET balance_minor = a.balance_minor - p_amount, last_seq = a.last_seq + 1
         WHERE a.id = v_debit RETURNING a.balance_minor, a.last_seq INTO v_balance, v_seq;
        INSERT INTO public.ledger_entries (transaction_id, account_id, direction, amount_minor, balance_after_minor)
        VALUES (v_journal_id, v_debit, 'DEBIT', p_amount, v_balance) RETURNING id INTO v_entry_id;
        INSERT INTO public.account_statement_lines (account_id, seq, ledger_entry_id) VALUES (v_debit, v_seq, v_entry_id);

        -- Credit side.
        UPDATE public.accounts a SET balance_minor = a.balance_minor + p_amount, last_seq = a.last_seq + 1
         WHERE a.id = v_credit RETURNING a.balance_minor, a.last_seq INTO v_balance, v_seq;
        INSERT INTO public.ledger_entries (transaction_id, account_id, direction, amount_minor, balance_after_minor)
        VALUES (v_journal_id, v_credit, 'CREDIT', p_amount, v_balance) RETURNING id INTO v_entry_id;
        INSERT INTO public.account_statement_lines (account_id, seq, ledger_entry_id) VALUES (v_credit, v_seq, v_entry_id);
    END IF;

    -- Run the pending deferred invariant checks now, so a violation surfaces as an error from this call. Then restore
    -- deferral: SET CONSTRAINTS lasts until the end of the transaction, and a later posting in the same transaction
    -- must again be checked only once complete.
    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;

    RETURN QUERY SELECT * FROM public.ledger_outcome(v_key_id, false);
END $$;

CREATE FUNCTION public.ledger_deposit(p_initiator uuid, p_account uuid, p_amount_minor bigint, p_description text,
                                      p_idempotency_key text)
    RETURNS TABLE (r_outcome text, r_replayed boolean, r_kind text, r_journal_id uuid, r_refused_id uuid,
                   r_reason text, r_account_id uuid, r_counterparty_account_id uuid, r_amount_minor bigint,
                   r_balance_minor bigint, r_seq bigint, r_occurred_at timestamptz, r_initiated_by uuid)
    LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT * FROM public.ledger_post('DEPOSIT', p_initiator, p_account, NULL, p_amount_minor, p_description,
                                     p_idempotency_key)
$$;

CREATE FUNCTION public.ledger_withdraw(p_initiator uuid, p_account uuid, p_amount_minor bigint, p_description text,
                                       p_idempotency_key text)
    RETURNS TABLE (r_outcome text, r_replayed boolean, r_kind text, r_journal_id uuid, r_refused_id uuid,
                   r_reason text, r_account_id uuid, r_counterparty_account_id uuid, r_amount_minor bigint,
                   r_balance_minor bigint, r_seq bigint, r_occurred_at timestamptz, r_initiated_by uuid)
    LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT * FROM public.ledger_post('WITHDRAWAL', p_initiator, p_account, NULL, p_amount_minor, p_description,
                                     p_idempotency_key)
$$;

CREATE FUNCTION public.ledger_transfer(p_initiator uuid, p_from_account uuid, p_to_account uuid, p_amount_minor bigint,
                                       p_description text, p_idempotency_key text)
    RETURNS TABLE (r_outcome text, r_replayed boolean, r_kind text, r_journal_id uuid, r_refused_id uuid,
                   r_reason text, r_account_id uuid, r_counterparty_account_id uuid, r_amount_minor bigint,
                   r_balance_minor bigint, r_seq bigint, r_occurred_at timestamptz, r_initiated_by uuid)
    LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT * FROM public.ledger_post('TRANSFER', p_initiator, p_from_account, p_to_account, p_amount_minor,
                                     p_description, p_idempotency_key)
$$;

-- ---------------------------------------------------------------------------------------------------------------
-- 6. Reconciliation: recomputes everything from the raw rows (never trusts stored running balances).
--    Returns one row per difference found; no rows means the books reconcile. Runs with the caller's rights.
-- ---------------------------------------------------------------------------------------------------------------
CREATE FUNCTION public.ledger_reconciliation()
    RETURNS TABLE (check_name text, subject_id uuid, expected bigint, actual bigint)
    LANGUAGE sql STABLE SECURITY INVOKER SET search_path = pg_catalog, pg_temp AS $$
    WITH signed AS (
        SELECT e.account_id, CASE e.direction WHEN 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END AS delta
          FROM public.ledger_entries e
    ), per_account AS (
        SELECT a.id, a.balance_minor, a.last_seq,
               coalesce((SELECT sum(s.delta) FROM signed s WHERE s.account_id = a.id), 0) AS computed,
               (SELECT count(*) FROM public.account_statement_lines l WHERE l.account_id = a.id) AS line_count,
               coalesce((SELECT max(l.seq) FROM public.account_statement_lines l WHERE l.account_id = a.id), 0) AS max_seq
          FROM public.accounts a
    ), lines AS (
        SELECT l.account_id, l.seq, e.id AS entry_id, r.id AS refusal_id, e.balance_after_minor,
               r.balance_at_attempt_minor,
               CASE WHEN e.id IS NULL THEN 0 WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END AS delta
          FROM public.account_statement_lines l
          LEFT JOIN public.ledger_entries e ON e.id = l.ledger_entry_id
          LEFT JOIN public.refused_attempts r ON r.id = l.refused_attempt_id
    ), running AS (
        SELECT lines.*, sum(delta) OVER (PARTITION BY account_id ORDER BY seq) AS running_after,
               sum(delta) OVER (PARTITION BY account_id ORDER BY seq) - delta AS running_before
          FROM lines
    ), journals AS (
        SELECT j.id, j.amount_minor,
               count(e.id) AS entries,
               coalesce(sum(e.amount_minor) FILTER (WHERE e.direction = 'DEBIT'), 0) AS debits,
               coalesce(sum(e.amount_minor) FILTER (WHERE e.direction = 'CREDIT'), 0) AS credits
          FROM public.journal_transactions j
          LEFT JOIN public.ledger_entries e ON e.transaction_id = j.id
         GROUP BY j.id, j.amount_minor
    )
    SELECT 'ACCOUNT_BALANCE', id, computed, balance_minor FROM per_account WHERE computed <> balance_minor
    UNION ALL
    SELECT 'ACCOUNT_SEQUENCE', id, line_count, last_seq FROM per_account WHERE last_seq <> line_count OR last_seq <> max_seq
    UNION ALL
    SELECT 'RUNNING_BALANCE', entry_id, running_after, balance_after_minor FROM running
     WHERE entry_id IS NOT NULL AND balance_after_minor <> running_after
    UNION ALL
    SELECT 'REFUSED_BALANCE', refusal_id, running_before, balance_at_attempt_minor FROM running
     WHERE refusal_id IS NOT NULL AND balance_at_attempt_minor <> running_before
    UNION ALL
    SELECT 'JOURNAL_BALANCE', id, debits, credits FROM journals
     WHERE entries <> 2 OR debits <> credits OR debits <> amount_minor
    UNION ALL
    SELECT 'ENTRY_WITHOUT_LINE', e.id, 1, 0 FROM public.ledger_entries e
     WHERE NOT EXISTS (SELECT 1 FROM public.account_statement_lines l WHERE l.ledger_entry_id = e.id)
    UNION ALL
    SELECT 'REFUSAL_WITHOUT_LINE', r.id, 1, 0 FROM public.refused_attempts r
     WHERE NOT EXISTS (SELECT 1 FROM public.account_statement_lines l WHERE l.refused_attempt_id = r.id)
    UNION ALL
    SELECT 'TOTAL_BALANCE', NULL::uuid, 0, total FROM (SELECT coalesce(sum(a.balance_minor), 0) AS total FROM public.accounts a) t
     WHERE total <> 0
$$;

-- ---------------------------------------------------------------------------------------------------------------
-- 7. Privileges
-- ---------------------------------------------------------------------------------------------------------------
REVOKE ALL ON TABLE public.idempotency_keys, public.journal_transactions, public.ledger_entries,
    public.refused_attempts, public.account_statement_lines FROM PUBLIC;

-- New functions are executable by PUBLIC by default: revoke that for every function created here.
REVOKE ALL ON FUNCTION
    public.ledger_balance_before(uuid, bigint),
    public.ledger_check_key_outcome(uuid),
    public.ledger_check_journal(uuid),
    public.ledger_check_entry(uuid),
    public.ledger_check_refusal(uuid),
    public.ledger_check_account(uuid),
    public.ledger_trg_journal(),
    public.ledger_trg_entry(),
    public.ledger_trg_refusal(),
    public.ledger_trg_key(),
    public.ledger_trg_account(),
    public.ledger_trg_line_contiguous(),
    public.ledger_trg_account_guard(),
    public.ledger_trg_append_only(),
    public.ledger_outcome(uuid, boolean),
    public.ledger_post(text, uuid, uuid, uuid, bigint, text, text),
    public.ledger_deposit(uuid, uuid, bigint, text, text),
    public.ledger_withdraw(uuid, uuid, bigint, text, text),
    public.ledger_transfer(uuid, uuid, uuid, bigint, text, text),
    public.ledger_reconciliation()
FROM PUBLIC;

DO $$
DECLARE
    app_role CONSTANT text := '${app_role}';
BEGIN
    IF app_role !~ '^[a-z_][a-z0-9_]{0,62}$' OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role) THEN
        RAISE EXCEPTION 'app_role placeholder is not an existing plain lower-case role name';
    END IF;
    -- Read-only on the ledger (statements and reconciliation). No privilege at all on idempotency_keys.
    -- No INSERT, UPDATE, DELETE or TRUNCATE anywhere; accounts keep exactly their V4 privileges.
    EXECUTE format('GRANT SELECT ON TABLE public.journal_transactions, public.ledger_entries, '
                   'public.refused_attempts, public.account_statement_lines TO %I', app_role);
    -- The only write path, plus read-only reconciliation.
    EXECUTE format('GRANT EXECUTE ON FUNCTION public.ledger_deposit(uuid, uuid, bigint, text, text), '
                   'public.ledger_withdraw(uuid, uuid, bigint, text, text), '
                   'public.ledger_transfer(uuid, uuid, uuid, bigint, text, text), '
                   'public.ledger_reconciliation() TO %I', app_role);
END $$;
