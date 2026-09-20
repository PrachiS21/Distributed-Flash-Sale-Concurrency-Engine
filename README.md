# RACE-3 — Flash Sale Concurrency Challenge (Thundering Herd)

## Problem statement

Sell **exactly 100 tickets** to **50,000 people** who all arrive in the same 60 seconds.

1. Build a naive service that fails (oversells tickets).
2. Build a load client that simulates the 50k requests and proves the service breaks.
3. Fix the concurrency issue, evaluating **Database Locks**, **Redis Atomic Counters** and **Message Queues**.

## Versions

| # | Version | Commit | How `/buy` claims a ticket | Status |
|---|---|---|---|---|
| v1 | The Glass House (naive) | `c0a41af` | Check-then-act: `findFirstByStatus('AVAILABLE')` → 5 ms simulated latency → `save` | Oversells (on purpose) |
| v2 | Postgres row lock | `89a96f0` | `SELECT … FOR UPDATE SKIP LOCKED` on the next available row, inside one transaction | Correct, bounded by the DB pool |
| **v3** | **Redis atomic claim (current)** | `ed61cf9` / `main` | Lua script `LPOP` + `HSET` in Redis, then persist the sale to Postgres | **Correct, horizontally scalable** |
| v4 | Message queue | – | – | Planned |

### Current version (v3): Redis atomic claim

`POST /buy?userId=<id>` → `TicketController` → `TicketService.claim(userId)`:

1. A Lua script runs **atomically** inside Redis:
   `LPOP flash:tickets:available` → if nothing is left, return nil → otherwise `HSET flash:tickets:owner <code> <userId>` and return the code.
   Redis is single-threaded, so no two requests can pop the same ticket, even from several app instances.
2. Nil → **400 `Sold Out`**.
3. Otherwise the ticket row in Postgres is marked `SOLD` with `user_id` and `purchased_at` → **200 `Success: Purchased <code>`**.
4. If the Postgres write fails, the claim is **compensated** (the owner entry is removed and the code is pushed back onto the list) and the request returns 500.

At startup, `RedisConfig.seedRedis` fills `flash:tickets:available` from the Postgres rows that are still `AVAILABLE`, but only if that key doesn't exist yet.

## Why each upgrade was needed

### v1 → v2: overselling

- **Race condition:** two requests read the same `AVAILABLE` row before either saves, so both "buy" it. The second save silently overwrites the first.
- `@Transactional` doesn't stop this under Postgres's default isolation (READ COMMITTED). The 5 ms sleep widens the window.
- **Hidden damage:** the table still shows 100 `SOLD` rows, but far more than 100 users got "Success".

**Fix:** `SELECT … FOR UPDATE SKIP LOCKED` locks the row, so only one transaction can claim it.

### v2 → v3: correct, but high latency under load

| Problem | Why it hurts |
|---|---|
| Every request goes through Postgres | ~49,900 "Sold Out" requests still run a locking transaction |
| 10 DB connections vs 200 threads | A connection is held for the whole request → the rest queue → latency |
| Queueing becomes errors | Waits over 30 s become 500s or client timeouts; user retries add load |
| More instances don't help | They add connections to one Postgres (`max_connections` ≈ 100) |
| Disk and lock overhead per sale | WAL sync, row locks, SKIP LOCKED scans |
| Sale traffic slows every other feature | The whole app shares the same DB |

**Fix:** a Redis Lua script claims a ticket atomically in memory. "Sold Out" costs one Redis call, and only the 100 winners write to Postgres.

### v3's remaining gaps (→ v4 message queue)

- **Crash between the Redis claim and the Postgres save** leaves the two stores disagreeing.
- **Redis restarts without persistence** → startup reseeds from Postgres → a ticket could be sold twice.
- **Still synchronous:** each request holds a thread until the Postgres write finishes.

## Tech stack

