package com.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ledgerline.config.JwtProperties;
import com.ledgerline.config.JwtTestSupport;
import com.ledgerline.support.OwnerJdbc;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Phase 4 authorization end to end: real HTTP layer, real security configuration, real PostgreSQL
 * (ledgerline_test_db). Users register through the API; roles and disabling are done as the database owner,
 * exactly as in production (there is no API for either). Every test creates its own users.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountAuthorizationIT {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate appJdbc;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private Environment environment;

    private JdbcTemplate ownerJdbc;

    record TestUser(UUID id, String email, String token) {
    }

    @BeforeEach
    void connectAsOwner() {
        ownerJdbc = OwnerJdbc.forTestDatabase(environment);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private TestUser user(String role) throws Exception {
        String email = "acct-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Account Tester\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated());
        if (!"ACCOUNT_HOLDER".equals(role)) {
            setRole(email, role);
        }
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id = appJdbc.queryForObject("select id from users where email = ?", UUID.class, email);
        return new TestUser(id, email, JsonPath.read(body, "$.accessToken"));
    }

    private void setRole(String email, String role) {
        assertThat(ownerJdbc.update("update users set role = ? where email = ?", role, email)).isEqualTo(1);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, TestUser as) throws Exception {
        return mvc.perform(as == null ? request : request.header("Authorization", "Bearer " + as.token()));
    }

    private ResultActions createAccount(TestUser as, String json) throws Exception {
        return call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(json), as);
    }

    private UUID newAccount(TestUser owner, String name) throws Exception {
        String body = createAccount(owner, "{\"name\":\"" + name + "\"}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private int accountsOwnedBy(UUID ownerId) {
        return appJdbc.queryForObject("select count(*) from accounts where owner_id = ?", Integer.class, ownerId);
    }

    /** The five Phase 4 endpoints with the status each caller should get. */
    private void assertAccess(TestUser as, UUID anyAccount, int create, int listOwn, int getOwn, int listAll, int getAny)
            throws Exception {
        createAccount(as, "{\"name\":\"Matrix\"}").andExpect(status().is(create));
        call(get("/api/accounts"), as).andExpect(status().is(listOwn));
        call(get("/api/accounts/" + anyAccount), as).andExpect(status().is(getOwn));
        call(get("/api/oversight/accounts"), as).andExpect(status().is(listAll));
        call(get("/api/oversight/accounts/" + anyAccount), as).andExpect(status().is(getAny));
    }

    // ---- access matrix ------------------------------------------------------------------------------------------

    @Test
    void everyRoleAgainstEveryEndpoint() throws Exception {
        TestUser holder = user("ACCOUNT_HOLDER");
        TestUser admin = user("ADMIN");
        TestUser auditor = user("AUDITOR");
        UUID holdersAccount = newAccount(holder, "Everyday");

        assertAccess(null, holdersAccount, 401, 401, 401, 401, 401);
        assertAccess(holder, holdersAccount, 201, 200, 200, 403, 403);
        assertAccess(admin, holdersAccount, 403, 403, 403, 200, 200);
        assertAccess(auditor, holdersAccount, 403, 403, 403, 200, 200);

        assertThat(accountsOwnedBy(admin.id())).as("staff created nothing").isZero();
        assertThat(accountsOwnedBy(auditor.id())).isZero();
    }

    @Test
    void anonymousGets401WithTheBearerChallenge() throws Exception {
        mvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.title").value("Authentication required"));
    }

    @Test
    void unlistedMethodsAndPathsAreDeniedByDefault() throws Exception {
        TestUser holder = user("ACCOUNT_HOLDER");
        TestUser admin = user("ADMIN");
        UUID account = newAccount(holder, "Locked down");
        String json = "{\"name\":\"changed\"}";
        // No update or delete exists for anyone; the default-deny rule refuses them before any controller.
        call(put("/api/accounts/" + account).contentType(MediaType.APPLICATION_JSON).content(json), holder).andExpect(status().isForbidden());
        call(patch("/api/accounts/" + account).contentType(MediaType.APPLICATION_JSON).content(json), holder).andExpect(status().isForbidden());
        call(delete("/api/accounts/" + account), holder).andExpect(status().isForbidden());
        call(post("/api/oversight/accounts").contentType(MediaType.APPLICATION_JSON).content(json), admin).andExpect(status().isForbidden());
        call(delete("/api/oversight/accounts/" + account), admin).andExpect(status().isForbidden());
        call(get("/api/admin/users"), admin).andExpect(status().isForbidden());
        call(get("/api/does-not-exist"), holder).andExpect(status().isForbidden());
        call(delete("/api/accounts/" + account), null).andExpect(status().isUnauthorized());
        assertThat(appJdbc.queryForObject("select name from accounts where id = ?", String.class, account)).isEqualTo("Locked down");
    }

    // ---- ownership and IDOR -------------------------------------------------------------------------------------

    @Test
    void creationAlwaysUsesTheCallerAndTheDatabaseDefaults() throws Exception {
        TestUser holder = user("ACCOUNT_HOLDER");
        String body = createAccount(holder, "{\"name\":\"  Savings  \"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(holder.id().toString()))
                .andExpect(jsonPath("$.name").value("Savings"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        call(get("/api/accounts/" + id), holder).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ownerId", "role", "status", "currency", "id"})
    void injectedFieldsAreRejectedAndNothingIsCreated(String field) throws Exception {
        TestUser attacker = user("ACCOUNT_HOLDER");
        TestUser victim = user("ACCOUNT_HOLDER");
        String value = switch (field) {
            case "ownerId" -> victim.id().toString();
            case "role" -> "ADMIN";
            case "status" -> "FROZEN";
            case "currency" -> "USD";
            default -> UUID.randomUUID().toString();
        };
        createAccount(attacker, "{\"name\":\"Sneaky\",\"" + field + "\":\"" + value + "\"}").andExpect(status().isBadRequest());
        assertThat(accountsOwnedBy(attacker.id())).isZero();
        assertThat(accountsOwnedBy(victim.id())).isZero();
        call(get("/api/auth/me"), attacker).andExpect(jsonPath("$.role").value("ACCOUNT_HOLDER"));
    }

    @Test
    void anotherUsersAccountIsTheSame404AsAnAccountThatDoesNotExist() throws Exception {
        TestUser owner = user("ACCOUNT_HOLDER");
        TestUser other = user("ACCOUNT_HOLDER");
        UUID ownersAccount = newAccount(owner, "Private");
        UUID missing = UUID.randomUUID();

        String foreign = call(get("/api/accounts/" + ownersAccount), other).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String absent = call(get("/api/accounts/" + missing), other).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        // Identical apart from "instance", which is just the path the caller asked for.
        assertThat(foreign.replace(ownersAccount.toString(), "ID")).isEqualTo(absent.replace(missing.toString(), "ID"));
        assertThat(foreign).doesNotContain(owner.id().toString()).doesNotContain("Private").doesNotContain(owner.email());
    }

    @Test
    void eachHolderListsOnlyTheirOwnAccounts() throws Exception {
        TestUser a = user("ACCOUNT_HOLDER");
        TestUser b = user("ACCOUNT_HOLDER");
        UUID a1 = newAccount(a, "A one");
        UUID a2 = newAccount(a, "A two");
        UUID b1 = newAccount(b, "B one");

        String aList = call(get("/api/accounts"), a).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(aList, "$.items[*].id")).containsExactly(a1.toString(), a2.toString());
        assertThat(JsonPath.<List<String>>read(aList, "$.items[*].ownerId")).containsOnly(a.id().toString());

        String bList = call(get("/api/accounts"), b).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(bList, "$.items[*].id")).containsExactly(b1.toString());
    }

    @Test
    void ownListPagesAreStableAndCapped() throws Exception {
        TestUser holder = user("ACCOUNT_HOLDER");
        UUID first = newAccount(holder, "1");
        UUID second = newAccount(holder, "2");
        UUID third = newAccount(holder, "3");
        call(get("/api/accounts?page=0&size=2"), holder).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(first.toString()))
                .andExpect(jsonPath("$.items[1].id").value(second.toString()))
                .andExpect(jsonPath("$.totalPages").value(2));
        call(get("/api/accounts?page=1&size=2"), holder)
                .andExpect(jsonPath("$.items[0].id").value(third.toString()))
                .andExpect(jsonPath("$.items.length()").value(1));
        call(get("/api/accounts?size=101"), holder).andExpect(status().isBadRequest());
        call(get("/api/oversight/accounts?size=101"), user("AUDITOR")).andExpect(status().isBadRequest());
    }

    @Test
    void staffSeeEveryAccountReadOnly() throws Exception {
        TestUser holder = user("ACCOUNT_HOLDER");
        UUID account = newAccount(holder, "Watched");
        for (TestUser staff : List.of(user("ADMIN"), user("AUDITOR"))) {
            call(get("/api/oversight/accounts/" + account), staff).andExpect(status().isOk())
                    .andExpect(jsonPath("$.ownerId").value(holder.id().toString()))
                    .andExpect(jsonPath("$.name").value("Watched"));
            call(get("/api/oversight/accounts/" + UUID.randomUUID()), staff).andExpect(status().isNotFound());
            call(get("/api/oversight/accounts?size=100"), staff).andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(100));
        }
    }

    @Test
    void responsesNeverContainUserDetailsOrHashes() throws Exception {
        TestUser holder = user("ACCOUNT_HOLDER");
        UUID account = newAccount(holder, "Plain");
        String body = call(get("/api/oversight/accounts/" + account), user("AUDITOR")).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Map<String, Object>>read(body, "$").keySet())
                .containsExactlyInAnyOrder("id", "ownerId", "name", "currency", "status", "createdAt");
        assertThat(body).doesNotContain(holder.email()).doesNotContain("$2");
    }

    // ---- identity and role changes with existing tokens ---------------------------------------------------------

    @Test
    void aDisabledUsersTokenStopsWorkingImmediately() throws Exception {
        TestUser holder = user("ACCOUNT_HOLDER");
        UUID account = newAccount(holder, "Before");
        assertThat(ownerJdbc.update("update users set enabled = false where id = ?", holder.id())).isEqualTo(1);
        call(get("/api/accounts"), holder).andExpect(status().isUnauthorized());
        call(get("/api/accounts/" + account), holder).andExpect(status().isUnauthorized());
        createAccount(holder, "{\"name\":\"After\"}").andExpect(status().isUnauthorized());
        assertThat(accountsOwnedBy(holder.id())).isEqualTo(1);

        TestUser auditor = user("AUDITOR");
        assertThat(ownerJdbc.update("update users set enabled = false where id = ?", auditor.id())).isEqualTo(1);
        call(get("/api/oversight/accounts"), auditor).andExpect(status().isUnauthorized());
    }

    @Test
    void roleChangesApplyToTheNextRequestWithTheSameToken() throws Exception {
        TestUser user = user("ACCOUNT_HOLDER");
        call(get("/api/accounts"), user).andExpect(status().isOk());
        call(get("/api/oversight/accounts"), user).andExpect(status().isForbidden());

        setRole(user.email(), "AUDITOR");
        call(get("/api/accounts"), user).andExpect(status().isForbidden());
        call(get("/api/oversight/accounts"), user).andExpect(status().isOk());

        setRole(user.email(), "ACCOUNT_HOLDER");                 // demotion is just as immediate
        call(get("/api/oversight/accounts"), user).andExpect(status().isForbidden());
        call(get("/api/accounts"), user).andExpect(status().isOk());

        TestUser admin = user("ADMIN");
        call(get("/api/oversight/accounts"), admin).andExpect(status().isOk());
        setRole(admin.email(), "ACCOUNT_HOLDER");
        call(get("/api/oversight/accounts"), admin).andExpect(status().isForbidden());
    }

    @Test
    void roleClaimsInAValidlySignedTokenGrantNothing() throws Exception {
        // Signed with the application's real key, so the signature is valid; the extra claims must be ignored.
        TestUser holder = user("ACCOUNT_HOLDER");
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer()).subject(holder.id().toString())
                .issuedAt(now).expiresAt(now.plusSeconds(600)).id(UUID.randomUUID().toString())
                .claim("role", "ADMIN").claim("roles", List.of("ADMIN", "AUDITOR"))
                .claim("scope", "ADMIN AUDITOR").claim("authorities", List.of("ROLE_ADMIN", "ROLE_AUDITOR"))
                .build();
        String forged = JwtTestSupport.encoder(jwtProperties)
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        TestUser withClaims = new TestUser(holder.id(), holder.email(), forged);

        call(get("/api/oversight/accounts"), withClaims).andExpect(status().isForbidden());
        call(get("/api/auth/me"), withClaims).andExpect(jsonPath("$.role").value("ACCOUNT_HOLDER"));
        call(get("/api/accounts"), withClaims).andExpect(status().isOk());
    }
}
