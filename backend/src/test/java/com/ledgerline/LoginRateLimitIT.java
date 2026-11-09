package com.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Rate limiting through the real login endpoint against ledgerline_test_db, with low limits so the test is fast.
 * Includes a real parallel burst through the full HTTP and security stack.
 * Its own Spring context (because of the properties), so its counts never affect other test classes.
 * Window expiry is covered by LoginRateLimiterTest with a controllable clock.
 */
@SpringBootTest(properties = {
        "ledgerline.login-rate-limit.max-failures-per-email=3",
        "ledgerline.login-rate-limit.max-failures-per-ip=5",
        "ledgerline.login-rate-limit.window=15m"})
@AutoConfigureMockMvc
class LoginRateLimitIT {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    private MockMvc mvc;

    private static String uniqueEmail() {
        return "rl-" + UUID.randomUUID() + "@example.test";
    }

    private void register(String email) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Rate Limit\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated());
    }

    private ResultActions login(String ip, String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                }));
    }

    @Test
    void emailLimitThenIpLimitThroughTheRealEndpoint() throws Exception {
        String ip = "203.0.113.10";
        String victim = uniqueEmail();
        String other = uniqueEmail();
        register(victim);
        register(other);

        // Three failures for one email are still ordinary 401s.
        for (int i = 0; i < 3; i++) {
            login(ip, victim, "wrong password " + i).andExpect(status().isUnauthorized());
        }
        // The fourth attempt is refused before the password is checked, even with the right password,
        // and from a different IP: the email limit is not tied to one IP.
        login(ip, victim, "wrong password 3").andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.title").value("Too many login attempts"));
        login(ip, victim, PASSWORD).andExpect(status().isTooManyRequests());
        login("198.51.100.20", victim, PASSWORD).andExpect(status().isTooManyRequests());

        // Other accounts can still sign in from elsewhere.
        login("198.51.100.20", other, PASSWORD).andExpect(status().isOk());

        // The first IP has 3 failures; two more for an unknown email reach its limit of 5.
        login(ip, uniqueEmail(), "nope nope").andExpect(status().isUnauthorized());
        login(ip, uniqueEmail(), "nope nope").andExpect(status().isUnauthorized());
        // Now that IP is blocked even for an account with no failures and the right password.
        login(ip, other, PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    void aSuccessfulLoginDoesNotEraseEarlierFailures() throws Exception {
        String ip = "203.0.113.11";
        String email = uniqueEmail();
        register(email);

        login(ip, email, "wrong password a").andExpect(status().isUnauthorized());
        login(ip, email, "wrong password b").andExpect(status().isUnauthorized());
        // A success gives back only its own attempt: the two failures stay counted (limit 3).
        login(ip, email, PASSWORD).andExpect(status().isOk());
        login("203.0.113.12", email, "wrong password c").andExpect(status().isUnauthorized());
        login("203.0.113.12", email, PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    void successfulLoginsAloneAreNeverLimited() throws Exception {
        String email = uniqueEmail();
        register(email);
        for (int i = 0; i < 8; i++) {
            login("203.0.113.13", email, PASSWORD).andExpect(status().isOk());
        }
    }

    @Test
    void parallelWrongPasswordsGetExactlyTheEmailLimitThrough() throws Exception {
        String email = uniqueEmail();
        register(email);
        int attempts = 40;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            for (int i = 0; i < attempts; i++) {
                String ip = "198.51.100." + (i % 4 + 30);   // several IPs: the email limit must hold across them
                String guess = "wrong guess " + i;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return login(ip, email, guess).andReturn().getResponse().getStatus();
                }));
            }
            ready.await();
            go.countDown();
        }
        List<Integer> statuses = results.stream().map(Future::resultNow).toList();
        assertThat(statuses).filteredOn(s -> s == 401).hasSize(3);
        assertThat(statuses).filteredOn(s -> s == 429).hasSize(attempts - 3);
        login("198.51.100.99", email, PASSWORD).andExpect(status().isTooManyRequests());
    }
}
