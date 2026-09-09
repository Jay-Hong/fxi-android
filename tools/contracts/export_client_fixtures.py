#!/usr/bin/env python3
"""Export the Android v2 contract corpus from the server's executable contract.

The checked-in corpus is what ordinary Android CI consumes.  This exporter is a
manual refresh/check tool and deliberately does not make the Android build depend
on the sibling Python repository.

Every manifest entry says how it was produced.  In particular, a Pydantic model
or pure builder is never labelled as an HTTP route, and external proxy responses
are kept separate from application-owned JSON.
"""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import copy
import hashlib
import json
import logging
import logging.handlers
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from types import SimpleNamespace
from typing import Any, Callable, Iterable
from unittest.mock import AsyncMock, patch


FIXED_KST = datetime(2026, 8, 31, 10, 20, tzinfo=timezone(timedelta(hours=9)))
FIXED_UTC_NAIVE = FIXED_KST.astimezone(timezone.utc).replace(tzinfo=None)
FIXTURE_SCHEMA_VERSION = 1


def canonical_json(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode()


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def git_output(repo: Path, *args: str) -> str:
    return subprocess.check_output(
        ["git", "-C", str(repo), *args], text=True
    ).strip()


@dataclass(frozen=True)
class Fixture:
    scenario_id: str
    path: str
    family: str
    body: bytes
    origin: str
    producer: str
    runtime_reachability: str
    config: dict[str, Any]
    media_type: str = "application/json"
    source: dict[str, Any] = field(default_factory=dict)
    derived_from: str | None = None
    mutation: str | None = None

    def manifest_entry(self) -> dict[str, Any]:
        entry: dict[str, Any] = {
            "id": self.scenario_id,
            "path": self.path,
            "family": self.family,
            "mediaType": self.media_type,
            "origin": self.origin,
            "producer": self.producer,
            "runtimeReachability": self.runtime_reachability,
            "config": self.config,
            "source": self.source,
            "sha256": sha256(self.body),
        }
        if self.derived_from is not None:
            entry["derivedFrom"] = self.derived_from
        if self.mutation is not None:
            entry["mutation"] = self.mutation
        return entry


class Corpus:
    def __init__(self) -> None:
        self._fixtures: list[Fixture] = []

    @property
    def fixtures(self) -> tuple[Fixture, ...]:
        return tuple(self._fixtures)

    def add_json(
        self,
        scenario_id: str,
        path: str,
        family: str,
        value: Any,
        *,
        origin: str,
        producer: str,
        runtime_reachability: str = "runtime",
        config: dict[str, Any] | None = None,
        source: dict[str, Any] | None = None,
        derived_from: str | None = None,
        mutation: str | None = None,
    ) -> None:
        self.add_raw(
            scenario_id,
            path,
            family,
            canonical_json(value),
            origin=origin,
            producer=producer,
            runtime_reachability=runtime_reachability,
            config=config,
            source=source,
            derived_from=derived_from,
            mutation=mutation,
        )

    def add_raw(
        self,
        scenario_id: str,
        path: str,
        family: str,
        body: bytes,
        *,
        origin: str,
        producer: str,
        runtime_reachability: str,
        config: dict[str, Any] | None = None,
        source: dict[str, Any] | None = None,
        media_type: str = "application/json",
        derived_from: str | None = None,
        mutation: str | None = None,
    ) -> None:
        self._fixtures.append(
            Fixture(
                scenario_id=scenario_id,
                path=path,
                family=family,
                body=body,
                origin=origin,
                producer=producer,
                runtime_reachability=runtime_reachability,
                config=config or not_applicable_config(),
                source=source or {},
                media_type=media_type,
                derived_from=derived_from,
                mutation=mutation,
            )
        )

    def body_json(self, scenario_id: str) -> Any:
        fixture = next(item for item in self._fixtures if item.scenario_id == scenario_id)
        return json.loads(fixture.body)


def not_applicable_config() -> dict[str, str]:
    return {
        "authStage": "not-applicable",
        "g1": "not-applicable",
        "g2": "not-applicable",
        "g3": "not-applicable",
        "premium": "not-applicable",
    }


def topic_config(
    stage: str,
    *,
    g1: str = "on",
    g2: str = "on",
    g3: str = "on",
    premium: str = "active",
) -> dict[str, str]:
    return {
        "authStage": stage,
        "g1": g1,
        "g2": g2,
        "g3": g3,
        "premium": premium,
    }


@contextlib.contextmanager
def isolated_server_import(server_repo: Path, database_url: str):
    """Import app.* without reading .env or opening server log files."""

    import dotenv

    old_env = os.environ.copy()
    old_path = list(sys.path)
    old_cwd = Path.cwd()
    old_handler = logging.handlers.RotatingFileHandler
    old_load_dotenv = dotenv.load_dotenv
    try:
        # Ambient rollout flags must not silently change the golden corpus.
        # The exporter supplies every environment input it intentionally uses.
        os.environ.clear()
        os.environ.update(
            {
                "DATABASE_URL": database_url,
                "DB_WORKLOAD_PROFILE": "online",
                "ENV": "test",
                "LOG_LEVEL": "WARNING",
            }
        )
        dotenv.load_dotenv = lambda *args, **kwargs: False
        logging.handlers.RotatingFileHandler = (  # type: ignore[assignment]
            lambda *args, **kwargs: logging.NullHandler()
        )
        sys.path.insert(0, str(server_repo))
        os.chdir(server_repo)
        yield
    finally:
        dotenv.load_dotenv = old_load_dotenv
        logging.handlers.RotatingFileHandler = old_handler
        sys.path[:] = old_path
        os.chdir(old_cwd)
        os.environ.clear()
        os.environ.update(old_env)


def seed_database() -> Any:
    from app import database, models
    from app.free_snapshot import basis_as_of

    models.Base.metadata.create_all(database.engine)
    db = database.SessionLocal()
    # Free 1d snapshots are frozen to the previous HH:30 boundary.  Seed the
    # preceding closed ten-minute bucket, not that boundary's in-progress
    # bucket, so the server's non-empty invariant is exercised honestly.
    basis = (
        basis_as_of(FIXED_KST) - timedelta(minutes=10)
    ).astimezone(timezone.utc).replace(tzinfo=None)

    banks = ("kb", "hana", "shinhan", "woori", "ibk", "nh", "sc", "bs")
    currencies = ("usd-krw", "jpy-krw", "eur-krw")
    for currency_index, currency in enumerate(currencies):
        base = (1388.0, 9.45, 10.31)[currency_index]
        db.add(
            models.InvestingExchangeRate(
                currency=currency, rate=base, timestamp=basis
            )
        )
        for bank_index, bank in enumerate(banks):
            db.add(
                models.BankExchangeRate(
                    bank=bank,
                    currency=currency,
                    rate=base + bank_index * 0.1,
                    timestamp=basis + timedelta(seconds=bank_index),
                )
            )

    for index, source in enumerate(("upbit", "bithumb", "coinone", "korbit", "gopax")):
        db.add(
            models.SourceRate(
                source=source,
                asset="usdt-krw",
                rate=1392.0 + index * 0.2,
                timestamp=basis + timedelta(seconds=index),
            )
        )
    db.add(
        models.SourceRate(
            source="krx",
            asset="usd-krw-futures",
            rate=1397.5,
            timestamp=basis,
        )
    )

    daily_specs = (
        ("investing", "usd-krw", 1388.0, "investing_observed_eod", "observed_rollup", None),
        ("hana", "usd-krw", 1389.0, "hana_official_historical_backfill", "external_backfill", None),
        ("hana", "jpy-krw", 9.5, "hana_observed_eod", "observed_rollup", None),
        ("hana", "eur-krw", 10.4, "hana_observed_eod", "observed_rollup", None),
        ("investing", "jpy-krw", 9.45, "investing_observed_eod", "observed_rollup", None),
        ("investing", "eur-krw", 10.31, "investing_observed_eod", "observed_rollup", None),
        ("bithumb", "usdt-krw", 1392.4, "source_candle_close", "bithumb_candlestick_api", None),
        ("krx", "usd-krw-futures", 1397.5, "krx_cf_close_1545", "krx_openapi_daily", "A75609"),
    )
    for day_offset in (100, 45, 2):
        d = FIXED_KST.date() - timedelta(days=day_offset)
        for source, asset, rate, close_basis, source_method, contract in daily_specs:
            db.add(
                models.SourceDailyRate(
                    source=source,
                    asset=asset,
                    date_kst=d,
                    rate=Decimal(str(rate + day_offset / 1000)),
                    high=Decimal(str(rate + 0.8)),
                    low=Decimal(str(rate - 0.8)),
                    close=Decimal(str(rate + day_offset / 1000)),
                    ohlc_quality="observed_rollup",
                    close_basis=close_basis,
                    source_method=source_method,
                    contract_code=contract,
                )
            )

    hourly_specs = tuple(item for item in daily_specs if item[0] != "krx") + (
        ("krx", "usd-krw-futures", 1397.5, "krx_observed_hourly", "observed_rollup", "A75609"),
    )
    for hour_offset in (170, 48, 2):
        bucket = (FIXED_KST - timedelta(hours=hour_offset)).replace(
            minute=0, second=0, microsecond=0, tzinfo=None
        )
        for source, asset, rate, close_basis, source_method, contract in hourly_specs:
            db.add(
                models.SourceHourlyRate(
                    source=source,
                    asset=asset,
                    bucket_ts_kst=bucket,
                    rate=Decimal(str(rate + hour_offset / 1000)),
                    high=Decimal(str(rate + 0.6)),
                    low=Decimal(str(rate - 0.6)),
                    close=Decimal(str(rate + hour_offset / 1000)),
                    ohlc_quality="observed_rollup",
                    close_basis=close_basis,
                    source_method=source_method,
                    contract_code=contract,
                )
            )

    for granularity, moments in (
        ("daily", (FIXED_UTC_NAIVE - timedelta(days=100), FIXED_UTC_NAIVE - timedelta(days=2))),
        ("hourly", (FIXED_UTC_NAIVE - timedelta(hours=170), FIXED_UTC_NAIVE - timedelta(hours=2))),
    ):
        for index, moment in enumerate(moments):
            db.add(
                models.MarketIndexRate(
                    instrument="dxy",
                    source="investing",
                    rate=103.1 + index * 0.2,
                    timestamp=moment,
                    granularity=granularity,
                )
            )

    # The 1d graph readers consume only realtime market_index_rates.  Keep both
    # index-axis series non-empty so the checked corpus exercises their real
    # bucket/provenance path instead of merely freezing an empty schema shell.
    for instrument, rate in (("dxy", 103.4), ("dxy_futures", 103.7)):
        db.add(
            models.MarketIndexRate(
                instrument=instrument,
                source="investing",
                rate=rate,
                timestamp=basis,
                granularity="realtime",
            )
        )
    db.commit()
    return db


def frozen_datetime_class():
    class FrozenDateTime(datetime):
        @classmethod
        def now(cls, tz=None):
            value = FIXED_KST if tz is not None else FIXED_UTC_NAIVE
            return value.astimezone(tz) if tz is not None else value

    return FrozenDateTime


def add_free_and_graph_fixtures(corpus: Corpus, db: Any) -> None:
    from fastapi.testclient import TestClient

    from app import free_snapshot, graph_v2, graph_v2_intraday, main

    frozen = frozen_datetime_class()
    with patch.object(free_snapshot, "datetime", frozen), patch.object(
        graph_v2, "datetime", frozen
    ), patch.object(graph_v2_intraday, "datetime", frozen):
        # Do not enter TestClient as a context manager: that would start the
        # server lifespan (Redis/Firebase/scheduler).  Plain requests still
        # traverse ASGI routing, dependency binding and response serialization.
        client = TestClient(main.app)
        try:
            for tab in ("usd", "jpy", "eur", "tether"):
                for period in ("1d", "1w", "3m", "1y"):
                    canonical = free_snapshot.build_free_snapshot_payload(
                        db, tab, period, now_kst=FIXED_KST
                    )
                    if not free_snapshot.validate_snapshot_payload(
                        canonical, tab, period
                    ):
                        raise RuntimeError(
                            f"server rejected generated free fixture {tab}/{period}"
                        )
                    with patch.object(
                        main,
                        "verify_firebase_token",
                        new=AsyncMock(return_value="fixture-user"),
                    ), patch.object(
                        main,
                        "_free_snapshot_cache_get",
                        new=AsyncMock(return_value=canonical),
                    ), patch.object(
                        main, "_free_snapshot_local_put", new=lambda *_args: None
                    ):
                        response = client.get(
                            "/api/v2/free/snapshot",
                            params={"tab": tab, "period": period},
                            headers={"Authorization": "Bearer fixture-token"},
                        )
                    if response.status_code != 200:
                        raise RuntimeError(
                            f"free route capture failed {tab}/{period}: "
                            f"{response.status_code} {response.text}"
                        )
                    if response.headers.get("cache-control") != "no-store":
                        raise RuntimeError(
                            f"free route lost no-store {tab}/{period}: {response.headers}"
                        )
                    payload = response.json()
                    corpus.add_json(
                        f"free-{tab}-{period}",
                        f"free/{tab}-{period}.json",
                        "free-snapshot",
                        payload,
                        origin="route",
                        producer="app.main.get_v2_free_snapshot",
                        source={
                            "modules": [
                                "app/main.py",
                                "app/free_snapshot.py",
                                "app/graph_v2.py",
                            ],
                            "route": "GET /api/v2/free/snapshot",
                            "routeTraversed": True,
                            "seedOrigin": (
                                "app.free_snapshot.build_free_snapshot_payload + "
                                "validate_snapshot_payload"
                            ),
                            "dependencyPatches": [
                                "verify_firebase_token",
                                "_free_snapshot_cache_get",
                                "_free_snapshot_local_put",
                            ],
                            "http": {
                                "method": "GET",
                                "path": (
                                    f"/api/v2/free/snapshot?tab={tab}&period={period}"
                                ),
                                "status": 200,
                                "headers": {"Cache-Control": "no-store"},
                            },
                        },
                    )
        finally:
            client.close()

        corpus.add_json(
            "graph-catalog-krx-hidden",
            "graph/catalog-krx-hidden.json",
            "graph-v2",
            graph_v2.build_catalog(krx_visible=False),
            origin="builder",
            producer="app.graph_v2.build_catalog",
            source={"module": "app/graph_v2.py", "routeTraversed": False},
            config=topic_config("not-applicable", g1="off", g2="on", g3="on"),
        )
        corpus.add_json(
            "graph-catalog-krx-visible",
            "graph/catalog-krx-visible.json",
            "graph-v2",
            graph_v2.build_catalog(krx_visible=True),
            origin="builder",
            producer="app.graph_v2.build_catalog",
            source={"module": "app/graph_v2.py", "routeTraversed": False},
            config=topic_config("not-applicable", g1="on", g2="on", g3="on"),
        )

        for tab, period, visible in (
            ("usd", "3m", False),
            ("usd", "3m", True),
            ("tether", "1w", False),
        ):
            payload = graph_v2.build_tab(
                db,
                tab,
                period,
                today_kst=FIXED_KST.date(),
                krx_visible=visible,
            )
            payload = graph_v2.attach_graph_v2_domain(
                payload, graph_v2.period_domain(period, FIXED_KST)
            )
            suffix = "visible" if visible else "hidden"
            corpus.add_json(
                f"graph-{tab}-{period}-krx-{suffix}",
                f"graph/{tab}-{period}-krx-{suffix}.json",
                "graph-v2",
                payload,
                origin="builder",
                producer="app.graph_v2.build_tab + attach_graph_v2_domain",
                source={"module": "app/graph_v2.py", "routeTraversed": False},
                config=topic_config(
                    "not-applicable", g1="on" if visible else "off", g2="on", g3="on"
                ),
            )

        for visible in (False, True):
            payload = graph_v2_intraday.build_tab_1d_payload(
                "usd", krx_visible=visible, now_kst=FIXED_KST
            )
            payload["in_progress"] = graph_v2_intraday.build_tab_1d_in_progress(
                "usd", now_kst=FIXED_KST, krx_visible=visible
            )
            payload = graph_v2.attach_graph_v2_domain(
                payload, graph_v2.period_domain("1d", FIXED_KST)
            )
            suffix = "visible" if visible else "hidden"
            corpus.add_json(
                f"graph-usd-1d-krx-{suffix}",
                f"graph/usd-1d-krx-{suffix}.json",
                "graph-v2",
                payload,
                origin="builder",
                producer=(
                    "app.graph_v2_intraday.build_tab_1d_payload + "
                    "build_tab_1d_in_progress + app.graph_v2.attach_graph_v2_domain"
                ),
                source={
                    "modules": ["app/graph_v2_intraday.py", "app/graph_v2.py"],
                    "routeTraversed": False,
                },
                config=topic_config(
                    "not-applicable", g1="on" if visible else "off", g2="on", g3="on"
                ),
            )


def add_topic_snapshot_fixtures(corpus: Corpus) -> None:
    from app.dxy_topic_publisher import build_dxy_topic_payload
    from app.fx_topic_payload import FX_TOPIC_BANK_ORDER, build_fx_tab_payload
    from app.krx_topic_publisher import build_krx_topic_payload
    from app.tether_topic_publisher import TETHER_TOPIC
    from app.usdt_topic_payload import build_tether_tab_payload

    ts = FIXED_KST.isoformat()
    for asset, rate in (("usd-krw", 1388.1), ("jpy-krw", 9.45), ("eur-krw", 10.31)):
        banks = [
            {"bank": bank, "currency": asset, "rate": rate + index / 10, "timestamp": ts}
            for index, bank in enumerate(FX_TOPIC_BANK_ORDER)
        ]
        payload = build_fx_tab_payload(
            asset,
            banks,
            {"bank": "investing", "currency": asset, "rate": rate, "timestamp": ts},
            bank_order=FX_TOPIC_BANK_ORDER,
        )
        payload["topic"] = f"fx:{asset}"
        corpus.add_json(
            f"topic-snapshot-fx-{asset}",
            f"topic/snapshot-fx-{asset}.json",
            "topic",
            payload,
            origin="builder",
            producer=(
                "app.fx_topic_payload.build_fx_tab_payload + "
                "publisher-compatible top-level topic injection"
            ),
            source={
                "modules": [
                    "app/fx_topic_payload.py",
                    "app/fx_topic_publisher.py",
                    "app/topic_initial_snapshot.py",
                ],
                "routeTraversed": False,
                "assembly": (
                    "pure builder output followed by the exact top-level topic "
                    "assignment used by both publisher and initial snapshot paths"
                ),
            },
            config=not_applicable_config(),
        )

    tether = build_tether_tab_payload(
        usdt_rates=[
            {
                "source": source,
                "asset": "usdt-krw",
                "rate": 1392.0 + index / 10,
                "timestamp": ts,
                "rate_changed_at": (FIXED_KST + timedelta(milliseconds=index)).isoformat(),
            }
            for index, source in enumerate(("upbit", "bithumb", "coinone", "korbit", "gopax"))
        ],
        bank_rates=[
            {"bank": "kb", "currency": "usd-krw", "rate": 1388.3, "timestamp": ts},
            {"bank": "hana", "currency": "usd-krw", "rate": 1388.4, "timestamp": ts},
        ],
        investing_rate={
            "bank": "investing", "currency": "usd-krw", "rate": 1388.1, "timestamp": ts
        },
    )
    tether["topic"] = TETHER_TOPIC
    corpus.add_json(
        "topic-snapshot-tether",
        "topic/snapshot-tether.json",
        "topic",
        tether,
        origin="builder",
        producer=(
            "app.usdt_topic_payload.build_tether_tab_payload + "
            "publisher-compatible top-level topic injection"
        ),
        source={
            "modules": [
                "app/usdt_topic_payload.py",
                "app/tether_topic_publisher.py",
                "app/topic_initial_snapshot.py",
            ],
            "routeTraversed": False,
            "assembly": (
                "pure builder output followed by the exact top-level topic "
                "assignment used by both publisher and initial snapshot paths"
            ),
        },
        config=not_applicable_config(),
    )

    krx_entry = {
        "source": "krx",
        "asset": "usd-krw-futures",
        "rate": 1397.5,
        "timestamp": ts,
        "rate_changed_at": (FIXED_KST + timedelta(milliseconds=1)).isoformat(),
    }
    corpus.add_json(
        "topic-snapshot-krx",
        "topic/snapshot-krx.json",
        "topic",
        build_krx_topic_payload(krx_entry),
        origin="builder",
        producer="app.krx_topic_publisher.build_krx_topic_payload",
        source={"module": "app/krx_topic_publisher.py", "routeTraversed": False},
        config=topic_config("enforce_authenticated_premium"),
    )
    corpus.add_json(
        "topic-snapshot-dxy",
        "topic/snapshot-dxy.json",
        "topic",
        build_dxy_topic_payload(
            {"instrument": "dxy", "source": "investing", "rate": 103.4, "timestamp": ts}
        ),
        origin="builder",
        producer="app.dxy_topic_publisher.build_dxy_topic_payload",
        source={"module": "app/dxy_topic_publisher.py", "routeTraversed": False},
        config=not_applicable_config(),
    )


async def capture_dispatch_frame(
    *,
    stage: Any,
    raw: str,
    config_values: dict[str, bool],
    outcome_factory: Callable[[Any], Any] | None = None,
    pre_register: Iterable[str] = (),
) -> dict[str, Any]:
    from app import config, topic_dispatcher, topic_initial_snapshot, topic_policy
    from app.clock import Clock
    from app.topic_auth_rollout import TopicAuthRollout
    from app.topic_wire import ConnectionIdentity

    class WebSocket:
        def __init__(self) -> None:
            self.sent: list[dict[str, Any]] = []

        async def send_json(self, payload: dict[str, Any]) -> None:
            self.sent.append(copy.deepcopy(payload))

        async def close(self, code: int) -> None:
            raise AssertionError(f"unexpected websocket close {code}")

    websocket = WebSocket()
    rollout = TopicAuthRollout(
        stage=stage,
        fx_topics=("fx:usd-krw", "fx:jpy-krw", "fx:eur-krw"),
        usdt_topic="usdt:krw",
        policy_topics=tuple(sorted(topic_policy.TOPIC_POLICY)),
        final_stage_rc_candidate_topics=tuple(sorted(topic_policy.TOPIC_POLICY)),
        started_at_epoch_seconds=0,
    )
    local_registry = topic_dispatcher.TopicRegistry()
    if pre_register:
        local_registry.register(websocket, pre_register)

    ticks = iter((990.0, 1000.0, 1000.0, 1000.0, 1000.0))

    def test_clock() -> Clock:
        return Clock(wall=lambda: FIXED_KST.astimezone(timezone.utc), mono=lambda: next(ticks, 1000.0))

    async def authorize_subscribe(token: str) -> str:
        if token != "fixture-token":
            raise AssertionError("fixture token changed")
        return "fixture-user"

    async def fake_authorize(plan, mono):
        if outcome_factory is None:
            from app.topic_authorization import AuthorizationOutcome

            return AuthorizationOutcome(plan=plan, premium=None, entitlement=None)
        return outcome_factory(plan)

    patches = [
        patch.object(topic_dispatcher, "registry", local_registry),
        patch.object(topic_dispatcher, "lease_clock", test_clock),
        patch.object(topic_dispatcher.uuid, "uuid4", side_effect=[
            type("U", (), {"hex": f"fixture-lease-{index}"})() for index in range(16)
        ]),
        patch.object(topic_dispatcher, "authorize_subscription_plan", new=fake_authorize),
        patch.object(topic_initial_snapshot, "send_initial_snapshots", new=AsyncMock()),
    ]
    for name, value in config_values.items():
        patches.append(patch.object(config, name, value))

    with contextlib.ExitStack() as stack:
        for item in patches:
            stack.enter_context(item)
        await topic_dispatcher.handle_client_message(
            websocket,
            raw,
            authorize_subscribe=authorize_subscribe,
            identity=ConnectionIdentity(),
            topic_auth_rollout=rollout,
        )
    if len(websocket.sent) != 1:
        raise RuntimeError(f"expected one terminal frame, got {websocket.sent!r}")
    return websocket.sent[0]


def add_topic_control_fixtures(corpus: Corpus) -> None:
    from app.config import TopicAuthStage
    from app.topic_authorization import AuthorizationOutcome, Denied, Granted, PremiumGranted
    from app.topic_wire import build_subscription_ack, build_subscription_error

    pong = asyncio.run(
        capture_dispatch_frame(
            stage=TopicAuthStage.COMPATIBILITY,
            raw="ping",
            config_values={},
        )
    )
    corpus.add_json(
        "topic-control-pong",
        "topic/pong.json",
        "topic",
        pong,
        origin="websocket-dispatcher",
        producer="app.topic_dispatcher.handle_client_message('ping')",
        source={
            "module": "app/topic_dispatcher.py",
            "route": "WS /ws",
            "routeTraversed": False,
            "dispatcherTraversed": True,
        },
        config=topic_config(
            "compatibility",
            g1="not-applicable",
            g2="not-applicable",
            g3="not-applicable",
            premium="not-applicable",
        ),
    )

    disabled = asyncio.run(
        capture_dispatch_frame(
            stage=TopicAuthStage.COMPATIBILITY,
            raw=json.dumps(
                {
                    "type": "subscribe",
                    "request_id": "topics-off",
                    "id_token": "fixture-token",
                    "topics": ["fx:usd-krw", "usdt:krw"],
                }
            ),
            config_values={"TOPIC_DISPATCHER_ENABLED": False},
        )
    )
    corpus.add_json(
        "topic-ack-topics-disabled",
        "topic/ack-topics-disabled.json",
        "topic",
        disabled,
        origin="websocket-dispatcher",
        producer="app.topic_dispatcher.handle_client_message",
        source={
            "module": "app/topic_dispatcher.py",
            "route": "WS /ws",
            "routeTraversed": False,
            "dispatcherTraversed": True,
            "runtimeConfig": {"TOPIC_DISPATCHER_ENABLED": False},
        },
        config=topic_config(
            "compatibility",
            g1="not-applicable",
            g2="not-applicable",
            g3="not-applicable",
            premium="not-applicable",
        ),
    )

    def granted(plan):
        premium = PremiumGranted("fixture-user", 980.0) if plan.requires_premium() else None
        entitlement = (
            Granted("fixture-user", 980.0, 970.0) if plan.requires_entitlement() else None
        )
        return AuthorizationOutcome(plan=plan, premium=premium, entitlement=entitlement)

    enforce_all = asyncio.run(
        capture_dispatch_frame(
            stage=TopicAuthStage.ENFORCE_AUTHENTICATED_PREMIUM,
            raw=json.dumps(
                {
                    "type": "subscribe",
                    "request_id": "enforce-all",
                    "id_token": "fixture-token",
                    "topics": ["fx:usd-krw", "usdt:krw", "krx:usd-krw-futures"],
                }
            ),
            config_values={
                "TOPIC_DISPATCHER_ENABLED": True,
                "FX_TOPIC_ENABLED": True,
                "KRX_CLIENT_DISTRIBUTION_EFFECTIVE": True,
            },
            outcome_factory=granted,
        )
    )
    corpus.add_json(
        "topic-ack-enforce-all-granted",
        "topic/ack-enforce-all-granted.json",
        "topic",
        enforce_all,
        origin="websocket-dispatcher",
        producer="app.topic_dispatcher.handle_client_message",
        source={
            "module": "app/topic_dispatcher.py",
            "route": "WS /ws",
            "routeTraversed": False,
            "dispatcherTraversed": True,
        },
        config=topic_config("enforce_authenticated_premium"),
    )

    def premium_denied(plan):
        return AuthorizationOutcome(
            plan=plan, premium=Denied("premium_required"), entitlement=None
        )

    denied = asyncio.run(
        capture_dispatch_frame(
            stage=TopicAuthStage.ENFORCE_AUTHENTICATED_PREMIUM,
            raw=json.dumps(
                {
                    "type": "subscribe",
                    "request_id": "premium-denied",
                    "id_token": "fixture-token",
                    "topics": ["fx:usd-krw", "krx:usd-krw-futures"],
                }
            ),
            config_values={
                "TOPIC_DISPATCHER_ENABLED": True,
                "FX_TOPIC_ENABLED": True,
                "KRX_CLIENT_DISTRIBUTION_EFFECTIVE": True,
            },
            outcome_factory=premium_denied,
        )
    )
    corpus.add_json(
        "topic-ack-premium-denied",
        "topic/ack-premium-denied.json",
        "topic",
        denied,
        origin="websocket-dispatcher",
        producer="app.topic_dispatcher.handle_client_message",
        source={
            "module": "app/topic_dispatcher.py",
            "route": "WS /ws",
            "routeTraversed": False,
            "dispatcherTraversed": True,
        },
        config=topic_config(
            "enforce_authenticated_premium",
            g1="not-applicable",
            premium="inactive",
        ),
    )

    def entitlement_denied(plan):
        return AuthorizationOutcome(
            plan=plan,
            premium=PremiumGranted("fixture-user", 980.0),
            entitlement=Denied("krx_entitlement_required"),
        )

    denied_krx = asyncio.run(
        capture_dispatch_frame(
            stage=TopicAuthStage.ENFORCE_AUTHENTICATED_PREMIUM,
            raw=json.dumps(
                {
                    "type": "subscribe",
                    "request_id": "krx-denied",
                    "id_token": "fixture-token",
                    "topics": ["usdt:krw", "krx:usd-krw-futures"],
                }
            ),
            config_values={
                "TOPIC_DISPATCHER_ENABLED": True,
                "KRX_CLIENT_DISTRIBUTION_EFFECTIVE": True,
            },
            outcome_factory=entitlement_denied,
        )
    )
    corpus.add_json(
        "topic-ack-krx-denied",
        "topic/ack-krx-denied.json",
        "topic",
        denied_krx,
        origin="websocket-dispatcher",
        producer="app.topic_dispatcher.handle_client_message",
        source={
            "module": "app/topic_dispatcher.py",
            "route": "WS /ws",
            "routeTraversed": False,
            "dispatcherTraversed": True,
        },
        config=topic_config("enforce_authenticated_premium", g1="off"),
    )

    # The vocabulary boundary is executable even when no current dispatch branch emits it.
    for code, retry in (
        ("invalid_token", None),
        ("temporarily_unavailable", 5),
        ("invalid_request", None),
        ("request_too_large", None),
    ):
        corpus.add_json(
            f"topic-error-{code.replace('_', '-')}",
            f"topic/error-{code.replace('_', '-')}.json",
            "topic",
            build_subscription_error(
                request_id=None if code == "invalid_request" else f"error-{code}",
                error=code,
                retry_after_seconds=retry,
            ),
            origin="builder",
            producer="app.topic_wire.build_subscription_error",
            runtime_reachability=("vocabulary-only" if code == "request_too_large" else "runtime"),
            source={"module": "app/topic_wire.py", "routeTraversed": False},
            config=not_applicable_config(),
        )

    corpus.add_json(
        "topic-ack-lease-boundary-900",
        "topic/ack-lease-boundary-900.json",
        "topic",
        build_subscription_ack(
            request_id="lease-boundary",
            operation="subscribe",
            accepted=["fx:usd-krw"],
            rejected=[],
            active=["fx:usd-krw"],
            leases={"fx:usd-krw": ("fixture-boundary-lease", 900)},
        ),
        origin="builder",
        producer="app.topic_wire.build_subscription_ack",
        runtime_reachability="configured",
        source={
            "module": "app/topic_wire.py",
            "note": "exact 900 is a buildable boundary; live elapsed time usually makes it smaller",
            "routeTraversed": False,
        },
        config=topic_config("enforce_authenticated_premium"),
    )


def pydantic_json(model: Any, **kwargs: Any) -> dict[str, Any]:
    return json.loads(model.model_dump_json(**kwargs))


def add_alert_schema_fixtures(corpus: Corpus) -> None:
    from app import schemas
    from app.notifications.comparison_evaluator import spread_matches

    created = "2026-08-31T10:00:00+09:00"
    families = (
        (
            "bank",
            schemas.NotificationSettingRequest,
            {"bank": "hana", "currency": "usd-krw", "condition": "above", "threshold": 1390.0},
            schemas.NotificationSettingUpdateRequest,
            schemas.NotificationSettingResponse,
            schemas.NotificationSettingsListResponse,
            {
                "id": 101,
                "user_id": "fixture-user",
                "bank": "hana",
                "currency": "usd-krw",
                "condition": "above",
                "threshold": 1390.0,
                "is_enabled": True,
                "triggered": False,
                "repeat_interval_sec": None,
                "created_at": created,
            },
            schemas.NotificationLogResponse,
            schemas.NotificationLogsListResponse,
            {
                "id": 501,
                "setting_id": 101,
                "bank": "hana",
                "currency": "usd-krw",
                "condition": "above",
                "threshold": 1390.0,
                "rate": 1391.2,
                "sent_at": created,
            },
        ),
        (
            "source",
            schemas.SourceNotificationSettingRequest,
            {"source": "upbit", "asset": "usdt-krw", "condition": "below", "threshold": 1385.0},
            schemas.SourceNotificationSettingUpdateRequest,
            schemas.SourceNotificationSettingResponse,
            schemas.SourceNotificationSettingsListResponse,
            {
                "id": 102,
                "user_id": "fixture-user",
                "source": "upbit",
                "asset": "usdt-krw",
                "condition": "below",
                "threshold": 1385.0,
                "is_enabled": True,
                "triggered": False,
                "repeat_interval_sec": None,
                "created_at": created,
            },
            schemas.SourceNotificationLogResponse,
            schemas.SourceNotificationLogsListResponse,
            {
                "id": 502,
                "setting_id": 102,
                "source": "upbit",
                "asset": "usdt-krw",
                "condition": "below",
                "threshold": 1385.0,
                "triggered_rate": 1384.8,
                "sent_at": created,
            },
        ),
    )
    for (
        name,
        request_type,
        request_data,
        update_type,
        response_type,
        settings_list_type,
        response_data,
        log_type,
        logs_list_type,
        log_data,
    ) in families:
        request = request_type(**request_data)
        repeat_request = request_type(**request_data, repeat_interval_sec=300)
        response = response_type(**response_data)
        log = log_type(**log_data)
        for suffix, value in (
            ("create-once-request", pydantic_json(request)),
            ("create-repeat-request", pydantic_json(repeat_request)),
            ("setting-response", pydantic_json(response)),
            ("history-item", pydantic_json(log)),
        ):
            corpus.add_json(
                f"alert-{name}-{suffix}",
                f"alerts/{name}-{suffix}.json",
                "alerts",
                value,
                origin="pydantic-schema",
                producer=f"app.schemas.{type(request if 'request' in suffix else response if 'setting' in suffix else log).__name__}",
                source={"module": "app/schemas.py", "routeTraversed": False},
            )
        for suffix, update in (
            ("update-omitted", update_type()),
            ("update-once-null", update_type(repeat_interval_sec=None)),
            ("update-repeat", update_type(repeat_interval_sec=300)),
        ):
            corpus.add_json(
                f"alert-{name}-{suffix}",
                f"alerts/{name}-{suffix}.json",
                "alerts",
                pydantic_json(update, exclude_unset=True),
                origin="pydantic-schema",
                producer=f"app.schemas.{type(update).__name__}.model_fields_set",
                source={
                    "module": "app/schemas.py",
                    "routeTraversed": False,
                    "fieldsSet": sorted(update.model_fields_set),
                },
            )
        corpus.add_json(
            f"alert-{name}-settings-list",
            f"alerts/{name}-settings-list.json",
            "alerts",
            pydantic_json(settings_list_type(settings=[response], total_count=1)),
            origin="pydantic-schema",
            producer=f"app.schemas.{settings_list_type.__name__}",
            source={"module": "app/schemas.py", "routeTraversed": False},
        )
        corpus.add_json(
            f"alert-{name}-history-list",
            f"alerts/{name}-history-list.json",
            "alerts",
            pydantic_json(logs_list_type(logs=[log], total_count=1)),
            origin="pydantic-schema",
            producer=f"app.schemas.{logs_list_type.__name__}",
            source={"module": "app/schemas.py", "routeTraversed": False},
        )

    absolute = schemas.ComparisonAlertRequest(
        tab="usd",
        left_source="krx",
        left_asset="usd-krw-futures",
        right_source="hana",
        right_asset="usd-krw",
        diff_type="absolute",
        operator="gte",
        threshold=10.0,
        repeat_interval_sec=None,
    )
    signed = schemas.ComparisonAlertRequest(
        tab="tether",
        left_source="upbit",
        left_asset="usdt-krw",
        right_source="krx",
        right_asset="usd-krw-futures",
        diff_type="signed",
        operator="lte",
        threshold=-5.0,
        repeat_interval_sec=300,
    )
    response = schemas.ComparisonAlertResponse(
        id=103,
        user_id="fixture-user",
        **pydantic_json(absolute),
        triggered=False,
        last_notified_spread=None,
        created_at=created,
    )
    comparison_history_left_rate = 1400.0
    comparison_history_right_rate = 1390.0
    comparison_history_spread = comparison_history_left_rate - comparison_history_right_rate
    if not spread_matches("absolute", "gte", 10.0, comparison_history_spread):
        raise RuntimeError("comparison history fixture does not represent a triggered delivery")
    history = schemas.ComparisonNotificationLogResponse(
        id=503,
        setting_id=103,
        tab="usd",
        left_source="krx",
        left_asset="usd-krw-futures",
        right_source="hana",
        right_asset="usd-krw",
        diff_type="absolute",
        operator="gte",
        threshold=10.0,
        left_rate=comparison_history_left_rate,
        right_rate=comparison_history_right_rate,
        spread=comparison_history_spread,
        is_repeat=False,
        sent_at=created,
    )
    for suffix, value, producer in (
        ("absolute-create-request", pydantic_json(absolute), "ComparisonAlertRequest"),
        ("signed-create-request", pydantic_json(signed), "ComparisonAlertRequest"),
        ("setting-response", pydantic_json(response), "ComparisonAlertResponse"),
        ("history-item", pydantic_json(history), "ComparisonNotificationLogResponse"),
    ):
        corpus.add_json(
            f"alert-comparison-{suffix}",
            f"alerts/comparison-{suffix}.json",
            "alerts",
            value,
            origin="pydantic-schema",
            producer=f"app.schemas.{producer}",
            source={
                "module": "app/schemas.py",
                "routeTraversed": False,
                **(
                    {
                        "semanticValidator": (
                            "app.notifications.comparison_evaluator.spread_matches"
                        )
                    }
                    if suffix == "history-item"
                    else {}
                ),
            },
        )

    for suffix, update in (
        ("update-omitted", schemas.ComparisonAlertUpdateRequest()),
        (
            "update-once-null",
            schemas.ComparisonAlertUpdateRequest(repeat_interval_sec=None),
        ),
        (
            "update-repeat",
            schemas.ComparisonAlertUpdateRequest(repeat_interval_sec=300),
        ),
    ):
        corpus.add_json(
            f"alert-comparison-{suffix}",
            f"alerts/comparison-{suffix}.json",
            "alerts",
            pydantic_json(update, exclude_unset=True),
            origin="pydantic-schema",
            producer=(
                "app.schemas.ComparisonAlertUpdateRequest.model_fields_set"
            ),
            source={
                "module": "app/schemas.py",
                "routeTraversed": False,
                "fieldsSet": sorted(update.model_fields_set),
            },
        )
    corpus.add_json(
        "alert-comparison-settings-list",
        "alerts/comparison-settings-list.json",
        "alerts",
        pydantic_json(
            schemas.ComparisonAlertsListResponse(alerts=[response], total_count=1)
        ),
        origin="pydantic-schema",
        producer="app.schemas.ComparisonAlertsListResponse",
        source={"module": "app/schemas.py", "routeTraversed": False},
    )
    corpus.add_json(
        "alert-comparison-history-list",
        "alerts/comparison-history-list.json",
        "alerts",
        pydantic_json(
            schemas.ComparisonNotificationLogsListResponse(
                logs=[history], total_count=1
            )
        ),
        origin="pydantic-schema",
        producer="app.schemas.ComparisonNotificationLogsListResponse",
        source={
            "module": "app/schemas.py",
            "routeTraversed": False,
            "semanticValidator": (
                "app.notifications.comparison_evaluator.spread_matches"
            ),
        },
    )

    for suffix, value in (
        ("pending", {"krx_visible": False, "premium_active": False, "premium_pending": True, "retry_after_seconds": 5}),
        ("inactive", {"krx_visible": False, "premium_active": False, "premium_pending": False, "retry_after_seconds": None}),
        ("active-krx-hidden", {"krx_visible": False, "premium_active": True, "premium_pending": False, "retry_after_seconds": None}),
        ("active-krx-visible", {"krx_visible": True, "premium_active": True, "premium_pending": False, "retry_after_seconds": None}),
    ):
        value = pydantic_json(schemas.EntitlementsResponse(**value))
        premium = (
            "active"
            if value["premium_active"]
            else "pending"
            if value["premium_pending"]
            else "inactive"
        )
        if value["premium_active"]:
            config_axes = topic_config(
                "not-applicable",
                g1="on" if value["krx_visible"] else "off",
                premium=premium,
            )
        else:
            # The route returns pending before consulting G1/G2/G3, and an
            # inactive premium result short-circuits KRX visibility.  Recording
            # concrete gate values here would claim dependencies never read.
            config_axes = not_applicable_config()
            config_axes["premium"] = premium
        corpus.add_json(
            f"entitlements-{suffix}",
            f"http/entitlements-{suffix}.json",
            "http",
            value,
            origin="pydantic-schema",
            producer="app.schemas.EntitlementsResponse",
            source={
                "module": "app/schemas.py",
                "route": "GET /api/entitlements",
                "routeTraversed": False,
                "http": {"method": "GET", "path": "/api/entitlements", "status": 200, "headers": {}},
            },
            config=config_axes,
        )


def add_fcm_fixtures(corpus: Corpus) -> None:
    from app import config as app_config
    from app import crud, main
    from app.notifications import comparison_evaluator, fcm
    from app.notifications.alert_evaluator import CachedAlertSetting
    from app.notifications.alert_storage_backend import FxNotificationBackend, SourceAlertBackend
    from app.notifications.comparison_evaluator import (
        ComparisonAlertEvaluator,
        ComparisonCandidate,
        FreshComparisonSnapshot,
        UnifiedRate,
    )
    from app.notifications.fcm import _normalize_data_payload

    def add_visible(
        name: str,
        value: tuple[str, str, dict[str, str]],
        producer: str,
        *,
        config: dict[str, Any] | None = None,
        source: dict[str, Any] | None = None,
    ) -> None:
        title, body, data = value
        normalized = _normalize_data_payload(title, body, data)
        corpus.add_json(
            f"fcm-{name}",
            f"fcm/{name}.json",
            "fcm",
            {"notification": {"title": title, "body": body}, "data": normalized},
            origin="builder",
            producer=f"{producer} + app.notifications.fcm._normalize_data_payload",
            source={
                "routeTraversed": False,
                "dataOnly": False,
                "clientVisibleProjection": True,
                "firebaseSdkTransportCaptured": False,
                "firebaseNetworkTraversed": False,
                "projectionBoundary": (
                    "actual payload builder output plus actual _normalize_data_payload; "
                    "equivalent to notification/data fields at send_fcm_multicast_sync "
                    "before SDK send"
                ),
                "omittedTransportFields": [
                    "tokens",
                    "apns",
                    "android.priority",
                    "android.notification.sound",
                ],
                **(source or {}),
            },
            config=config,
        )

    def capture_crud_bank_payload(
        candidate: CachedAlertSetting, triggered_rate: Decimal
    ) -> tuple[str, str, dict[str, str]]:
        """Execute the authoritative bank sender path and capture its FCM args."""

        setting = SimpleNamespace(
            id=candidate.setting_id,
            condition=candidate.condition,
            threshold=candidate.threshold,
            repeat_interval_sec=candidate.repeat_interval_sec,
        )
        device = SimpleNamespace(device_token="fixture-token")
        captured: list[tuple[str, str, dict[str, str]]] = []

        def sender(tokens, title, body, data):
            if tokens != ["fixture-token"]:
                raise RuntimeError(f"unexpected bank FCM tokens: {tokens!r}")
            captured.append((title, body, copy.deepcopy(data)))
            return {"success_count": 1, "failure_count": 0, "failed_tokens": []}

        triggered = [{
            "setting": setting,
            "devices": [device],
            "user_id": "fixture-user",
        }]
        with patch.object(
            crud, "get_triggered_settings_for_rate", return_value=triggered
        ), patch.object(
            crud, "mark_setting_triggered"
        ), patch.object(
            crud, "create_notification_log"
        ), patch.object(
            comparison_evaluator, "emit_comparison_observation"
        ), patch.object(
            fcm, "init_firebase", return_value=True
        ), patch.object(
            fcm, "send_fcm_multicast_sync", new=sender
        ), patch.object(
            app_config, "FX_ALERT_SHADOW_ENABLED", False
        ), patch.object(
            app_config, "FX_ALERT_CUTOVER_CANARY_SETTING_IDS", frozenset()
        ):
            sent = crud.process_rate_alerts(
                object(),
                [{
                    "bank": candidate.source,
                    "currency": candidate.asset,
                    "rate": float(triggered_rate),
                }],
            )
        if sent != 1 or len(captured) != 1:
            raise RuntimeError(
                f"authoritative bank sender was not captured once: sent={sent} "
                f"captures={len(captured)}"
            )
        return captured[0]

    def capture_legacy_source_payload(
        candidate: CachedAlertSetting, triggered_rate: Decimal
    ) -> tuple[str, str, dict[str, str]]:
        """Execute the dormant legacy source sender for compatibility parity."""

        setting = SimpleNamespace(
            id=candidate.setting_id,
            condition=candidate.condition,
            threshold=candidate.threshold,
            repeat_interval_sec=candidate.repeat_interval_sec,
        )
        device = SimpleNamespace(device_token="fixture-token")
        captured: list[tuple[str, str, dict[str, str]]] = []

        def sender(tokens, title, body, data):
            if tokens != ["fixture-token"]:
                raise RuntimeError(f"unexpected source FCM tokens: {tokens!r}")
            captured.append((title, body, copy.deepcopy(data)))
            return {"success_count": 1, "failure_count": 0, "failed_tokens": []}

        triggered = [{
            "setting": setting,
            "devices": [device],
            "user_id": "fixture-user",
        }]
        with patch.object(
            crud, "get_triggered_source_settings_for_rate", return_value=triggered
        ), patch.object(
            crud, "mark_source_setting_triggered"
        ), patch.object(
            crud, "create_source_notification_log"
        ), patch.object(
            fcm, "init_firebase", return_value=True
        ), patch.object(
            fcm, "send_fcm_multicast_sync", new=sender
        ):
            sent = crud.process_source_rate_alerts(
                object(),
                [{
                    "source": candidate.source,
                    "asset": candidate.asset,
                    "rate": float(triggered_rate),
                }],
            )
        if sent != 1 or len(captured) != 1:
            raise RuntimeError(
                f"legacy source sender was not captured once: sent={sent} "
                f"captures={len(captured)}"
            )
        return captured[0]

    def checked_bank_payload(
        candidate: CachedAlertSetting, triggered_rate: Decimal
    ) -> tuple[str, str, dict[str, str]]:
        value = capture_crud_bank_payload(candidate, triggered_rate)
        if value != fx_backend.build_payload(candidate, triggered_rate):
            raise RuntimeError("FxNotificationBackend drifted from authoritative crud wire")
        return value

    def checked_source_payload(
        candidate: CachedAlertSetting, triggered_rate: Decimal
    ) -> tuple[str, str, dict[str, str]]:
        value = source_backend.build_payload(candidate, triggered_rate)
        if value != capture_legacy_source_payload(candidate, triggered_rate):
            raise RuntimeError("SourceAlertBackend drifted from legacy crud compatibility wire")
        return value

    fx_backend = FxNotificationBackend()
    source_backend = SourceAlertBackend()
    add_visible(
        "rate-alert-once",
        checked_bank_payload(
            CachedAlertSetting(101, "fixture-user", "hana", "usd-krw", "above", 1390.0, ("token",), None),
            Decimal("1391.2"),
        ),
        (
            "captured app.crud.process_rate_alerts + parity-checked "
            "app.notifications.alert_storage_backend.FxNotificationBackend.build_payload"
        ),
        source={
            "modules": [
                "app/notifications/alert_storage_backend.py",
                "app/crud.py",
                "app/notifications/fcm.py",
            ],
            "authoritativeRuntime": "app.crud.process_rate_alerts",
            "payloadSendBoundaryCaptured": True,
            "dependencyPatches": [
                "get_triggered_settings_for_rate",
                "mark_setting_triggered",
                "create_notification_log",
                "emit_comparison_observation",
                "init_firebase",
                "send_fcm_multicast_sync",
                "FX_ALERT_SHADOW_ENABLED",
                "FX_ALERT_CUTOVER_CANARY_SETTING_IDS",
            ],
            "shadowParityChecked": True,
        },
    )
    add_visible(
        "rate-alert-repeat",
        checked_bank_payload(
            CachedAlertSetting(104, "fixture-user", "kb", "usd-krw", "below", 1380.0, ("token",), 300),
            Decimal("1379.8"),
        ),
        (
            "captured app.crud.process_rate_alerts + parity-checked "
            "app.notifications.alert_storage_backend.FxNotificationBackend.build_payload"
        ),
        source={
            "modules": [
                "app/notifications/alert_storage_backend.py",
                "app/crud.py",
                "app/notifications/fcm.py",
            ],
            "authoritativeRuntime": "app.crud.process_rate_alerts",
            "payloadSendBoundaryCaptured": True,
            "dependencyPatches": [
                "get_triggered_settings_for_rate",
                "mark_setting_triggered",
                "create_notification_log",
                "emit_comparison_observation",
                "init_firebase",
                "send_fcm_multicast_sync",
                "FX_ALERT_SHADOW_ENABLED",
                "FX_ALERT_CUTOVER_CANARY_SETTING_IDS",
            ],
            "shadowParityChecked": True,
        },
    )
    add_visible(
        "source-alert-once",
        checked_source_payload(
            CachedAlertSetting(102, "fixture-user", "upbit", "usdt-krw", "above", 1390.0, ("token",), None),
            Decimal("1392.2"),
        ),
        (
            "app.notifications.alert_storage_backend.SourceAlertBackend.build_payload + "
            "captured legacy app.crud.process_source_rate_alerts compatibility parity"
        ),
        source={
            "modules": [
                "app/notifications/alert_storage_backend.py",
                "app/crud.py",
                "app/notifications/fcm.py",
            ],
            "authoritativeRuntime": (
                "app.notifications.alert_evaluator.UsdtAlertEvaluator -> "
                "app.notifications.alert_storage_backend.SourceAlertBackend"
            ),
            "authoritativePayloadBuilderExecuted": True,
            "dependencyPatches": [
                "get_triggered_source_settings_for_rate",
                "mark_source_setting_triggered",
                "create_source_notification_log",
                "init_firebase",
                "send_fcm_multicast_sync",
            ],
            "legacyPayloadSendBoundaryCaptured": True,
            "legacyCompatibilityParityChecked": True,
        },
    )
    add_visible(
        "source-alert-repeat-krx",
        checked_source_payload(
            CachedAlertSetting(105, "fixture-user", "krx", "usd-krw-futures", "below", 1400.0, ("token",), 300),
            Decimal("1397.5"),
        ),
        (
            "app.notifications.alert_storage_backend.SourceAlertBackend.build_payload + "
            "captured legacy app.crud.process_source_rate_alerts compatibility parity"
        ),
        config=topic_config("not-applicable"),
        source={
            "modules": [
                "app/notifications/alert_storage_backend.py",
                "app/crud.py",
                "app/notifications/fcm.py",
            ],
            "authoritativeRuntime": (
                "app.notifications.alert_evaluator.KrxAlertEvaluator -> "
                "app.notifications.alert_storage_backend.SourceAlertBackend"
            ),
            "authoritativePayloadBuilderExecuted": True,
            "dependencyPatches": [
                "get_triggered_source_settings_for_rate",
                "mark_source_setting_triggered",
                "create_source_notification_log",
                "init_firebase",
                "send_fcm_multicast_sync",
            ],
            "legacyPayloadSendBoundaryCaptured": True,
            "legacyCompatibilityParityChecked": True,
        },
    )

    candidate_abs = ComparisonCandidate(
        103, "fixture-user", "usd", "krx", "usd-krw-futures", "hana", "usd-krw",
        "absolute", "gte", 8.0, ("token",)
    )
    fresh_abs = FreshComparisonSnapshot(
        103, True, False, "usd", "krx", "usd-krw-futures", "hana", "usd-krw",
        "absolute", "gte", 8.0, None, None
    )
    left = UnifiedRate(1397.5, FIXED_UTC_NAIVE, "db")
    right = UnifiedRate(1388.4, FIXED_UTC_NAIVE, "db")
    add_visible(
        "comparison-alert-absolute-once",
        ComparisonAlertEvaluator._build_payload(fresh_abs, candidate_abs, left, right, 9.1),
        "app.notifications.comparison_evaluator.ComparisonAlertEvaluator._build_payload",
        config=topic_config("not-applicable"),
        source={
            "modules": [
                "app/notifications/comparison_evaluator.py",
                "app/notifications/fcm.py",
            ],
            "authoritativeRuntime": "app.notifications.comparison_evaluator",
        },
    )
    candidate_signed = ComparisonCandidate(
        106, "fixture-user", "tether", "upbit", "usdt-krw", "krx", "usd-krw-futures",
        "signed", "lte", -5.0, ("token",)
    )
    fresh_signed = FreshComparisonSnapshot(
        106, True, False, "tether", "upbit", "usdt-krw", "krx", "usd-krw-futures",
        "signed", "lte", -5.0, 300, None
    )
    signed_left = UnifiedRate(1392.0, FIXED_UTC_NAIVE, "db")
    signed_right = UnifiedRate(1397.5, FIXED_UTC_NAIVE, "db")
    add_visible(
        "comparison-alert-signed-repeat",
        ComparisonAlertEvaluator._build_payload(
            fresh_signed, candidate_signed, signed_left, signed_right, -5.5
        ),
        "app.notifications.comparison_evaluator.ComparisonAlertEvaluator._build_payload",
        config=topic_config("not-applicable"),
        source={
            "modules": [
                "app/notifications/comparison_evaluator.py",
                "app/notifications/fcm.py",
            ],
            "authoritativeRuntime": "app.notifications.comparison_evaluator",
        },
    )
    sync_capture: list[dict[str, str]] = []

    async def capture_sync_sender(*, tokens, data):
        if tokens != ["fixture-token"]:
            raise RuntimeError(f"unexpected sync FCM tokens: {tokens!r}")
        sync_capture.append(copy.deepcopy(data))
        return {"success_count": 1, "failure_count": 0, "failed_tokens": []}

    with patch.object(
        crud,
        "get_devices_by_user",
        return_value=[SimpleNamespace(device_token="fixture-token")],
    ), patch.object(main, "send_fcm_data_only", new=capture_sync_sender):
        asyncio.run(main.notify_user_devices_sync(object(), "fixture-user"))
    if sync_capture != [{"type": "sync_alerts"}]:
        raise RuntimeError(f"sync_alerts sender capture drifted: {sync_capture!r}")

    corpus.add_json(
        "fcm-sync-alerts",
        "fcm/sync-alerts.json",
        "fcm",
        {"data": sync_capture[0]},
        origin="builder",
        producer="captured app.main.notify_user_devices_sync -> send_fcm_data_only args",
        source={
            "module": "app/main.py",
            "routeTraversed": False,
            "dataOnly": True,
            "clientVisibleProjection": True,
            "firebaseSdkTransportCaptured": False,
            "firebaseNetworkTraversed": False,
            "notificationFieldPresent": False,
            "payloadSendBoundaryCaptured": True,
            "dependencyPatches": ["get_devices_by_user", "send_fcm_data_only"],
        },
    )


def add_registry_fixture(corpus: Corpus) -> None:
    from app import graph_v2, source_registry, topic_policy

    sources = [
        {
            "source": item.source,
            "asset": item.asset,
            "category": item.category,
            "sortOrder": item.sort_order,
            "phase1Enabled": item.phase1_enabled,
        }
        for item in source_registry.get_all_sources()
    ]
    # A flat graph_v2.SERIES_REGISTRY covers only long-period readers.  The
    # actual catalog also exposes 1d-only bank/exchange/index identifiers.
    # Export the authoritative tab/period scopes rather than a global union:
    # S2 authorizes graph data by (tab, seriesId), and a flat vocabulary would
    # make a cross-tab identifier look globally admissible.
    catalog = graph_v2.build_catalog(krx_visible=True)
    series_by_tab_period: dict[str, dict[str, list[str]]] = {}
    for tab in catalog["tabs"]:
        tab_id = tab["id"]
        if not isinstance(tab_id, str) or not tab_id or tab_id in series_by_tab_period:
            raise RuntimeError(f"invalid or duplicate graph catalog tab id: {tab_id!r}")
        period_scopes: dict[str, list[str]] = {}
        for period, period_contract in tab["periods"].items():
            all_series = list(period_contract["all_series"])
            defaults = list(period_contract["default_visible_series"])
            if not all_series or any(
                not isinstance(series_id, str) or not series_id
                for series_id in all_series
            ):
                raise RuntimeError(f"empty graph series id in catalog scope {tab_id}/{period}")
            if len(all_series) != len(set(all_series)):
                raise RuntimeError(f"duplicate graph series id in catalog scope {tab_id}/{period}")
            if not set(defaults).issubset(all_series):
                raise RuntimeError(
                    f"default graph series escapes catalog scope {tab_id}/{period}: "
                    f"{sorted(set(defaults) - set(all_series))}"
                )
            period_scopes[period] = all_series
        series_by_tab_period[tab_id] = period_scopes
    corpus.add_json(
        "registry-allowed-identifiers",
        "registry/allowed-identifiers.json",
        "registry",
        {
            "sources": sources,
            "graphSeriesIdsByTabPeriod": series_by_tab_period,
            "topicIds": sorted(topic_policy.TOPIC_POLICY),
        },
        origin="registry",
        producer=(
            "app.source_registry + app.graph_v2.build_catalog(krx_visible=True) + "
            "app.topic_policy"
        ),
        source={
            "modules": [
                "app/source_registry.py",
                "app/graph_v2.py",
                "app/graph_v2_intraday.py",
                "app/topic_policy.py",
            ],
            "routeTraversed": False,
            "authorizationScope": "entitled-superset",
            "builderArguments": {"krx_visible": True},
            "entitlementEvaluationTraversed": False,
            "krxCapabilityCheckRequired": True,
        },
        config=topic_config(
            "not-applicable", g1="on", g2="on", g3="on", premium="active"
        ),
    )


def verify_capture_headers(
    name: str,
    headers: Any,
    *,
    required: Iterable[str] = (),
    forbidden: Iterable[str] = (),
) -> None:
    """Check a captured response's headers against both halves of its contract.

    ``required`` is what the fixture must be able to show; a recorded header proves the
    server sent it.  ``forbidden`` is the other half, and it cannot be checked anywhere
    else: only the headers the capture *asks* for are written to the fixture, so an empty
    recorded map is indistinguishable from a header that was never requested.  Absence has
    to be asserted here, against the live response, or it is not asserted at all.
    """
    missing = [header for header in required if header not in headers]
    if missing:
        raise RuntimeError(f"HTTP route capture lost headers {name}: {sorted(missing)}")
    gained = [header for header in forbidden if header in headers]
    if gained:
        raise RuntimeError(f"HTTP route capture gained headers {name}: {sorted(gained)}")


def add_http_error_fixtures(corpus: Corpus) -> None:
    from fastapi.testclient import TestClient

    from app import config, main
    from app.notifications import fcm
    from app.subscription import PremiumStatus

    valid_bank = {
        "bank": "hana",
        "currency": "usd-krw",
        "condition": "above",
        "threshold": 1390.0,
        "is_enabled": True,
    }
    valid_krx = {
        "source": "krx",
        "asset": "usd-krw-futures",
        "condition": "above",
        "threshold": 1400.0,
        "is_enabled": True,
    }

    client = TestClient(main.app)

    def capture(
        name: str,
        method: str,
        path: str,
        expected_status: int,
        producer: str,
        *,
        request_json: dict[str, Any] | None = None,
        patches: Iterable[Any] = (),
        dependency_patches: Iterable[str] = (),
        contract_headers: Iterable[str] = (),
        forbidden_headers: Iterable[str] = (),
        config_axes: dict[str, Any] | None = None,
        authorization: bool = True,
    ) -> None:
        with contextlib.ExitStack() as stack:
            for item in patches:
                stack.enter_context(item)
            response = client.request(
                method,
                path,
                json=request_json,
                headers=(
                    {"Authorization": "Bearer fixture-token"}
                    if authorization
                    else {}
                ),
            )
        if response.status_code != expected_status:
            raise RuntimeError(
                f"HTTP route capture failed {name}: expected {expected_status}, "
                f"got {response.status_code} {response.text}"
            )
        # Against the live response, before the allowlist narrows it — see
        # `verify_capture_headers` for why absence cannot be checked after this point.
        verify_capture_headers(
            name, response.headers, required=contract_headers, forbidden=forbidden_headers
        )
        selected_headers = {
            header: response.headers[header]
            for header in contract_headers
            if header in response.headers
        }
        corpus.add_json(
            f"http-{name}",
            f"http/{name}.json",
            "http",
            response.json(),
            origin="route",
            producer=producer,
            runtime_reachability="runtime",
            config=config_axes,
            source={
                "module": "app/main.py",
                "routeTraversed": True,
                "dependencyPatches": list(dependency_patches),
                "http": {
                    "method": method,
                    "path": path,
                    "status": expected_status,
                    "headers": selected_headers,
                },
            },
        )

    try:
        # Exercise verify_firebase_token itself.  Only Firebase readiness is
        # patched so the request deterministically reaches the header branch.
        capture(
            "auth-401",
            "GET",
            "/api/entitlements",
            401,
            "ASGI route + app.main.verify_firebase_token missing-header branch",
            patches=(
                patch.object(main, "is_firebase_initialized", return_value=True),
                patch.object(fcm, "rest_auth_app", return_value=object()),
            ),
            dependency_patches=("is_firebase_initialized", "rest_auth_app"),
            authorization=False,
        )
        capture(
            "auth-infrastructure-503",
            "GET",
            "/api/entitlements",
            503,
            "ASGI route + app.main.verify_firebase_token auth-app-unavailable branch",
            patches=(
                patch.object(main, "is_firebase_initialized", return_value=True),
                patch.object(fcm, "rest_auth_app", return_value=None),
            ),
            dependency_patches=("is_firebase_initialized", "rest_auth_app"),
            # The counterpart to premium-pending-503, which does carry `Retry-After: 5`.
            # Both are 503; only one of them tells the client when to come back.
            forbidden_headers=("Retry-After",),
        )

        def auth_patch():
            return patch.object(
                main,
                "verify_firebase_token",
                new=AsyncMock(return_value="fixture-user"),
            )

        def premium_patch(value):
            return patch.object(
                main,
                "verify_premium_status",
                new=AsyncMock(return_value=value),
            )
        capture(
            "premium-inactive-403",
            "POST",
            "/api/notification-settings",
            403,
            "ASGI route + app.main.require_premium(INACTIVE)",
            request_json=valid_bank,
            patches=(auth_patch(), premium_patch(PremiumStatus.INACTIVE)),
            dependency_patches=("verify_firebase_token", "verify_premium_status"),
            config_axes=topic_config(
                "not-applicable",
                g1="not-applicable",
                g2="not-applicable",
                g3="not-applicable",
                premium="inactive",
            ),
        )
        capture(
            "premium-pending-503",
            "POST",
            "/api/notification-settings",
            503,
            "ASGI route + app.main.require_premium(PENDING)",
            request_json=valid_bank,
            patches=(auth_patch(), premium_patch(PremiumStatus.PENDING)),
            dependency_patches=("verify_firebase_token", "verify_premium_status"),
            contract_headers=("Retry-After",),
            config_axes=topic_config(
                "not-applicable",
                g1="not-applicable",
                g2="not-applicable",
                g3="not-applicable",
                premium="pending",
            ),
        )
        capture(
            "validation-422",
            "POST",
            "/api/notification-settings",
            422,
            "ASGI route + FastAPI NotificationSettingRequest validation",
            request_json={
                "bank": "hana",
                "currency": "usd-krw",
                "condition": "above",
            },
        )

        def graph_patches():
            return (
                auth_patch(),
                premium_patch(PremiumStatus.ACTIVE),
                patch.object(config, "KRX_FUTURES_ENABLED", False),
                patch.object(config, "KRX_CLIENT_DISTRIBUTION_ENABLED", False),
            )
        for name, path, status in (
            ("graph-period-400", "/api/v2/graph/tab?tab=usd&period=2y", 400),
            ("graph-tab-404", "/api/v2/graph/tab?tab=unknown&period=3m", 404),
        ):
            capture(
                name,
                "GET",
                path,
                status,
                "ASGI route + app.main.get_v2_graph_tab",
                patches=graph_patches(),
                dependency_patches=(
                    "verify_firebase_token",
                    "verify_premium_status",
                    "KRX_FUTURES_ENABLED",
                    "KRX_CLIENT_DISTRIBUTION_ENABLED",
                ),
                contract_headers=("Cache-Control",),
                config_axes=topic_config(
                    "not-applicable", g1="off", g2="off", g3="off"
                ),
            )

        for name, path, status in (
            ("free-period-400", "/api/v2/free/snapshot?tab=usd&period=2y", 400),
            ("free-tab-404", "/api/v2/free/snapshot?tab=unknown&period=3m", 404),
        ):
            capture(
                name,
                "GET",
                path,
                status,
                "ASGI route + app.main.get_v2_free_snapshot",
                patches=(auth_patch(),),
                dependency_patches=("verify_firebase_token",),
            )
        capture(
            "free-snapshot-503",
            "GET",
            "/api/v2/free/snapshot?tab=usd&period=3m",
            503,
            "ASGI route + app.main.get_v2_free_snapshot cold canonical branch",
            patches=(
                auth_patch(),
                patch.object(
                    main, "_free_snapshot_cache_get", new=AsyncMock(return_value=None)
                ),
                patch.object(main, "_free_snapshot_local_get", return_value=None),
            ),
            dependency_patches=(
                "verify_firebase_token",
                "_free_snapshot_cache_get",
                "_free_snapshot_local_get",
            ),
        )

        capture(
            "krx-distribution-disabled-403",
            "POST",
            "/api/source-notification-settings",
            403,
            "ASGI route + app.entitlements.krx_alert_gate_error",
            request_json=valid_krx,
            patches=(
                auth_patch(),
                premium_patch(PremiumStatus.ACTIVE),
                patch.object(config, "KRX_FUTURES_ENABLED", False),
                patch.object(config, "KRX_CLIENT_DISTRIBUTION_ENABLED", False),
            ),
            dependency_patches=(
                "verify_firebase_token",
                "verify_premium_status",
                "KRX_FUTURES_ENABLED",
                "KRX_CLIENT_DISTRIBUTION_ENABLED",
            ),
            config_axes=topic_config("not-applicable", g1="off", g2="off", g3="off"),
        )
        capture(
            "krx-entitlement-required-403",
            "POST",
            "/api/source-notification-settings",
            403,
            "ASGI route + app.entitlements.krx_alert_gate_error",
            request_json=valid_krx,
            patches=(
                auth_patch(),
                premium_patch(PremiumStatus.ACTIVE),
                patch.object(config, "KRX_FUTURES_ENABLED", True),
                patch.object(config, "KRX_CLIENT_DISTRIBUTION_ENABLED", True),
            ),
            dependency_patches=(
                "verify_firebase_token",
                "verify_premium_status",
                "KRX_FUTURES_ENABLED",
                "KRX_CLIENT_DISTRIBUTION_ENABLED",
            ),
            config_axes=topic_config("not-applicable", g1="off", g2="on", g3="on"),
        )
    finally:
        client.close()

    # nginx's version-dependent HTML is intentionally not pinned byte-for-byte.
    corpus.add_raw(
        "http-proxy-429-html",
        "http/proxy-429.html",
        "http",
        b"<html><body><h1>429 Too Many Requests</h1></body></html>\n",
        origin="external",
        producer="nginx limit_req boundary (representative non-JSON body)",
        runtime_reachability="external",
        media_type="text/html",
        source={
            "module": "nginx/conf.d/default.conf",
            "routeTraversed": False,
            "bodyExact": False,
            "http": {"method": "GET", "path": "/api/rates", "status": 429, "headers": {}},
        },
    )


def add_adversarial_fixtures(corpus: Corpus) -> None:
    base_id = "free-usd-3m"
    base = corpus.body_json(base_id)

    def mutate(scenario_id: str, filename: str, mutation: str, fn: Callable[[dict[str, Any]], None]):
        value = copy.deepcopy(base)
        fn(value)
        corpus.add_json(
            scenario_id,
            f"adversarial/{filename}",
            "adversarial",
            value,
            origin="adversarial",
            producer="deterministic mutation of checked valid fixture",
            runtime_reachability="invalid-input",
            source={"routeTraversed": False},
            derived_from=base_id,
            mutation=mutation,
        )

    def first_series(value: dict[str, Any]) -> dict[str, Any]:
        return value["graph"]["series"][0]

    mutate(
        "adversarial-allowed-id-krx-provenance",
        "allowed-id-krx-provenance.json",
        "set first point source=krx while preserving an allowed series id",
        lambda value: first_series(value)["data"][0].update({"source": "krx"}),
    )
    mutate(
        "adversarial-cross-tab-series",
        "cross-tab-series.json",
        "append one complete tether-only series to the usd tab",
        lambda value: value["graph"]["series"].append(
            copy.deepcopy(
                next(
                    series
                    for series in corpus.body_json("free-tether-3m")["graph"]["series"]
                    if series["id"] == "bithumb.usdt-krw"
                )
            )
        ),
    )
    mutate(
        "adversarial-direct-krx-series",
        "direct-krx-series.json",
        "append one complete krx.usd-krw-futures series to a free graph",
        lambda value: value["graph"]["series"].append(
            copy.deepcopy(
                next(
                    series
                    for series in corpus.body_json(
                        "graph-usd-3m-krx-visible"
                    )["series"]
                    if series["id"] == "krx.usd-krw-futures"
                )
            )
        ),
    )

    tether_id = "free-tether-3m"
    tether = corpus.body_json(tether_id)
    futures_group = copy.deepcopy(
        corpus.body_json("topic-snapshot-krx")["data"]["usd_krw_futures"]
    )
    # Keep freshness valid for the free snapshot.  The sole defect is the
    # forbidden group itself, not a future timestamp hidden inside it.
    futures_group["timestamp"] = tether["as_of"]
    futures_group["rate_changed_at"] = tether["as_of"]
    tether["rate"]["usd_krw_futures"] = futures_group
    corpus.add_json(
        "adversarial-futures-rate-group",
        "adversarial/futures-rate-group.json",
        "adversarial",
        tether,
        origin="adversarial",
        producer="deterministic mutation of checked valid fixture",
        runtime_reachability="invalid-input",
        source={"routeTraversed": False},
        derived_from=tether_id,
        mutation="inject removed usd_krw_futures group into the free tether rate block",
    )

    injected = copy.deepcopy(base)
    live_entry = copy.deepcopy(
        corpus.body_json("graph-usd-1d-krx-visible")["in_progress"][
            "investing.usd"
        ]
    )
    live_entry["bucket_start"] = base["as_of"]
    live_entry["sampled_at"] = base["as_of"]
    injected["graph"]["in_progress"] = {"investing.usd": live_entry}
    corpus.add_json(
        "adversarial-free-in-progress",
        "adversarial/free-in-progress.json",
        "adversarial",
        injected,
        origin="adversarial",
        producer="deterministic mutation of checked valid fixture",
        runtime_reachability="invalid-input",
        source={"routeTraversed": False},
        derived_from=base_id,
        mutation="inject premium live-tail in_progress into a free snapshot",
    )

    ack_base_id = "topic-ack-enforce-all-granted"
    ack_base = next(
        item.body for item in corpus.fixtures if item.scenario_id == ack_base_id
    )
    request_id_line = b'  "request_id": "enforce-all",\n'
    if ack_base.count(request_id_line) != 1:
        raise RuntimeError("canonical ACK request_id line changed")
    raw = ack_base.replace(
        request_id_line,
        request_id_line + b'  "request_id": "attacker-value",\n',
        1,
    )
    corpus.add_raw(
        "adversarial-duplicate-key-first-wins",
        "adversarial/duplicate-key-first-wins.json",
        "adversarial",
        raw,
        origin="adversarial",
        producer="hand-authored raw bytes; JSON serializers cannot preserve duplicate keys",
        runtime_reachability="invalid-input",
        source={"routeTraversed": False, "rawBytesRequired": True},
        derived_from=ack_base_id,
        mutation=(
            "insert exactly one second request_id member into the canonical ACK raw bytes; "
            "the frozen first-wins consumer policy is enforced when S1 replaces the wire decoder"
        ),
    )


def validate_corpus(corpus: Corpus) -> None:
    ids = [item.scenario_id for item in corpus.fixtures]
    paths = [item.path for item in corpus.fixtures]
    if len(ids) != len(set(ids)):
        raise RuntimeError("duplicate fixture scenario id")
    if len(paths) != len(set(paths)):
        raise RuntimeError("duplicate fixture path")
    for item in corpus.fixtures:
        path = Path(item.path)
        if path.is_absolute() or ".." in path.parts or path.as_posix() != item.path:
            raise RuntimeError(f"unsafe fixture path: {item.path}")
        if item.origin == "adversarial" and (
            item.derived_from is None or item.mutation is None
        ):
            raise RuntimeError(f"adversarial fixture lacks provenance: {item.scenario_id}")
        if item.media_type == "application/json" and not item.scenario_id.startswith(
            "adversarial-duplicate-key"
        ):
            json.loads(item.body)


def build_manifest(corpus: Corpus, server_repo: Path) -> dict[str, Any]:
    lock = (server_repo / "requirements.lock.txt").read_bytes()
    return {
        "schemaVersion": FIXTURE_SCHEMA_VERSION,
        "fixtureAsOf": FIXED_KST.isoformat(),
        "server": {
            "repository": "exchange-rate",
            "revision": git_output(server_repo, "rev-parse", "HEAD"),
            "dependencyLock": "requirements.lock.txt",
            "dependencyLockSha256": sha256(lock),
            "contractTreeDirty": bool(
                git_output(
                    server_repo,
                    "status",
                    "--porcelain",
                    "--",
                    "app",
                    "nginx",
                    "tests",
                    "requirements.lock.txt",
                )
            ),
        },
        "fixtures": [item.manifest_entry() for item in sorted(corpus.fixtures, key=lambda x: x.scenario_id)],
    }


def write_tree(root: Path, corpus: Corpus, manifest: dict[str, Any]) -> None:
    root.mkdir(parents=True, exist_ok=True)
    wanted = {item.path for item in corpus.fixtures} | {"MANIFEST.json"}
    if (root / "MANIFEST.json").exists():
        old_manifest = json.loads((root / "MANIFEST.json").read_text())
        for old in old_manifest.get("fixtures", []):
            old_path = old.get("path")
            if isinstance(old_path, str) and old_path not in wanted:
                candidate = root / old_path
                if candidate.is_file() and root in candidate.resolve().parents:
                    candidate.unlink()
    for item in corpus.fixtures:
        target = root / item.path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(item.body)
    (root / "MANIFEST.json").write_bytes(canonical_json(manifest))


def compare_trees(checked: Path, generated: Path) -> list[str]:
    checked_files = {
        path.relative_to(checked).as_posix(): path.read_bytes()
        for path in checked.rglob("*")
        if path.is_file()
    }
    generated_files = {
        path.relative_to(generated).as_posix(): path.read_bytes()
        for path in generated.rglob("*")
        if path.is_file()
    }
    problems = []
    for path in sorted(checked_files.keys() | generated_files.keys()):
        if path not in checked_files:
            problems.append(f"missing checked fixture: {path}")
        elif path not in generated_files:
            problems.append(f"orphan checked fixture: {path}")
        elif checked_files[path] != generated_files[path]:
            problems.append(f"content differs: {path}")
    return problems


def generate(server_repo: Path) -> tuple[Corpus, dict[str, Any]]:
    corpus = Corpus()
    with tempfile.TemporaryDirectory(prefix="fxi-contract-db-") as temp:
        database_url = f"sqlite:///{Path(temp) / 'fixtures.sqlite3'}"
        with isolated_server_import(server_repo, database_url):
            db = seed_database()
            try:
                add_free_and_graph_fixtures(corpus, db)
                add_topic_snapshot_fixtures(corpus)
                add_topic_control_fixtures(corpus)
                add_alert_schema_fixtures(corpus)
                add_fcm_fixtures(corpus)
                add_registry_fixture(corpus)
                add_http_error_fixtures(corpus)
                add_adversarial_fixtures(corpus)
            finally:
                db.close()
    validate_corpus(corpus)
    return corpus, build_manifest(corpus, server_repo)


def parse_args() -> argparse.Namespace:
    script = Path(__file__).resolve()
    android_repo = script.parents[2]
    parser = argparse.ArgumentParser()
    parser.add_argument("--server-repo", type=Path, default=android_repo.parent / "exchange-rate")
    parser.add_argument(
        "--output",
        type=Path,
        default=android_repo / "app/src/test/resources/contracts/v2",
    )
    parser.add_argument("--check", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    server_repo = args.server_repo.resolve()
    output = args.output.resolve()
    if not (server_repo / "app/topic_wire.py").is_file():
        raise SystemExit(f"not an exchange-rate server repo: {server_repo}")

    corpus, manifest = generate(server_repo)
    with tempfile.TemporaryDirectory(prefix="fxi-contract-output-") as temp:
        generated = Path(temp) / "v2"
        write_tree(generated, corpus, manifest)
        if args.check:
            problems = compare_trees(output, generated) if output.exists() else ["checked corpus is absent"]
            if problems:
                for problem in problems:
                    print(problem, file=sys.stderr)
                return 1
        else:
            write_tree(output, corpus, manifest)
    print(f"contract corpus: {len(corpus.fixtures)} fixtures ({'check' if args.check else 'write'} OK)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
