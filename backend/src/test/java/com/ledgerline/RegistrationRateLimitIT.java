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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Per-IP registration limit through the real endpoint against ledgerline_test_db (limit 3 per IP here). */
@SpringBootTest(properties = {
        "ledgerline.registration-rate-limit.max-attempts-per-ip=3",
        "ledgerline.registration-rate-limit.window=1h"})
@AutoConfigureMockMvc
class RegistrationRateLimitIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate appJdbc;

    private static String uniqueEmail() {
        return "reg-rl-" + UUID.randomUUID() + "@example.test";
    }

    private ResultActions register(String ip, String email) throws Exception {
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Reg Limit\",\"email\":\"" + email + "\",\"password\":\"correct horse battery staple\"}")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                }));
    }

    private int rowsFor(String email) {
        return appJdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email);
    }

    @Test
    void successesAndDuplicatesCountAndTheNextAttemptIsRefusedWithoutCreatingAUser() throws Exception {
        String ip = "203.0.113.40";
        String first = uniqueEmail();
        register(ip, first).andExpect(status().isCreated());
        register(ip, first).andExpect(status().isConflict());          // a duplicate still counts
        register(ip, uniqueEmail()).andExpect(status().isCreated());

        String refused = uniqueEmail();
        register(ip, refused).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.title").value("Too many registration attempts"));
        assertThat(rowsFor(refused)).isZero();

        // Another IP is unaffected.
        register("203.0.113.41", refused).andExpect(status().isCreated());
    }

    @Test
    void invalidRequestsAreNotCounted() throws Exception {
        String ip = "203.0.113.42";
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fullName\":\"\",\"email\":\"bad\",\"password\":\"x\"}")
                            .with(request -> {
                                request.setRemoteAddr(ip);
                                return request;
                            }))
                    .andExpect(status().isBadRequest());
        }
        register(ip, uniqueEmail()).andExpect(status().isCreated());
    }

    @Test
    void aParallelBurstFromOneIpCreatesExactlyTheLimit() throws Exception {
        String ip = "203.0.113.43";
        int attempts = 20;
        List<String> emails = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            emails.add(uniqueEmail());
        }
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            for (String email : emails) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return register(ip, email).andReturn().getResponse().getStatus();
                }));
            }
            ready.await();
            go.countDown();
        }
        List<Integer> statuses = results.stream().map(Future::resultNow).toList();
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(3);
        assertThat(statuses).filteredOn(s -> s == 429).hasSize(attempts - 3);
        assertThat(emails.stream().mapToInt(this::rowsFor).sum()).isEqualTo(3);
    }
}
