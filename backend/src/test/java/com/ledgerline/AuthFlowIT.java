package com.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ledgerline.config.JwtProperties;
import com.ledgerline.config.JwtTestSupport;
import com.ledgerline.security.JwtTokenService;
import com.ledgerline.support.OwnerJdbc;
import com.ledgerline.support.RandomJwtSecret;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * End-to-end authentication against ledgerline_test_db: real HTTP layer, real security, real PostgreSQL.
 * Every test uses its own random email, because the application role cannot delete users.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIT {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate appJdbc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private Environment environment;

    private JdbcTemplate ownerJdbc;

    @BeforeEach
    void connectAsOwner() {
        ownerJdbc = OwnerJdbc.forTestDatabase(environment);
    }

    private static String uniqueEmail() {
        return "it-" + UUID.randomUUID() + "@example.test";
    }

    private ResultActions register(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Integration Tester\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private String loginToken(String email) throws Exception {
        String body = login(email, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private ResultActions me(String token) throws Exception {
        return mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token));
    }

    private UUID userId(String email) {
        return appJdbc.queryForObject("select id from users where email = ?", UUID.class, email);
    }

    @Test
    void registerLoginAndMe() throws Exception {
        String email = uniqueEmail();
        String registered = register(email, PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/auth/me"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("ACCOUNT_HOLDER"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(registered).doesNotContain(PASSWORD).doesNotContainIgnoringCase("password").doesNotContain("$2a$");

        String token = loginToken(email);
        me(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId(email).toString()))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("ACCOUNT_HOLDER"));
    }

    @Test
    void passwordIsStoredAsABcryptCost12HashOnly() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());

        String hash = appJdbc.queryForObject("select password_hash from users where email = ?", String.class, email);
        assertThat(hash).startsWith("$2a$12$").hasSize(60).isNotEqualTo(PASSWORD).doesNotContain(PASSWORD);
        assertThat(new BCryptPasswordEncoder().matches(PASSWORD, hash)).isTrue();
    }

    @Test
    void emailIsNormalisedAndDuplicatesIgnoringCaseAreRejectedWith409() throws Exception {
        String email = uniqueEmail();
        register("  " + email.toUpperCase() + " ", PASSWORD).andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email));

        register(email, PASSWORD).andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Email already registered"));
        register(email.toUpperCase(), PASSWORD).andExpect(status().isConflict());
        assertThat(appJdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isEqualTo(1);

        loginToken(email.toUpperCase()); // login normalises the same way
    }

    @Test
    void concurrentRegistrationsOfOneEmailCreateExactlyOneUser() throws Exception {
        String email = uniqueEmail();
        int attempts = 3; // the test connection pool has 3 connections
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            calls.add(() -> {
                start.await();
                return register(email, PASSWORD).andReturn().getResponse().getStatus();
            });
        }
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            List<Future<Integer>> results = calls.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
        }
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(attempts - 1);
        assertThat(appJdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isEqualTo(1);
    }

    @Test
    void roleOrEnabledInTheRegistrationBodyIsRejectedAndNothingIsCreated() throws Exception {
        String email = uniqueEmail();
        for (String extra : List.of("\"role\":\"ADMIN\"", "\"enabled\":false", "\"role\":\"AUDITOR\"")) {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fullName\":\"M\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"," + extra + "}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(appJdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isZero();
    }

    @Test
    void unknownEmailWrongPasswordAndDisabledAccountGetIdentical401s() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());

        String wrongPassword = login(email, "not the right password").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = login(uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        ownerJdbc.update("update users set enabled = false where email = ?", email);
        String disabledCorrectPassword = login(email, PASSWORD).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(wrongPassword).isEqualTo(unknownEmail).isEqualTo(disabledCorrectPassword).contains("Invalid email or password");
    }

    @Test
    void disablingAUserRevokesAccessImmediatelyEvenWithAnUnexpiredToken() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());
        String token = loginToken(email);
        me(token).andExpect(status().isOk());

        ownerJdbc.update("update users set enabled = false where email = ?", email);

        me(token).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""));
    }

    @Test
    void roleComesFromTheDatabaseOnEveryRequestNotFromTheToken() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());
        String token = loginToken(email);
        me(token).andExpect(jsonPath("$.role").value("ACCOUNT_HOLDER"));

        ownerJdbc.update("update users set role = 'AUDITOR' where email = ?", email);

        me(token).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("AUDITOR"));
    }

    @Test
    void expiredTokenForAnExistingUserIsRejected() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());
        Clock seventeenMinutesAgo = Clock.fixed(Instant.now().minus(Duration.ofMinutes(17)), ZoneOffset.UTC);
        String expired = new JwtTokenService(jwtEncoder, jwtProperties, seventeenMinutesAgo).issue(userId(email)).value();

        me(expired).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.title").value("Authentication required"));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());
        JwtProperties otherKey = new JwtProperties(RandomJwtSecret.newSecret(), jwtProperties.issuer(), Duration.ofMinutes(15));
        String forged = new JwtTokenService(JwtTestSupport.encoder(otherKey), otherKey, Clock.systemUTC()).issue(userId(email)).value();

        me(forged).andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenForAUserThatDoesNotExistIsRejected() throws Exception {
        String token = new JwtTokenService(jwtEncoder, jwtProperties, Clock.systemUTC()).issue(UUID.randomUUID()).value();
        me(token).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointNeedsAToken() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void healthStaysPublic() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
