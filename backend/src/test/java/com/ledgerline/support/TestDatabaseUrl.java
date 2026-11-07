package com.ledgerline.support;

import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a JDBC URL points at the local integration-test database, using only the URL text.
 * Nothing here opens a connection.
 *
 * Accepted: jdbc:postgresql://localhost:5432/ledgerline_test_db (host may also be 127.0.0.1 or [::1];
 * the port may be omitted, which means 5432). Everything else is rejected, including multi-host URLs,
 * user information in the host part, other ports, other database names and unknown query parameters.
 * Query parameters need an allow-list because the PostgreSQL driver lets parameters such as PGDBNAME,
 * PGHOST or PGPORT override the database, host and port written in the URL itself.
 */
public final class TestDatabaseUrl {

    public static final String DATABASE = "ledgerline_test_db";

    static final String PREFIX = "jdbc:postgresql://";
    static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    static final int PORT = 5432;
    static final Set<String> ALLOWED_PARAMETERS = Set.of("ApplicationName", "connectTimeout", "sslmode");

    private TestDatabaseUrl() {
    }

    /** Throws if the URL is not the local test database. {@code property} names the setting, for the message. */
    public static void requireLocalTestDatabase(String property, String url) {
        String problem = problemWith(url);
        if (problem != null) {
            throw new IllegalStateException("Integration tests refused to start: " + property + " " + problem
                    + ". Expected jdbc:postgresql://localhost:" + PORT + "/" + DATABASE
                    + ". No database connection was opened.");
        }
    }

    /** Returns why the URL is unacceptable, or null if it is the local test database. */
    static String problemWith(String url) {
        if (url == null || url.isBlank()) {
            return "is missing";
        }
        if (!url.startsWith(PREFIX)) {
            return "is not a jdbc:postgresql://host:port/database URL";
        }

        String rest = url.substring(PREFIX.length());
        String query = null;
        int queryStart = rest.indexOf('?');
        if (queryStart >= 0) {
            query = rest.substring(queryStart + 1);
            rest = rest.substring(0, queryStart);
        }

        int slash = rest.indexOf('/');
        if (slash < 0) {
            return "has no database name";
        }
        String authority = rest.substring(0, slash);
        String database = rest.substring(slash + 1);

        if (authority.contains(",")) {
            return "lists more than one host";
        }
        if (authority.contains("@")) {
            return "contains user information in the host part";
        }

        String host;
        String port = null;
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            if (end < 0) {
                return "has a malformed IPv6 host";
            }
            host = authority.substring(0, end + 1);
            String afterHost = authority.substring(end + 1);
            if (!afterHost.isEmpty()) {
                if (!afterHost.startsWith(":")) {
                    return "has a malformed host";
                }
                port = afterHost.substring(1);
            }
        } else {
            int colon = authority.indexOf(':');
            host = colon < 0 ? authority : authority.substring(0, colon);
            port = colon < 0 ? null : authority.substring(colon + 1);
        }

        if (!LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            return "points at host '" + host + "', not the local test server";
        }
        if (port != null) {
            if (!port.matches("\\d{1,5}")) {
                return "has a malformed port '" + port + "'";
            }
            if (Integer.parseInt(port) != PORT) {
                return "uses port " + port + ", not " + PORT;
            }
        }
        if (!database.equals(DATABASE)) {
            return "names database '" + database + "', not " + DATABASE;
        }
        if (query != null) {
            for (String parameter : query.split("&", -1)) {
                String name = parameter.split("=", 2)[0];
                if (!ALLOWED_PARAMETERS.contains(name)) {
                    return "has query parameter '" + name + "', which is not on the allow-list";
                }
            }
        }
        return null;
    }
}