- **OS:** macOS (Apple Silicon M1). All services run locally.
- **Backend:** Java 21, Spring Boot 3.5.6 (Web/Tomcat, Data JPA/Hibernate, HikariCP, Data Redis)
- **Database:** PostgreSQL 18, database `bigB_days`
- **Cache/lock:** Redis on `localhost:6379` (v3 only)
- **Load tester:** Python 3 with asyncio + aiohttp (`loadtest/flood.py`)

Load limits are set low on purpose (in `application.properties`) so you can watch the server struggle: `server.tomcat.threads.max=200` and `spring.datasource.hikari.maximum-pool-size=10`.

## Repository layout

```
backend/                 Spring Boot service (Maven)
  db/check.sql           Post-run check: tickets by status, sold rows vs distinct winners
  db/reset.sql           Set every ticket back to AVAILABLE
  src/main/java/com/flashsale/naive/
    TicketController.java   POST /buy
    TicketService.java      Redis claim + Postgres persist + compensation
    RedisConfig.java        Redis seeding on startup + Lua claim script
    TicketRepository.java   Spring Data repo (also holds the v2 SKIP LOCKED query)
    Ticket.java             JPA entity for table `tkt`
loadtest/flood.py        Thundering-herd load client
reset.sh                 Reset Redis AND Postgres between runs
```

## One-time setup

### 1. Database (PostgreSQL)

```sql
CREATE DATABASE "bigB_days";
\c bigB_days

CREATE TABLE tkt (
    id SERIAL PRIMARY KEY,
    tkt_code VARCHAR(32) UNIQUE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    user_id VARCHAR(64),
    purchased_at TIMESTAMP WITH TIME ZONE
);

-- Seed 100 tickets: TICK-001 … TICK-100
INSERT INTO tkt (tkt_code, status)
SELECT 'TICK-' || LPAD(s::text, 3, '0'), 'AVAILABLE'
FROM generate_series(1, 100) AS s;
```

### 2. Redis (v3 only)

```sh
brew install redis && brew services start redis
redis-cli ping      # → PONG
```

### 3. Load tester

```sh
cd loadtest
python3 -m venv .venv
./.venv/bin/pip install -r requirements.txt
```

### 4. Database password

The service reads the Postgres password from `DB_PASSWORD` (it defaults to `postgres`). Set it once per terminal:

```sh
export DB_PASSWORD='<your-postgres-password>'
export PGPASSWORD="$DB_PASSWORD"     # so psql doesn't prompt
```

## Running the current version (v3)

All commands run from the repo root.

```sh
# 1. Reset both Redis and Postgres (always do this before a run)
./reset.sh

# 2. Build and start the service (separate terminal)
cd backend && mvn spring-boot:run

# 3. Flood it
cd loadtest
./.venv/bin/python flood.py --total 100000 --concurrency 2000
#    two instances: run one flood per port at the same time, with distinct user ids
./.venv/bin/python flood.py --total 50000 --url http://localhost:8080/buy --user-prefix a &
./.venv/bin/python flood.py --total 50000 --url http://localhost:8081/buy --user-prefix b &
wait

# 4. Verify
psql -U postgres -d bigB_days -f backend/db/check.sql   # SOLD = 100, distinct_winners = 100
redis-cli LLEN flash:tickets:available                   # expect 0
redis-cli HLEN flash:tickets:owner                       # expect 100
```

Stop background instances with `pkill -f naive-service-0.0.1-SNAPSHOT.jar`.

### `flood.py` options

| Flag | Default | Meaning |
|---|---|---|
| `--url` | `http://localhost:8080/buy` | Endpoint to hit |
| `--total` | `50000` | Total requests |
| `--concurrency` | `1000` | Max in-flight requests |
| `--ramp` | `0` | Seconds to spread request starts over (`0` = all at once, `60` = the "same 60 seconds" scenario) |
| `--timeout` | `30` | Per-request timeout (s) |
| `--user-prefix` | `user` | userId = `<prefix><n>` |

