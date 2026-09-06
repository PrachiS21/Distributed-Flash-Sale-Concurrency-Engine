#!/usr/bin/env python3
"""
Thundering-herd load client for the flash-sale service.

Fires N concurrent POST /buy requests at the naive service to prove it
oversells. Each virtual user gets a unique userId so the DB can later be
asked "how many distinct users won a ticket?" (correct answer: <= 100).

Usage:
    python flood.py                          # 50k requests, burst
    python flood.py --total 5000 --concurrency 500
    python flood.py --ramp 60                # spread arrivals over 60s
    python flood.py --url http://localhost:8080/buy
"""

import argparse
import asyncio
import time
from collections import Counter

import aiohttp


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", default="http://localhost:8080/buy",
                   help="buy endpoint (default: %(default)s)")
    p.add_argument("--total", type=int, default=50_000,
                   help="total requests to send (default: %(default)s)")
    p.add_argument("--concurrency", type=int, default=1000,
                   help="max in-flight requests at once (default: %(default)s)")
    p.add_argument("--ramp", type=float, default=0.0,
                   help="seconds to spread request starts over; 0 = fire as fast as possible")
    p.add_argument("--timeout", type=float, default=30.0,
                   help="per-request timeout in seconds (default: %(default)s)")
    p.add_argument("--user-prefix", default="user",
                   help="userId prefix; final id is <prefix><n> (default: %(default)s)")
    return p.parse_args()


async def buy(session: aiohttp.ClientSession, url: str, user_id: str,
              sem: asyncio.Semaphore, timeout: float, stats: dict) -> None:
    async with sem:
        start = time.perf_counter()
        try:
            async with session.post(url, params={"userId": user_id},
                                    timeout=aiohttp.ClientTimeout(total=timeout)) as resp:
                body = await resp.text()
                elapsed = time.perf_counter() - start
                stats["latencies"].append(elapsed)
                stats["http"][resp.status] += 1
                if resp.status == 200 and body.startswith("Success"):
                    stats["outcome"]["purchased"] += 1
                elif "Sold Out" in body:
                    stats["outcome"]["sold_out"] += 1
                else:
                    stats["outcome"]["other"] += 1
                    stats["samples"].append(f"HTTP {resp.status}: {body[:120]}")
        except asyncio.TimeoutError:
            stats["outcome"]["timeout"] += 1
        except aiohttp.ClientError as e:
            stats["outcome"]["conn_error"] += 1
            stats["samples"].append(f"{type(e).__name__}: {e}")


async def run(args: argparse.Namespace) -> dict:
    stats = {
        "http": Counter(),
        "outcome": Counter(),
        "latencies": [],
        "samples": [],
    }
    sem = asyncio.Semaphore(args.concurrency)
    # connector limit slightly above the semaphore so the semaphore is the real gate
    connector = aiohttp.TCPConnector(limit=args.concurrency + 50, limit_per_host=args.concurrency + 50)
    gap = (args.ramp / args.total) if args.ramp > 0 else 0.0

    async with aiohttp.ClientSession(connector=connector) as session:
        tasks = []
        wall_start = time.perf_counter()
        for i in range(1, args.total + 1):
            uid = f"{args.user_prefix}{i}"
            tasks.append(asyncio.create_task(
                buy(session, args.url, uid, sem, args.timeout, stats)))
            if gap:
                await asyncio.sleep(gap)
        await asyncio.gather(*tasks)
        stats["wall"] = time.perf_counter() - wall_start

    return stats


def pct(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    s = sorted(values)
    k = max(0, min(len(s) - 1, int(round((p / 100) * (len(s) - 1)))))
    return s[k]


def report(args: argparse.Namespace, stats: dict) -> None:
    wall = stats["wall"]
    lat = stats["latencies"]
    purchased = stats["outcome"]["purchased"]

    print("\n" + "=" * 56)
    print(f"  target        {args.url}")
    print(f"  requests      {args.total}   concurrency {args.concurrency}   ramp {args.ramp}s")
    print(f"  wall time     {wall:.2f}s   throughput {args.total / wall:,.0f} req/s")
    print("-" * 56)
    print("  HTTP status")
    for code, n in sorted(stats["http"].items()):
        print(f"    {code}: {n}")
    print("  outcomes")
    for name in ("purchased", "sold_out", "other", "timeout", "conn_error"):
        if stats["outcome"][name]:
            print(f"    {name:<11} {stats['outcome'][name]}")
    if lat:
        print("-" * 56)
        print(f"  latency ms    p50 {pct(lat, 50) * 1000:.0f}   "
              f"p95 {pct(lat, 95) * 1000:.0f}   p99 {pct(lat, 99) * 1000:.0f}   "
              f"max {max(lat) * 1000:.0f}")
    print("=" * 56)
    if purchased > 100:
        print(f"  OVERSOLD: service returned {purchased} successful purchases for 100 tickets "
              f"(+{purchased - 100})")
    elif purchased == 100:
        print("  exactly 100 purchases reported by the client — check the DB for the real count")
    else:
        print(f"  {purchased} purchases reported (no oversell seen from the client this run)")
    if stats["samples"]:
        print("\n  sample errors / unexpected bodies:")
        for s in stats["samples"][:5]:
            print(f"    - {s}")
    print("\n  now confirm against the DB:")
    print("    psql -U postgres -d bigB_days -f ../backend/db/check.sql")


def main() -> None:
    args = parse_args()
    stats = asyncio.run(run(args))
    report(args, stats)


if __name__ == "__main__":
    main()
