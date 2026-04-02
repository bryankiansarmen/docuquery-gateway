package com.docuquery.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RateLimitFilter}.
 *
 * <p>All Redis interactions are mocked so these tests run without an
 * infrastructure dependency and complete in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    @Mock
    private GatewayFilterChain chain;

    private RateLimitFilter rateLimitFilter;
    private RateLimitFilter.Config config;

    @BeforeEach
    void setUp() {
        rateLimitFilter = new RateLimitFilter(redisTemplate);
        config = new RateLimitFilter.Config();
        config.setCapacity(10);
        config.setRefillRate(5);
    }

    // -------------------------------------------------------------------------
    // Missing / blank API key  →  401 Unauthorized
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("When X-API-Key header is absent or blank")
    class MissingApiKey {

        @Test
        @DisplayName("returns 401 when header is missing entirely")
        void returnsUnauthorizedWhenApiKeyHeaderMissing() {
            MockServerWebExchange exchange = exchangeWithoutApiKey("/doc/ask");
            GatewayFilter filter = rateLimitFilter.apply(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(redisTemplate, never()).opsForValue();
            verify(chain, never()).filter(any());
        }

        @Test
        @DisplayName("returns 401 when header value is blank")
        void returnsUnauthorizedWhenApiKeyIsBlank() {
            MockServerWebExchange exchange = exchangeWithApiKey("", "/doc/ask");
            GatewayFilter filter = rateLimitFilter.apply(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(chain, never()).filter(any());
        }
    }

    // -------------------------------------------------------------------------
    // First request for a key  →  TTL is set, request passes through
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("When this is the first request for an API key")
    class FirstRequest {

        @Test
        @DisplayName("sets a 1-second TTL on the Redis key and forwards the request")
        void setsTtlAndForwardsFirstRequest() {
            stubRedisIncrement(1L);
            stubRedisExpire(true);
            when(chain.filter(any())).thenReturn(Mono.empty());

            MockServerWebExchange exchange = exchangeWithApiKey("test-key", "/doc/ask");
            GatewayFilter filter = rateLimitFilter.apply(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            String expectedRedisKey = "rate_limit:test-key:/doc/ask";
            verify(valueOps).increment(expectedRedisKey);
            verify(redisTemplate).expire(eq(expectedRedisKey), eq(Duration.ofSeconds(1)));
            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("adds X-RateLimit headers with correct remaining count")
        void addsRateLimitHeadersOnFirstRequest() {
            stubRedisIncrement(1L);
            stubRedisExpire(true);
            when(chain.filter(any())).thenReturn(Mono.empty());

            MockServerWebExchange exchange = exchangeWithApiKey("test-key", "/doc/ask");
            GatewayFilter filter = rateLimitFilter.apply(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            HttpHeaders headers = exchange.getResponse().getHeaders();
            assertThat(headers.getFirst("X-RateLimit-Limit")).isEqualTo("10");
            assertThat(headers.getFirst("X-RateLimit-Remaining")).isEqualTo("9"); // capacity - 1
        }
    }

    // -------------------------------------------------------------------------
    // Subsequent request within limit  →  no TTL, passes through with headers
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("When the request count is within the configured capacity")
    class WithinRateLimit {

        @Test
        @DisplayName("does NOT reset TTL for subsequent requests")
        void doesNotResetTtlForSubsequentRequests() {
            stubRedisIncrement(5L);   // 5th request — count > 1
            when(chain.filter(any())).thenReturn(Mono.empty());

            MockServerWebExchange exchange = exchangeWithApiKey("test-key", "/doc/upload");
            GatewayFilter filter = rateLimitFilter.apply(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(redisTemplate, never()).expire(anyString(), any());
            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("includes accurate X-RateLimit-Remaining in response headers")
        void includesAccurateRemainingHeader() {
            stubRedisIncrement(7L);  // 7 used, 3 remaining
            when(chain.filter(any())).thenReturn(Mono.empty());

            MockServerWebExchange exchange = exchangeWithApiKey("test-key", "/doc/upload");
            GatewayFilter filter = rateLimitFilter.apply(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            HttpHeaders headers = exchange.getResponse().getHeaders();
            assertThat(headers.getFirst("X-RateLimit-Limit")).isEqualTo("10");
            assertThat(headers.getFirst("X-RateLimit-Remaining")).isEqualTo("3");
        }
    }

    // -------------------------------------------------------------------------
    // Requests exceeding capacity  →  429 Too Many Requests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("When the request count exceeds the configured capacity")
    class RateLimitExceeded {

        @Test
        @DisplayName("returns 429 Too Many Requests")
        void returnsTooManyRequestsStatus() {
            stubRedisIncrement(11L);  // capacity is 10

            MockServerWebExchange exchange = exchangeWithApiKey("test-key", "/doc/ask");
            GatewayFilter filter = rateLimitFilter.apply(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            verify(chain, never()).filter(any());
        }

        @Test
        @DisplayName("returns X-RateLimit-Remaining: 0 when limit exceeded")
        void returnsZeroRemainingHeaderWhenLimitExceeded() {
            stubRedisIncrement(20L);

            MockServerWebExchange exchange = exchangeWithApiKey("test-key", "/doc/ask");
            GatewayFilter filter = rateLimitFilter.apply(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            HttpHeaders headers = exchange.getResponse().getHeaders();
            assertThat(headers.getFirst("X-RateLimit-Limit")).isEqualTo("10");
            assertThat(headers.getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        }

        @Test
        @DisplayName("returns 429 exactly at count == capacity + 1 (boundary)")
        void returnsTooManyRequestsAtBoundary() {
            stubRedisIncrement((long) config.getCapacity() + 1);

            MockServerWebExchange exchange = exchangeWithApiKey("boundary-key", "/doc/upload");
            GatewayFilter filter = rateLimitFilter.apply(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    // -------------------------------------------------------------------------
    // Config defaults
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Config defaults")
    class ConfigDefaults {

        @Test
        @DisplayName("Config has expected default values")
        void configHasExpectedDefaults() {
            RateLimitFilter.Config defaultConfig = new RateLimitFilter.Config();

            assertThat(defaultConfig.getCapacity()).isEqualTo(10);
            assertThat(defaultConfig.getRefillRate()).isEqualTo(5);
            assertThat(defaultConfig.getKeyType()).isEqualTo("API_KEY");
        }

        @Test
        @DisplayName("Config values can be mutated via setters")
        void configSettersWork() {
            RateLimitFilter.Config c = new RateLimitFilter.Config();
            c.setCapacity(30);
            c.setRefillRate(15);
            c.setKeyType("USER_ID");

            assertThat(c.getCapacity()).isEqualTo(30);
            assertThat(c.getRefillRate()).isEqualTo(15);
            assertThat(c.getKeyType()).isEqualTo("USER_ID");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private MockServerWebExchange exchangeWithApiKey(String apiKey, String path) {
        MockServerHttpRequest request = MockServerHttpRequest
                .get(path)
                .header("X-API-Key", apiKey)
                .build();
        return MockServerWebExchange.from(request);
    }

    private MockServerWebExchange exchangeWithoutApiKey(String path) {
        MockServerHttpRequest request = MockServerHttpRequest
                .get(path)
                .build();
        return MockServerWebExchange.from(request);
    }

    private void stubRedisIncrement(long returnValue) {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(Mono.just(returnValue));
    }

    private void stubRedisExpire(boolean result) {
        when(redisTemplate.expire(anyString(), any(Duration.class)))
                .thenReturn(Mono.just(result));
    }
}
