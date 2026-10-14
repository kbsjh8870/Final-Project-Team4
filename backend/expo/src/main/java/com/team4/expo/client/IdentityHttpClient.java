package com.team4.expo.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.team4.common.error.CustomException;
import com.team4.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Component
@Slf4j
public class IdentityHttpClient implements IdentityClient {

    private final String identityBaseUrl;
    private final String serviceToken;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public IdentityHttpClient(
            @Value("${identity.base-url}") String identityBaseUrl,
            @Value("${identity.service-token}") String serviceToken,
            ObjectMapper objectMapper
    ) {
        this.identityBaseUrl = identityBaseUrl;
        this.serviceToken = serviceToken;
        this.objectMapper = objectMapper;
    }

    // 참가업체 프로필은 잘 바뀌지 않으니 10분간 캐시에 보관
    private final Cache<Long, ExhibitorProfile> exhibitorProfiles = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(1_000)
            .build();

    @Override
    public Optional<ExhibitorProfile> getExhibitorProfile(Long userId) {
        ExhibitorProfile cached = exhibitorProfiles.getIfPresent(userId); // 캐시 확인
        if (cached != null) {
            return Optional.of(cached);
        }

        // cache.get(key, 매핑 펑션)으로 매핑 펑션 안에서 HTTP를 기다리면 캐시 내부 락이 잡혀 다른 업체 조회까지 막힐 수 있음
        // 따라서 캐시 밖에서 조회하고 성공한 결과만 저장
        Optional<ExhibitorProfile> profile = fetchExhibitorProfile(userId); // http 조회
        profile.ifPresent(p -> exhibitorProfiles.put(userId, p)); // 성공 시 저장
        return profile;
    }

    private Optional<ExhibitorProfile> fetchExhibitorProfile(Long userId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(identityBaseUrl + "/internal/identity/users/" + userId))
                    .header("Authorization", "Bearer " + serviceToken)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Identity 참가업체 정보 조회 실패 userId={}, status={}", userId, response.statusCode());
                return Optional.empty();
            }

            JsonNode data = objectMapper.readTree(response.body()).path("data");

            return Optional.of(new ExhibitorProfile(
                    data.path("companyName").asText(null),
                    data.path("industry").asText(null),
                    data.path("businessNo").asText(null),
                    data.path("representativeName").asText(null),
                    data.path("email").asText(null)
            ));
        } catch (IOException | InterruptedException e) {
            // 부가 표시 정보 조회 실패는 부스 목록 조회 자체를 막으면 안 되므로 예외를 삼키고 빈 값 반환
            log.warn("Identity 서버 통신 중 오류 userId={}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<CustomerContact> getCustomerContact(Long customerId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(identityBaseUrl + "/internal/identity/users/" + customerId))
                    .header("Authorization", "Bearer " + serviceToken)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Identity 고객 정보 조회 실패 customerId={}, status={}", customerId, response.statusCode());
                return Optional.empty();
            }

            JsonNode data = objectMapper.readTree(response.body()).path("data");
            return Optional.of(new CustomerContact(data.path("name").asText(null), data.path("email").asText(null)));
        } catch (IOException | InterruptedException e) {
            log.warn("Identity 서버 통신 중 오류 customerId={}: {}", customerId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void sendMail(String to, String subject, String body) {
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of("to", to, "subject", subject, "body", body));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(identityBaseUrl + "/internal/identity/mails"))
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new CustomException(ErrorCode.INTERNAL_ERROR,
                        "Identity 메일 발송 실패 (status=" + response.statusCode() + "): " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "Identity 서버 통신 중 오류: " + e.getMessage());
        }
    }
}
