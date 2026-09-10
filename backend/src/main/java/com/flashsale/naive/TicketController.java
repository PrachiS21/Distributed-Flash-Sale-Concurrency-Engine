package com.flashsale.naive;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDateTime;
import java.util.List;

@RestController
public class TicketController {
    
    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping("/buy")
    public ResponseEntity<String> buyTicket(@RequestParam String userId) {
        String code = ticketService.claim(userId);
        if (code == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Sold Out");
        }
        return ResponseEntity.ok("Success: Purchased " + code);
    }

}