The script reports HTTP status counts, outcomes (purchased / sold_out / other / timeout / conn_error), throughput, and p50/p95/p99/max latency. It prints `OVERSOLD` if more than 100 purchases succeed.

## Running previous versions (to compare improvements)

The older versions are in git history. The cleanest approach is a **git worktree**: a separate folder checked out at that commit, so your current working copy isn't touched.

```sh
# From the repo root. Create each worktree once:
git worktree add ../RACE-v1-naive     c0a41af
git worktree add ../RACE-v2-pglock    89a96f0
```

v1 and v2 **only use Postgres** (no Redis). Stop any running v3 instance first, because every version shares the same `tkt` table. Then:

```sh
# v1 (naive): expect OVERSOLD
psql -U postgres -d bigB_days -f backend/db/reset.sql
cd ../RACE-v1-naive/backend && mvn spring-boot:run          # separate terminal, port 8080

cd loadtest   # back in the main RACE repo; the current flood.py works against every version
./.venv/bin/python flood.py --total 50000 --concurrency 1000   # client reports far more than 100 purchases → OVERSOLD
psql -U postgres -d bigB_days -f backend/db/check.sql
```

```sh
# v2 (Postgres SKIP LOCKED): expect exactly 100
psql -U postgres -d bigB_days -f backend/db/reset.sql
cd ../RACE-v2-pglock/backend && mvn spring-boot:run

cd loadtest   # back in the main RACE repo
./.venv/bin/python flood.py --total 50000 --concurrency 1000   # exactly 100 purchased
psql -U postgres -d bigB_days -f backend/db/check.sql
```

In v1, the ticket count in the table never goes above 100, because many requests overwrite the same rows. The oversell shows up as the client's `purchased` count (and the extra users told "Success").

Before returning to v3, run `./reset.sh`. Redis and Postgres get out of sync whenever only one of them is reset.

To compare the code itself without checking anything out:

```sh
git show c0a41af:backend/src/main/java/com/flashsale/naive/TicketController.java   # v1 logic
git show 89a96f0:backend/src/main/java/com/flashsale/naive/TicketController.java   # v2 logic
git diff c0a41af 89a96f0 -- backend/src     # v1 → v2
git diff 89a96f0 ed61cf9 -- backend/src     # v2 → v3
```

Remove the worktrees when you're done: `git worktree remove ../RACE-v1-naive` (and the same for v2).

### Results log

Use the same flood parameters for every version so the numbers are comparable.

| Version | Command | Purchased (client) | SOLD rows / distinct winners | Throughput (req/s) | p50 / p99 (ms) | Errors (5xx / timeout / conn) |
|---|---|---|---|---|---|---|
| v1 naive | `--total 50000 --concurrency 1000` | 976 (OVERSOLD, +876) | 100 / 100 | 4,689 | 165 / 644 | 0 |
| v2 PG lock | `--total 50000 --concurrency 1000` | 100 | 100 / 100 | 4,480 | 172 / 575 | 0 |
| v2 PG lock | `--total 100000 --concurrency 1000` | 100 | 100 / 100 | 4,579 | 170 / 396 | 0 |
| v3 Redis | `--total 50000 --concurrency 1000` | 100 | 100 / 100 | 3,675 | 224 / 441 | 0 |
| v3 Redis | `--total 100000 --concurrency 1000` | 100 | 100 / 100 | 5,015 | 158 / 560 | 0 |


## Troubleshooting

- **Every request returns 500 in v3.** The ticket codes in Redis don't match Postgres, or Redis is left over from an earlier run. Run `./reset.sh`.
- **`Sold Out` immediately.** The last run's state wasn't reset. Run `./reset.sh` (v3) or `db/reset.sql` (v1/v2).
- **`psql` asks for a password.** Export `PGPASSWORD` (see setup).
- **Port already in use.** A previous instance is still running. Use `pkill -f naive-service` or `lsof -i :8080`.
