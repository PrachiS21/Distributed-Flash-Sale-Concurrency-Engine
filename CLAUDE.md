# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this project is

**RACE** is a hands-on concurrency experiment: the "flash sale / thundering herd" problem.
The goal is to sell **exactly 100 tickets** to ~50,000 users who all arrive within about 60 seconds, without overselling.

It is built in phases. Each phase replaces how `/buy` claims a ticket:

| Phase | Commit | Approach | Result |
|---|---|---|---|
| 1. "The Glass House" | `c0a41af` | Naive check-then-act (`findFirstByStatus` → 5 ms sleep → save) inside `@Transactional` | Oversells on purpose |
| 2. Postgres lock | `89a96f0` | `SELECT ... FOR UPDATE SKIP LOCKED` via `TicketRepository.lockNextAvailable` | Correct; limited by the DB/Hikari pool |
| 3. Redis atomic claim | `ed61cf9` (current) | Lua script pops a ticket from a Redis list and records the owner, then writes the sale to Postgres | Correct; supports multiple app instances |
| (planned) | – | Message queue | Named in `README.md`, not started |

`README.md` is the main user-facing doc: problem statement, setup SQL, runbook, and how to run v1/v2 from git worktrees to compare. Keep it in sync when behavior or commands change.
To see an earlier approach, use `git show <commit>:backend/src/main/java/com/flashsale/naive/TicketController.java`.

## Repository layout

```
README.md             Problem statement, versions, setup SQL, runbook, how to run older versions
reset.sh              Resets Redis (refills the ticket list) and Postgres
backend/              Spring Boot service (Maven, artifactId naive-service)
  pom.xml
  db/check.sql        Post-run check: count by status, sold rows vs. distinct winners
  db/reset.sql        Sets every ticket back to AVAILABLE
  src/main/java/com/flashsale/naive/
    NaiveServiceApplication.java   @SpringBootApplication entry point
    Ticket.java                    JPA entity → table `tkt`
    TicketRepository.java          Spring Data repo (incl. the Phase 2 SKIP LOCKED query)
    TicketService.java             claim() logic: Redis Lua claim, then Postgres write, with compensation
    TicketController.java          POST /buy
    RedisConfig.java               Startup Redis seeding + the Lua claim script bean
  src/main/resources/application.properties
  target/             Build output. It IS committed to git (there is no .gitignore)
  PONG, accepting     Stray files left by shell redirect mistakes. Not used by anything
loadtest/
  flood.py            asyncio/aiohttp load generator
  requirements.txt    aiohttp>=3.10
  .venv/              Local virtualenv (not committed)
```

## Tech stack & environment

- macOS on Apple Silicon (M1). All services run locally, with no Docker.
- **Java 21**, **Spring Boot 3.5.6**: web (Tomcat), Data JPA (Hibernate + HikariCP), Data Redis (Lettuce).
- **PostgreSQL 18**: database `bigB_days`, user `postgres`, password from the `DB_PASSWORD` env var (defaults to `postgres`). Hibernate `ddl-auto=none`, so the schema is managed by hand.
- **Redis**: `localhost:6379`, no auth, 2 s timeout.
- **Python 3** with `aiohttp` for load testing.
- There is no Maven wrapper (`mvnw`). Use a system `mvn`.
- There are **no automated tests**. Correctness is checked by load testing and then running `db/check.sql`.

Load limits are set low on purpose (in `application.properties`) so you can watch the server struggle: `server.tomcat.threads.max=200` and `spring.datasource.hikari.maximum-pool-size=10`.

## Data model

Table `tkt` (the DDL and seed SQL are in `README.md`):

| column | type | notes |
|---|---|---|
| `id` | SERIAL PK | |
| `tkt_code` | VARCHAR(32) UNIQUE NOT NULL | Maps to `Ticket.ticketCode` |
| `status` | VARCHAR(20) NOT NULL | `'AVAILABLE'` or `'SOLD'` (plain strings, no enum) |
| `user_id` | VARCHAR(64) | Buyer |
| `purchased_at` | TIMESTAMPTZ | Entity uses `LocalDateTime` |

Redis keys (Phase 3):

- `flash:tickets:available`: a **LIST** of unsold ticket codes.
- `flash:tickets:owner`: a **HASH** mapping `ticket code → userId`.

## How a purchase works now (Phase 3)

`POST /buy?userId=<id>` → `TicketController.buyTicket` → `TicketService.claim(userId)`:

1. Runs the Lua script (bean `claimScript` in `RedisConfig`). Redis executes it atomically: `LPOP available` → if nothing is left, return nil → otherwise `HSET owner code userId` and return the code.
   Because this is one atomic step, Redis alone guarantees no ticket is sold twice, even across several app instances.
