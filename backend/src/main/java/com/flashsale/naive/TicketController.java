package com.flashsale.naive;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDateTime;
import java.util.Optional;

@RestController
public class TicketController {

    private final TicketRepository ticketRepository;

    public TicketController(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @PostMapping("/buy")
    @Transactional
    public ResponseEntity<String> buyTicket(@RequestParam String userId) {
        
        Optional<Ticket> availableTicket = ticketRepository.findFirstByStatus("AVAILABLE");

        if (availableTicket.isPresent()) {
            Ticket ticket = availableTicket.get();

            // The Trap: Simulated processing latency to force race condition overlaps
            try {
                Thread.sleep(5); 
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            ticket.setStatus("SOLD");
            ticket.setUserId(userId);
            ticket.setPurchasedAt(LocalDateTime.now());
            
            ticketRepository.save(ticket);
            
            return ResponseEntity.ok("Success: Purchased " + ticket.getTicketCode());
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Sold Out");
    }
}