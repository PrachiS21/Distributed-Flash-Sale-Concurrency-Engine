# Project: Flash Sale Concurrency Challenge (Thundering Herd)

## Problem Statement
Sell exactly 100 tickets to 50,000 people arriving in the same 60 seconds. 
1. Build a naive service that fails (oversells tickets).
2. Build a load client to simulate the 50k requests and prove the service breaks.
3. Fix the concurrency issue (evaluate Database Locks, Redis Atomic Counters, and Message Queues).

## Environment Context
* **OS:** macOS (Apple Silicon M1)
* **Database:** PostgreSQL 18 (Local)
* **Backend:** Java (Spring Boot, Spring Data JPA, Tomcat, HikariCP)
* **Load Tester (Pending):** Python script with asyncio and aiohttp

## Current Status
**Phase 1 (The Glass House)** is in progress. A naive, synchronous REST API has been built using a classic "check-then-act" flow. A 5ms simulated processing latency has been added to guarantee race conditions under heavy load.

---

## 1. Database Setup (PostgreSQL)

**Database Name:** `bigB_days`

```sql
-- CREATE TABLE tkt (
--     id SERIAL PRIMARY KEY,
--     tkt_code VARCHAR(32) UNIQUE NOT NULL,
--     status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
--     user_id VARCHAR(64),
--     purchased_at TIMESTAMP WITH TIME ZONE
-- );

-- -- Seed 100 tickets
-- INSERT INTO tkt (tkt_code, status)
-- SELECT 
--     'TICK-' || LPAD(s::text, 4, '0'),
--     'AVAILABLE'
-- FROM generate_series(1, 100) AS s;