2. If the script returns nil → the controller returns **400 `Sold Out`**.
3. Otherwise it loads the ticket by `tkt_code` from Postgres, sets `SOLD`, `userId` and `purchasedAt`, and saves it → **200 `Success: Purchased <code>`**.
4. If the Postgres step throws, the service **compensates**: it removes the hash entry, pushes the code back to the head of the list (`LPUSH`), and rethrows. Spring then returns **500**.

On startup, the `seedRedis` `CommandLineRunner` fills `flash:tickets:available` from every Postgres row with status `AVAILABLE`, but **only if the key doesn't exist**, so a sale already in progress isn't overwritten. Note that Redis deletes a list once its last element is popped. So if you restart after a complete sell-out, the runner seeds again from whatever Postgres still marks `AVAILABLE`.

`TicketRepository.findFirstByStatus` and `lockNextAvailable` are left over from Phases 1 and 2. The current code path doesn't use them.

## Common commands

Build and run (from `backend/`):

```sh
mvn package                                   # → target/naive-service-0.0.1-SNAPSHOT.jar
DB_PASSWORD=... mvn spring-boot:run           # single instance on :8080
DB_PASSWORD=... java -jar target/naive-service-0.0.1-SNAPSHOT.jar --server.port=8081   # extra instance
```

Reset state between runs (from the repo root, with Redis and Postgres running):

```sh
./reset.sh                                    # clears both Redis keys, pushes 100 codes, runs db/reset.sql
psql -U postgres -d bigB_days -f backend/db/reset.sql   # Postgres only
```

Load test (from `loadtest/`):

```sh
python3 -m venv .venv && ./.venv/bin/pip install -r requirements.txt   # first time only
./.venv/bin/python flood.py --total 50000 --concurrency 1000
./.venv/bin/python flood.py --total 100000 --concurrency 2000 --ramp 60 --url http://localhost:8081/buy
```

`flood.py` flags: `--url`, `--total`, `--concurrency` (an asyncio semaphore limit), `--ramp` (seconds to spread request starts over), `--timeout`, and `--user-prefix`. Each request uses a unique userId (`user1`, `user2`, …). The script reports HTTP status counts, outcome counts (purchased / sold_out / other / timeout / conn_error), and p50/p95/p99 latency. It flags `OVERSOLD` if more than 100 purchases succeed.
It sends traffic to **one URL only**. To test several instances, run one copy per port or put a load balancer in front.

Check the results:

```sh
psql -U postgres -d bigB_days -f backend/db/check.sql   # expect SOLD=100 and distinct_winners=100
redis-cli LLEN flash:tickets:available                   # expect 0
redis-cli HLEN flash:tickets:owner                       # expect 100
```

## Gotchas & known issues

- **The CLAUDE.md filename has a space in it** (`CLAUDE. md`). Claude Code only auto-loads a file named exactly `CLAUDE.md`.
- **Ticket codes use 3 digits** (`TICK-001` … `TICK-100`), matching `reset.sh` and the seed SQL in `README.md`. If Redis codes stop matching Postgres, every claim fails `findByTicketCode`, gets compensated, and returns 500. The code goes back to the head of the list, so every later request fails too.
- **Redis and Postgres can drift apart** if you reset only one of them, or if the app is killed partway through a run. Always use `./reset.sh`, which resets both, before a load test. Check they agree first: `LLEN` + `HLEN` in Redis should match `AVAILABLE` / `SOLD` in `db/check.sql`.
- `reset.sh` and `db/reset.sql` only reset rows. They never create the table or seed tickets (see the setup section in `README.md`).
- The Redis and Postgres writes aren't one transaction. If the app crashes between the Lua claim and the DB save, a ticket is claimed in Redis but never marked `SOLD` in Postgres. The design treats Redis as the source of truth for who wins.
- `claim()` isn't `@Transactional`: the find and the save each run in their own short transaction. That's fine because Redis already guarantees a code is claimed only once.
- `spring.cache.type=redis` is set, but no caching is used (no `@EnableCaching`, no cache starter).
- Several files have unused imports (e.g. `PageRequest` and `StringRedisTemplate` in the controller). Harmless.
- `target/` is under version control, so every build dirties `git status`. Avoid committing rebuilt jars/classes unless that's intended.
- Never write the DB password into a tracked file. Always pass it as `DB_PASSWORD` (and `PGPASSWORD` for psql).

## Conventions

- One Java package, `com.flashsale.naive`, which uses constructor injection and no Lombok.
- Each concurrency strategy replaces the previous one in place, and its commit records the phase. Keep that pattern: one commit per strategy with a descriptive message.
- Explanatory comments that describe the concurrency guarantee (see `TicketRepository`, `TicketService`) are encouraged. This is a learning project.
