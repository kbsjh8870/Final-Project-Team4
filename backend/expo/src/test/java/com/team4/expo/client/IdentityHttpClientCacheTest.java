package com.team4.expo.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 참가업체 프로필 캐시
@DisplayName("IdentityHttpClient 참가업체 프로필 캐시")
class IdentityHttpClientCacheTest {

    private HttpServer identity;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;
    private IdentityHttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        identity = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        identity.createContext("/internal/identity/users/", ex -> {
            hits.incrementAndGet();
            byte[] body = "{\"data\":{\"companyName\":\"현대\",\"industry\":\"자동차\"}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        identity.start();
        client = new IdentityHttpClient("http://127.0.0.1:" + identity.getAddress().getPort(), "token", new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        identity.stop(0);
    }

    @Test
    void 같은_참가업체를_다시_조회하면_Identity를_호출하지_않는다() {
        assertThat(client.getExhibitorProfile(1L)).get().extracting(ExhibitorProfile::companyName).isEqualTo("현대");
        assertThat(client.getExhibitorProfile(1L)).get().extracting(ExhibitorProfile::companyName).isEqualTo("현대");

        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void 조회가_실패하면_저장하지_않고_다음에_다시_조회한다() {
        status = 500;
        assertThat(client.getExhibitorProfile(1L)).isEmpty();

        status = 200;
        assertThat(client.getExhibitorProfile(1L)).isPresent();

        assertThat(hits.get()).isEqualTo(2);
    }
}
