package com.flashsale.naive;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Service
public class TicketService {
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<String> claimScript;
    private final TicketRepository repo;
    // constructor injection...
    public TicketService(StringRedisTemplate redis, 
                         DefaultRedisScript<String> claimScript, 
                         TicketRepository repo) {
        this.redis = redis;
        this.claimScript = claimScript;
        this.repo = repo;
    }


    public String claim(String userId) {
        String code = redis.execute(claimScript,
            List.of("flash:tickets:available", "flash:tickets:owner"), userId);
        if (code == null) return null;               // sold out — nothing to compensate

        try {
            Ticket t = repo.findByTicketCode(code).orElseThrow(
                () -> new IllegalStateException("Ticket code not found in DB: " + code));
            t.setStatus("SOLD");
            t.setUserId(userId);
            t.setPurchasedAt(LocalDateTime.now());
            repo.save(t);
            return code;
        } catch (Exception e) {
            // Postgres write failed AFTER Redis already committed the claim.
            // Undo the Redis side so the ticket returns to the pool instead of vanishing.
            redis.opsForHash().delete("flash:tickets:owner", code);
            redis.opsForList().leftPush("flash:tickets:available", code);
            throw new RuntimeException("Failed to persist sale for " + code + " — released back to pool", e);
        }
    }


}
