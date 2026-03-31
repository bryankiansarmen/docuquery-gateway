package com.docuquery.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class RateLimitFilter extends AbstractGatewayFilterFactory<RateLimitFilter.Config> {

    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;

    public RateLimitFilter(ReactiveRedisTemplate<String, String> reactiveRedisTemplate) {
        super(Config.class);
        this.reactiveRedisTemplate = reactiveRedisTemplate;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");

            if (apiKey == null || apiKey.isBlank()) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            String redisKey = "rate_limit:" + apiKey + ":" +
                    exchange.getRequest().getPath().value();

            return reactiveRedisTemplate.opsForValue()
                    .increment(redisKey)
                    .flatMap(count -> {
                        if (count == 1) {
                            return reactiveRedisTemplate.expire(redisKey, Duration.ofSeconds(1))
                                    .then(Mono.just(count));
                        }
                        return Mono.just(count);
                    })
                    .flatMap(count -> {
                        if (count > config.getCapacity()) {
                            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                            exchange.getResponse().getHeaders()
                                    .add("X-RateLimit-Limit", String.valueOf(config.getCapacity()));
                            exchange.getResponse().getHeaders()
                                    .add("X-RateLimit-Remaining", "0");
                            return exchange.getResponse().setComplete();
                        }

                        exchange.getResponse().getHeaders()
                                .add("X-RateLimit-Limit", String.valueOf(config.getCapacity()));
                        exchange.getResponse().getHeaders()
                                .add("X-RateLimit-Remaining",
                                        String.valueOf(config.getCapacity() - count));

                        return chain.filter(exchange);
                    });
        };
    }

    public static class Config {
        private int capacity = 10;
        private int refillRate = 5;
        private String keyType = "API_KEY";

        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }
        public int getRefillRate() { return refillRate; }
        public void setRefillRate(int refillRate) { this.refillRate = refillRate; }
        public String getKeyType() { return keyType; }
        public void setKeyType(String keyType) { this.keyType = keyType; }
    }
}