package com.ledgerline;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Starts the whole application against ledgerline_test_db. Needs PostgreSQL. The database guard is applied
 * automatically (see TestDatabaseGuard), so no import is needed.
 */
@SpringBootTest
class LedgerlineBackendApplicationIT {

    @Test
    void contextLoads() {
    }
}
