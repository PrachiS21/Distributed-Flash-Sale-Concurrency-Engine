package com.flashsale.naive;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.List;

@Configuration
public class RedisConfig {

    @Bean
    CommandLineRunner seedRedis(StringRedisTemplate redis, TicketRepository repo) {
        return args -> { 
            String availableKey = "flash:tickets:available";

            if (Boolean.TRUE.equals(redis.hasKey(availableKey))) {
                return; // already seeded — don't duplicate or overwrite an in-progress sale
            }

            List<String> codes = repo.findAll().stream()
                .filter(t -> "AVAILABLE".equals(t.getStatus()))
                .map(Ticket::getTicketCode)
                .toList();

            if (!codes.isEmpty()) {
                redis.opsForList().rightPushAll(availableKey, codes);
            }
        };
}

    // Lua script
    @Bean
    DefaultRedisScript<String> claimScript() {
        DefaultRedisScript<String> s = new DefaultRedisScript<>();
        s.setScriptText(
            "local code = redis.call('LPOP', KEYS[1]) " +
            "if not code then return nil end " +
            "redis.call('HSET', KEYS[2], code, ARGV[1]) " +
            "return code");
        s.setResultType(String.class);
        return s;
    }
}

