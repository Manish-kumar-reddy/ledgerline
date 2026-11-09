package com.ledgerline.service;

import com.ledgerline.dto.ReconciliationResponse;
import com.ledgerline.money.Money;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only reconciliation for ADMIN and AUDITOR: runs ledger_reconciliation() (V5), which recomputes balances,
 * running balances, journal totals and sequence numbers from the raw rows, plus summary totals, in one snapshot.
 */
@Service
public class ReconciliationService {

    static final int MAX_LISTED = 100;

    private final JdbcTemplate jdbc;

    public ReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationResponse reconcile() {
        List<ReconciliationResponse.Difference> differences = jdbc.query(
                "select check_name, subject_id, expected, actual from public.ledger_reconciliation()",
                (rs, n) -> new ReconciliationResponse.Difference(rs.getString("check_name"),
                        rs.getObject("subject_id", UUID.class), rs.getLong("expected"), rs.getLong("actual")));
        Map<String, Object> totals = jdbc.queryForMap("""
                select (select count(*) from public.accounts) as accounts,
                       (select count(*) from public.journal_transactions) as journals,
                       (select count(*) from public.refused_attempts) as refused,
                       (select coalesce(sum(balance_minor), 0) from public.accounts where kind = 'CUSTOMER') as customers,
                       (select coalesce(sum(balance_minor), 0) from public.accounts where kind = 'SYSTEM') as settlement""");
        long customers = ((Number) totals.get("customers")).longValue();
        long settlement = ((Number) totals.get("settlement")).longValue();
        return new ReconciliationResponse(differences.isEmpty(), differences.size(),
                differences.subList(0, Math.min(MAX_LISTED, differences.size())),
                ((Number) totals.get("accounts")).longValue(), ((Number) totals.get("journals")).longValue(),
                ((Number) totals.get("refused")).longValue(), Money.format(customers), customers,
                Money.format(settlement), settlement, customers + settlement);
    }
}
