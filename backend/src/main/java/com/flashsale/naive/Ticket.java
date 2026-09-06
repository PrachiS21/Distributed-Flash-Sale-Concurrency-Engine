package com.flashsale.naive;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "tkt")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tkt_code", unique = true, nullable = false)
    private String ticketCode;

    @Column(nullable = false)
    private String status;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "purchased_at")
    private LocalDateTime purchasedAt;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getTicketCode() { return ticketCode; }
    public void setTicketCode(String ticketCode) { this.ticketCode = ticketCode; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    
    public LocalDateTime getPurchasedAt() { return purchasedAt; }
    public void setPurchasedAt(LocalDateTime purchasedAt) { this.purchasedAt = purchasedAt; }
}