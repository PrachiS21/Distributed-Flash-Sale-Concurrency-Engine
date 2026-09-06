package com.flashsale.naive;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDateTime;
import java.util.List;

@RestController
public class TicketController {

    private final TicketRepository ticketRepository;

    public TicketController(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @PostMapping("/buy")
    @Transactional
    public ResponseEntity<String> buyTicket(@RequestParam String userId) {

        // lockNextAvailable() takes a row lock (FOR UPDATE SKIP LOCKED) on one
        // AVAILABLE ticket for this transaction only. No other concurrent
        // transaction can see or grab that same row, so the mutate-and-save below
        // can never race with another request for the same ticket.
        List<Ticket> locked = ticketRepository.lockNextAvailable(PageRequest.of(0, 1));

        if (!locked.isEmpty()) {
            Ticket ticket = locked.get(0);
            ticket.setStatus("SOLD");
            ticket.setUserId(userId);
            ticket.setPurchasedAt(LocalDateTime.now());
            ticketRepository.save(ticket);
            return ResponseEntity.ok("Success: Purchased " + ticket.getTicketCode());
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Sold Out");
    }
}