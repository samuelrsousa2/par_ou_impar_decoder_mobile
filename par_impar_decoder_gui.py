import json
import asyncio
import os
import queue
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, ROUND_HALF_UP

import tkinter as tk
from tkinter import ttk, messagebox
from tkinter.scrolledtext import ScrolledText

import websockets

APP_ID = 122601
DERIV_WS_URL = f"wss://ws.derivws.com/websockets/v3?app_id={APP_ID}"

ALLOWED_SYMBOLS = [
    "R_10", "R_25", "R_50", "R_75", "R_100",
    "1HZ10V", "1HZ25V", "1HZ50V", "1HZ75V", "1HZ100V","RDBULL","RDBEAR"
]

CURRENCY = "USD"
CONFIG_FILE = "par_impar_config.json"


def utc_ts():
    return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")


def round2(x: float) -> float:
    d = Decimal(str(x)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    return float(d)


def digits_parity_map(price_str: str):
    digits = [ch for ch in price_str if ch.isdigit()]
    if not digits:
        return [], None, None
    parities = []
    for ch in digits:
        n = int(ch)
        parities.append("P" if (n % 2 == 0) else "I")
    last_digit = int(digits[-1])
    last_parity = "PAR" if (last_digit % 2 == 0) else "IMPAR"
    return parities, last_digit, last_parity


def all_same_parity(parities):
    if not parities:
        return None
    if all(p == "P" for p in parities):
        return "PAR"
    if all(p == "I" for p in parities):
        return "IMPAR"
    return None


@dataclass
class SignalPlan:
    symbol: str
    direction: str  # "PAR" ou "IMPAR"
    account: str    # "DEMO" ou "REAL"
    base_stake: float
    max_gale: int
    mult: float


class DerivWSClient:
    """
    Cliente WS robusto:
    - connect_forever mantém conexão viva e chama callbacks
    - request() espera resposta (req_id)
    - send_only() envia sem esperar ack (ideal para subscribe que às vezes não responde)
    """
    def __init__(self, url: str, name: str, ui_queue: queue.Queue):
        self.url = url
        self.name = name
        self.ui_queue = ui_queue

        self.ws = None
        self.req_id = 0
        self.pending = {}

        self._lock = None
        self._send_lock = None
        self._connected = None

        self.stop_flag = False
        self.on_message_callbacks = []
        self.on_disconnect_callbacks = []

    def add_message_callback(self, cb):
        self.on_message_callbacks.append(cb)

    def add_disconnect_callback(self, cb):
        self.on_disconnect_callbacks.append(cb)

    def _ensure_async_primitives(self):
        if self._lock is None:
            self._lock = asyncio.Lock()
        if self._send_lock is None:
            self._send_lock = asyncio.Lock()
        if self._connected is None:
            self._connected = asyncio.Event()

    def _ui_log(self, text: str):
        self.ui_queue.put(("log_general", f"{utc_ts()} | {text}"))

    def _clear_connection_state(self):
        try:
            if self._connected is not None:
                self._connected.clear()
        except Exception:
            pass
        self.ws = None

        # quebra futures pendentes
        if self.pending:
            for rid, fut in list(self.pending.items()):
                try:
                    if not fut.done():
                        fut.set_exception(ConnectionError(f"[{self.name}] conexão caiu (req_id={rid})"))
                except Exception:
                    pass
            self.pending.clear()

    async def close(self):
        try:
            if self.ws is not None:
                await self.ws.close()
        except Exception:
            pass
        self._clear_connection_state()

    def stop(self):
        self.stop_flag = True
        self._clear_connection_state()

    async def connect_forever(self):
        self._ensure_async_primitives()
        backoff = 1.0
        while not self.stop_flag:
            try:
                async with websockets.connect(self.url, ping_interval=20, ping_timeout=20) as ws:
                    if self.stop_flag:
                        try:
                            await ws.close()
                        except Exception:
                            pass
                        break

                    self.ws = ws
                    self._connected.set()
                    self._ui_log(f"[{self.name}] Conectado.")
                    backoff = 1.0

                    async for msg in ws:
                        if self.stop_flag:
                            break

                        data = json.loads(msg)

                        rid = data.get("req_id")
                        if rid is not None and rid in self.pending:
                            fut = self.pending.pop(rid)
                            if not fut.done():
                                fut.set_result(data)

                        for cb in self.on_message_callbacks:
                            try:
                                cb(data)
                            except Exception as e:
                                self._ui_log(f"[{self.name}] Erro callback: {e}")

            except asyncio.CancelledError:
                break
            except Exception as e:
                self._clear_connection_state()

                for cb in self.on_disconnect_callbacks:
                    try:
                        cb(self.name, e)
                    except Exception:
                        pass

                if self.stop_flag:
                    break

                self._ui_log(f"[{self.name}] Reconectando... ({e})")
                await asyncio.sleep(backoff)
                backoff = min(backoff * 1.7, 20.0)

        self._clear_connection_state()

    async def _wait_connected_and_open(self, deadline_ts: float):
        self._ensure_async_primitives()
        while True:
            remaining = deadline_ts - time.time()
            if remaining <= 0:
                raise TimeoutError(f"[{self.name}] timeout aguardando conexão")

            if not self._connected.is_set():
                try:
                    await asyncio.wait_for(self._connected.wait(), timeout=remaining)
                except Exception:
                    continue

            ws = self.ws
            if ws is None or getattr(ws, "closed", False):
                self._clear_connection_state()
                await asyncio.sleep(0.05)
                continue
            return ws

    async def send_only(self, payload: dict, timeout=25):
        """
        Envia sem esperar resposta. Útil para subscribe de ticks.
        """
        deadline = time.time() + float(timeout)
        async with self._send_lock:
            ws = await self._wait_connected_and_open(deadline)
            try:
                await ws.send(json.dumps(payload))
            except Exception:
                self._clear_connection_state()
                # tenta uma vez mais dentro do deadline
                ws = await self._wait_connected_and_open(deadline)
                await ws.send(json.dumps(payload))

    async def request(self, payload: dict, timeout=25):

        self._ensure_async_primitives()
        deadline = time.time() + float(timeout)

        async with self._lock:
            while True:
                remaining = deadline - time.time()
                if remaining <= 0:
                    raise TimeoutError(f"[{self.name}] timeout total aguardando conexão/resposta")

                ws = await self._wait_connected_and_open(deadline)

                self.req_id += 1
                rid = self.req_id
                payload["req_id"] = rid

                loop = asyncio.get_running_loop()
                fut = loop.create_future()
                self.pending[rid] = fut

                try:
                    await ws.send(json.dumps(payload))
                except Exception:
                    self.pending.pop(rid, None)
                    self._clear_connection_state()
                    await asyncio.sleep(0.15)
                    continue

                remaining = deadline - time.time()
                if remaining <= 0:
                    self.pending.pop(rid, None)
                    raise TimeoutError(f"[{self.name}] timeout total aguardando resposta")

                try:
                    resp = await asyncio.wait_for(fut, timeout=remaining)
                    return resp
                except Exception:
                    self.pending.pop(rid, None)
                    self._clear_connection_state()
                    await asyncio.sleep(0.15)
                    continue


class TradingEngine:
    def __init__(self, ui_queue: queue.Queue):
        self.ui_queue = ui_queue

        self.public = None
        self.demo = None
        self.real = None

        self.running = False
        self.loop = None

        # tokens (persistidos)
        self._last_demo_token = ""
        self._last_real_token = ""

        # config
        self.virtual_mode = False
        self.vwin_target = 0
        self.vloss_target = 0
        self.vwin_streak = 0
        self.vloss_streak = 0
        self._armed_real_next = False

        self.trigger_mode = "SEQUENCIA"
        self.stake = 1.0
        self.max_gale = 0
        self.mult = 2.0
        self.stop_win = 0.0

        self.busy_trade = False

        # real stats
        self.real_balance = None
        self.real_balance_start = None
        self.real_wins = 0
        self.real_losses = 0
        self.real_profit = 0.0

        # subs/seen
        self._tick_subscribed = set()
        self._tick_seen_events = {}
        self._balance_subscribed = False
        self._balance_seen = asyncio.Event() if asyncio.get_event_loop_policy() else None  # placeholder

        # restart control
        self._restart_in_progress = False
        self._last_restart_ts = 0.0
        self._stopping = False

        # tasks
        self._connect_tasks = []

        self._create_clients()

    def _create_clients(self):
        self.public = DerivWSClient(DERIV_WS_URL, "PUBLIC", self.ui_queue)
        self.demo = DerivWSClient(DERIV_WS_URL, "DEMO", self.ui_queue)
        self.real = DerivWSClient(DERIV_WS_URL, "REAL", self.ui_queue)

        self.public.add_message_callback(self._on_public_msg)
        self.real.add_message_callback(self._on_real_msg)

        self.public.add_disconnect_callback(self._on_any_disconnect)
        self.demo.add_disconnect_callback(self._on_any_disconnect)
        self.real.add_disconnect_callback(self._on_any_disconnect)

    def ui(self, channel, msg):
        self.ui_queue.put((channel, msg))

    def log_market_exec(self, symbol: str, line: str):
        self.ui("log_market_exec", {"symbol": symbol, "line": line})

    def set_config(
        self,
        *,
        virtual_mode,
        vwin_target,
        vloss_target,
        trigger_mode,
        stake,
        max_gale,
        mult,
        stop_win,
    ):
        self.virtual_mode = bool(virtual_mode)
        self.vwin_target = int(vwin_target)
        self.vloss_target = int(vloss_target)
        self.trigger_mode = trigger_mode
        self.stake = float(stake)
        self.max_gale = int(max_gale)
        self.mult = float(mult)
        self.stop_win = float(stop_win)

        if not self.virtual_mode:
            self.vwin_streak = 0
            self.vloss_streak = 0
            self._armed_real_next = False
            self.ui("ui_virtual_state", {"vwin": 0, "vloss": 0, "armed": False})

    async def authorize(self, client: DerivWSClient, token: str, label: str):
        if not token.strip():
            self.ui("log_general", f"{utc_ts()} | [{label}] Token vazio (não autorizado).")
            return False
        resp = await client.request({"authorize": token.strip()}, timeout=45)
        if resp.get("error"):
            self.ui("log_general", f"{utc_ts()} | [{label}] ERRO authorize: {resp['error'].get('message')}")
            return False
        self.ui("log_general", f"{utc_ts()} | [{label}] Autorizado.")
        return True

    async def _subscribe_ticks_robust(self):
        """
        ✅ NOVO: subscribe robusto baseado em "primeiro tick recebido"
        - não depende de ack/req_id do subscribe
        - evita timeout do PUBLIC que você mostrou no log
        """
        # cria events por símbolo
        for sym in ALLOWED_SYMBOLS:
            if sym not in self._tick_seen_events:
                self._tick_seen_events[sym] = asyncio.Event()

        # 1) envia subscribe para todos (sem esperar ack)
        for sym in ALLOWED_SYMBOLS:
            if sym in self._tick_subscribed:
                continue
            await self.public.send_only({"ticks": sym, "subscribe": 1}, timeout=35)

        # 2) espera ticks aparecerem (janela única)
        t0 = time.time()
        window = 25.0
        while time.time() - t0 < window:
            ok = True
            for sym in ALLOWED_SYMBOLS:
                if sym in self._tick_subscribed:
                    continue
                if self._tick_seen_events[sym].is_set():
                    self._tick_subscribed.add(sym)
                else:
                    ok = False
            if ok:
                break
            await asyncio.sleep(0.2)

        # 3) retry nos que faltaram
        missing = [s for s in ALLOWED_SYMBOLS if s not in self._tick_subscribed]
        if missing:
            self.ui("log_general", f"{utc_ts()} | [PUBLIC] Retry subscribe ticks (faltando {len(missing)}).")
            for sym in missing:
                # limpa evento e reenvia
                try:
                    self._tick_seen_events[sym].clear()
                except Exception:
                    self._tick_seen_events[sym] = asyncio.Event()
                await self.public.send_only({"ticks": sym, "subscribe": 1}, timeout=35)

            t1 = time.time()
            while time.time() - t1 < 20.0:
                for sym in list(missing):
                    if self._tick_seen_events[sym].is_set():
                        self._tick_subscribed.add(sym)
                        missing.remove(sym)
                if not missing:
                    break
                await asyncio.sleep(0.2)

        self.ui("log_general", f"{utc_ts()} | [PUBLIC] Ticks ativos ({len(self._tick_subscribed)}/{len(ALLOWED_SYMBOLS)}).")
        # se mesmo assim faltou, não derruba o engine — ele segue com os que chegaram
        if len(self._tick_subscribed) == 0:
            raise TimeoutError("[PUBLIC] Nenhum tick chegou após subscribe (rede instável).")

    async def _subscribe_real_balance(self):
        if self._balance_subscribed:
            return
        resp = await self.real.request({"balance": 1, "subscribe": 1}, timeout=45)
        if resp.get("error"):
            self.ui("log_general", f"{utc_ts()} | [REAL] ERRO balance: {resp['error'].get('message')}")
            return
        self._balance_subscribed = True
        self.ui("log_general", f"{utc_ts()} | [REAL] Balance subscribe ok.")

    async def _start_internal(self):
        self._connect_tasks = [
            asyncio.create_task(self.public.connect_forever()),
            asyncio.create_task(self.demo.connect_forever()),
            asyncio.create_task(self.real.connect_forever()),
        ]

        # tempo para estabilizar
        await asyncio.sleep(1.0)

        ok_demo = await self.authorize(self.demo, self._last_demo_token, "DEMO")
        ok_real = await self.authorize(self.real, self._last_real_token, "REAL")

        if self.virtual_mode and not ok_demo:
            self.ui("log_general", f"{utc_ts()} | [ENGINE] Modo virtual ligado mas DEMO não autorizou (token/instabilidade).")
        if not ok_real:
            self.ui("log_general", f"{utc_ts()} | [ENGINE] REAL não autorizou (saldo/real pode falhar).")

        # ✅ aqui era o problema (PUBLIC request timeout). Agora é robusto por tick.
        await self._subscribe_ticks_robust()

        if ok_real:
            await self._subscribe_real_balance()

        self.ui("log_general", f"{utc_ts()} | [ENGINE] Rodando.")

    async def start(self, demo_token: str, real_token: str):
        if self.running:
            return

        self.running = True
        self._last_demo_token = demo_token or ""
        self._last_real_token = real_token or ""

        try:
            await self._start_internal()
        except Exception as e:
            self.ui("log_general", f"{utc_ts()} | [ENGINE] ERRO no start: {repr(e)}")
            await self._reboot_all(reason=f"start falhou: {repr(e)}")

    async def stop(self):
        if self._stopping:
            return
        self._stopping = True
        try:
            self.running = False

            if self.public:
                self.public.stop()
            if self.demo:
                self.demo.stop()
            if self.real:
                self.real.stop()

            try:
                await asyncio.gather(
                    self.public.close(),
                    self.demo.close(),
                    self.real.close(),
                    return_exceptions=True,
                )
            except Exception:
                pass

            for t in self._connect_tasks:
                try:
                    t.cancel()
                except Exception:
                    pass
            if self._connect_tasks:
                try:
                    await asyncio.gather(*self._connect_tasks, return_exceptions=True)
                except Exception:
                    pass
            self._connect_tasks = []

            self.ui("log_general", f"{utc_ts()} | [ENGINE] Parado.")
        finally:
            self._stopping = False

    def reset_counters_and_views(self):
        self.real_wins = 0
        self.real_losses = 0
        self.real_profit = 0.0
        self.real_balance_start = None

        self.vwin_streak = 0
        self.vloss_streak = 0
        self._armed_real_next = False

        self.ui("ui_pl", {
            "wins": 0, "losses": 0, "profit": 0.0,
            "balance": self.real_balance,
            "start": self.real_balance_start,
        })
        self.ui("ui_virtual_state", {"vwin": 0, "vloss": 0, "armed": False})
        self.ui("ui_reset_views", {"ok": True})

    async def _reboot_all(self, reason: str):
        if self._restart_in_progress:
            return
        self._restart_in_progress = True
        try:
            self.ui("log_general", f"{utc_ts()} | [REBOOT] {reason}")
            self.ui("log_general", f"{utc_ts()} | [REBOOT] Fechando e abrindo novamente (limpo)...")

            self.reset_counters_and_views()

            await self.stop()

            # limpa estado interno
            self.busy_trade = False
            self._tick_subscribed = set()
            self._tick_seen_events = {}
            self._balance_subscribed = False
            self.real_balance = None
            self.real_balance_start = None

            # recria clientes do zero
            self._create_clients()

            # reinicia
            self.running = True
            await self._start_internal()

        except Exception as e:
            self.ui("log_general", f"{utc_ts()} | [REBOOT] Falha: {repr(e)}")
            self.running = False
        finally:
            self._restart_in_progress = False

    def _on_any_disconnect(self, who: str, exc: Exception):
        if not self.running:
            return
        if self._stopping or self._restart_in_progress:
            return
        now = time.time()
        if now - self._last_restart_ts < 2.0:
            return
        self._last_restart_ts = now
        try:
            asyncio.create_task(self._reboot_all(reason=f"{who} caiu: {exc}"))
        except Exception:
            pass

    def _on_real_msg(self, data):
        if data.get("msg_type") == "balance":
            bal = data.get("balance", {}).get("balance")
            if bal is not None:
                try:
                    bal = float(bal)
                    self.real_balance = bal
                    if self.real_balance_start is None:
                        self.real_balance_start = bal
                    self.ui("ui_balance", {"balance": bal, "start": self.real_balance_start})
                except Exception:
                    pass

    def _format_quote(self, quote: float, pip_size: int | None):
        if pip_size is None:
            return f"{quote:.2f}"
        fmt = f"{{:.{pip_size}f}}"
        return fmt.format(quote)

    def _on_public_msg(self, data):
        if not self.running:
            return
        if data.get("msg_type") != "tick":
            return

        tick = data.get("tick", {})
        symbol = tick.get("symbol")
        quote = tick.get("quote")
        pip_size = tick.get("pip_size")

        if symbol in self._tick_seen_events:
            # ✅ marca que o símbolo está vivo (resolve subscribe sem ack)
            try:
                self._tick_seen_events[symbol].set()
            except Exception:
                pass

        if symbol not in ALLOWED_SYMBOLS or quote is None:
            return

        try:
            pip_size_int = int(pip_size) if pip_size is not None else None
        except Exception:
            pip_size_int = None

        price_str = self._format_quote(float(quote), pip_size_int)

        parities, last_digit, last_parity = digits_parity_map(price_str)
        seq_str = "/".join(parities) if parities else "-"
        self.ui("log_market", {"symbol": symbol, "line": f"{utc_ts()} | {price_str} ----> {seq_str} - digito {last_digit} - {last_parity}"})

        uniform = all_same_parity(parities)
        if uniform is None:
            return

        if self.trigger_mode == "SEQUENCIA":
            direction = uniform
        else:
            direction = "IMPAR" if uniform == "PAR" else "PAR"

        if self.busy_trade:
            return
        self.busy_trade = True

        if self.virtual_mode:
            account = "REAL" if self._armed_real_next else "DEMO"
        else:
            account = "REAL"

        plan = SignalPlan(
            symbol=symbol,
            direction=direction,
            account=account,
            base_stake=float(self.stake),
            max_gale=int(self.max_gale),
            mult=float(self.mult),
        )

        asyncio.create_task(self._execute_signal(plan))

    async def _proposal(self, client: DerivWSClient, symbol: str, direction: str, stake: float):
        contract_type = "DIGITEVEN" if direction == "PAR" else "DIGITODD"
        payload = {
            "proposal": 1,
            "amount": stake,
            "basis": "stake",
            "contract_type": contract_type,
            "currency": CURRENCY,
            "duration": 1,
            "duration_unit": "t",
            "symbol": symbol,
        }
        resp = await client.request(payload, timeout=45)
        if resp.get("error"):
            return None, resp["error"].get("message")
        pid = resp.get("proposal", {}).get("id")
        if not pid:
            return None, "Proposal sem id"
        return pid, None

    async def _buy_and_wait(self, client: DerivWSClient, proposal_id: str, stake: float):
        buy_resp = await client.request({"buy": proposal_id, "price": stake}, timeout=45)
        if buy_resp.get("error"):
            return None, None, buy_resp["error"].get("message")

        buy = buy_resp.get("buy", {})
        contract_id = buy.get("contract_id")
        if not contract_id:
            return None, None, "Buy sem contract_id"

        deadline = time.time() + 35
        while time.time() < deadline:
            msg = await client.request({"proposal_open_contract": 1, "contract_id": contract_id}, timeout=45)
            if msg.get("error"):
                return contract_id, None, msg["error"].get("message")
            poc = msg.get("proposal_open_contract", {})
            if poc.get("is_sold"):
                profit = float(poc.get("profit", 0.0) or 0.0)
                status = poc.get("status")  # won/lost
                return contract_id, {"status": status, "profit": profit}, None
            await asyncio.sleep(0.12)

        return contract_id, None, "Timeout aguardando resultado"

    def _growth_value(self):
        if self.real_balance is not None and self.real_balance_start is not None:
            return float(self.real_balance - self.real_balance_start)
        return None

    async def _check_stop_win_and_maybe_stop(self):
        if not self.running:
            return
        if self.stop_win <= 0:
            return

        growth = self._growth_value()
        metric = growth if growth is not None else float(self.real_profit)
        if metric >= float(self.stop_win):
            self.ui("log_general", f"{utc_ts()} | [STOP WIN] Alvo atingido: {metric:.2f} >= {self.stop_win:.2f}. Parando engine.")
            await self.stop()

    async def _execute_signal(self, plan: SignalPlan):
        try:
            if not self.running:
                return

            client = self.real if plan.account == "REAL" else self.demo

            signal_id = f"{int(time.time()*1000)}"
            open_line = f"{utc_ts()} | EXEC OPEN | {plan.account} | {plan.symbol} | {plan.direction} | stake={round2(plan.base_stake):.2f} | gale_max={plan.max_gale} | mult={plan.mult}"
            self.log_market_exec(plan.symbol, open_line)

            self.ui("op_add", {
                "id": signal_id,
                "time": utc_ts(),
                "symbol": plan.symbol,
                "account": plan.account,
                "direction": plan.direction,
                "stake": round2(plan.base_stake),
                "gale": 0,
                "status": "OPEN",
                "profit": "",
            })

            total_profit = 0.0
            current_stake = round2(plan.base_stake)
            used_gale = 0
            final_status = None

            while True:
                if not self.running:
                    final_status = "STOP"
                    break

                pid, perr = await self._proposal(client, plan.symbol, plan.direction, current_stake)
                if perr:
                    self.ui("log_general", f"{utc_ts()} | [{plan.account}] PROPOSAL ERRO {plan.symbol}: {perr}")
                    final_status = "ERROR"
                    break

                _, result, berr = await self._buy_and_wait(client, pid, current_stake)
                if berr:
                    self.ui("log_general", f"{utc_ts()} | [{plan.account}] BUY/WAIT ERRO {plan.symbol}: {berr}")
                    final_status = "ERROR"
                    break

                status = result.get("status")
                profit = float(result.get("profit", 0.0))
                total_profit += profit

                self.ui("op_update", {
                    "id": signal_id,
                    "gale": used_gale,
                    "status": "WIN" if status == "won" else "LOSS",
                    "profit": f"{round2(total_profit):.2f}",
                })

                if status == "won":
                    final_status = "WIN"
                    break

                if used_gale >= plan.max_gale:
                    final_status = "LOSS"
                    break

                used_gale += 1
                current_stake = round2(current_stake * plan.mult)
                self.ui("log_general", f"{utc_ts()} | [{plan.account}] GALE {used_gale}/{plan.max_gale} {plan.symbol} {plan.direction} stake={current_stake:.2f}")

            close_line = f"{utc_ts()} | EXEC CLOSE | {plan.account} | {plan.symbol} | {plan.direction} | result={final_status} | profit_total={round2(total_profit):.2f} | gales_used={used_gale}"
            self.log_market_exec(plan.symbol, close_line)

            if plan.account == "REAL":
                if final_status == "WIN":
                    self.real_wins += 1
                elif final_status == "LOSS":
                    self.real_losses += 1
                self.real_profit = round2(self.real_profit + total_profit)
                self.ui("ui_pl", {
                    "wins": self.real_wins,
                    "losses": self.real_losses,
                    "profit": self.real_profit,
                    "balance": self.real_balance,
                    "start": self.real_balance_start,
                })
                await self._check_stop_win_and_maybe_stop()

            if self.virtual_mode and self.running:
                if plan.account == "DEMO":
                    if final_status == "WIN":
                        self.vwin_streak += 1
                        self.vloss_streak = 0
                    elif final_status == "LOSS":
                        self.vloss_streak += 1
                        self.vwin_streak = 0

                    armed = False
                    if self.vwin_target > 0 and self.vwin_streak >= self.vwin_target:
                        armed = True
                    if self.vloss_target > 0 and self.vloss_streak >= self.vloss_target:
                        armed = True

                    if armed:
                        self._armed_real_next = True
                        self.ui("log_general", f"{utc_ts()} | [VIRTUAL] Gatilho atingido -> PRÓXIMO sinal em REAL.")

                    self.ui("ui_virtual_state", {"vwin": self.vwin_streak, "vloss": self.vloss_streak, "armed": self._armed_real_next})
                else:
                    self._armed_real_next = False
                    self.vwin_streak = 0
                    self.vloss_streak = 0
                    self.ui("ui_virtual_state", {"vwin": 0, "vloss": 0, "armed": False})
                    self.ui("log_general", f"{utc_ts()} | [VIRTUAL] Sinal REAL finalizado -> volta para DEMO.")

        except Exception as e:
            self.ui("log_general", f"{utc_ts()} | [ENGINE] Erro execução: {repr(e)}")
        finally:
            self.busy_trade = False


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Deriv — Par/Ímpar (Decodificador de Preço)")
        self.geometry("1200x800")

        self.ui_queue = queue.Queue()
        self.engine = TradingEngine(self.ui_queue)

        self._build_scrollable_root()
        self._build_ui()
        self._load_config_into_ui()
        self._poll_ui_queue()

        self.protocol("WM_DELETE_WINDOW", self.on_close)

        self.loop_thread = threading.Thread(target=self._start_async_loop, daemon=True)
        self.loop_thread.start()

    def _build_scrollable_root(self):
        container = ttk.Frame(self)
        container.pack(fill="both", expand=True)

        self.canvas = tk.Canvas(container, highlightthickness=0)
        self.vsb = ttk.Scrollbar(container, orient="vertical", command=self.canvas.yview)
        self.hsb = ttk.Scrollbar(container, orient="horizontal", command=self.canvas.xview)

        self.canvas.configure(yscrollcommand=self.vsb.set, xscrollcommand=self.hsb.set)

        self.vsb.pack(side="right", fill="y")
        self.hsb.pack(side="bottom", fill="x")
        self.canvas.pack(side="left", fill="both", expand=True)

        self.root_frame = ttk.Frame(self.canvas)
        self.canvas_window = self.canvas.create_window((0, 0), window=self.root_frame, anchor="nw")

        def on_configure(_):
            self.canvas.configure(scrollregion=self.canvas.bbox("all"))

        def on_canvas_configure(event):
            self.canvas.itemconfig(self.canvas_window, width=event.width)

        self.root_frame.bind("<Configure>", on_configure)
        self.canvas.bind("<Configure>", on_canvas_configure)

        def _on_mousewheel(event):
            if event.delta:
                self.canvas.yview_scroll(int(-1 * (event.delta / 120)), "units")
            return "break"

        def _on_shift_mousewheel(event):
            if event.delta:
                self.canvas.xview_scroll(int(-1 * (event.delta / 120)), "units")
            return "break"

        def _on_button4(_):
            self.canvas.yview_scroll(-1, "units")
            return "break"

        def _on_button5(_):
            self.canvas.yview_scroll(1, "units")
            return "break"

        self.bind_all("<MouseWheel>", _on_mousewheel, add="+")
        self.bind_all("<Shift-MouseWheel>", _on_shift_mousewheel, add="+")
        self.bind_all("<Button-4>", _on_button4, add="+")
        self.bind_all("<Button-5>", _on_button5, add="+")

    # ---------- config persistence ----------
    def _collect_config_from_ui(self) -> dict:
        return {
            "demo_token": self.demo_token.get(),
            "real_token": self.real_token.get(),
            "trigger_mode": self.trigger_mode.get(),
            "virtual_mode": bool(self.virtual_mode.get()),
            "vwin": self.vwin.get().strip(),
            "vloss": self.vloss.get().strip(),
            "stake": self.stake.get().strip(),
            "gale": self.gale.get().strip(),
            "mult": self.mult.get().strip(),
            "stop_win": self.stop_win.get().strip(),
        }

    def _apply_config_to_ui(self, cfg: dict):
        def set_entry(entry: ttk.Entry, val: str):
            entry.delete(0, "end")
            entry.insert(0, str(val))

        if not isinstance(cfg, dict):
            return

        set_entry(self.demo_token, cfg.get("demo_token", ""))
        set_entry(self.real_token, cfg.get("real_token", ""))
        try:
            self.trigger_mode.set(cfg.get("trigger_mode", "SEQUENCIA"))
        except Exception:
            pass
        self.virtual_mode.set(bool(cfg.get("virtual_mode", True)))
        set_entry(self.vwin, cfg.get("vwin", "0"))
        set_entry(self.vloss, cfg.get("vloss", "0"))
        set_entry(self.stake, cfg.get("stake", "1.00"))
        set_entry(self.gale, cfg.get("gale", "0"))
        set_entry(self.mult, cfg.get("mult", "2.0"))
        set_entry(self.stop_win, cfg.get("stop_win", "0"))

    def _save_config(self):
        try:
            cfg = self._collect_config_from_ui()
            with open(CONFIG_FILE, "w", encoding="utf-8") as f:
                json.dump(cfg, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    def _load_config_into_ui(self):
        try:
            if os.path.exists(CONFIG_FILE):
                with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                    cfg = json.load(f)
                self._apply_config_to_ui(cfg)
        except Exception:
            pass

    # ---------- UI ----------
    def _build_ui(self):
        cfg = ttk.LabelFrame(self.root_frame, text="Configurações")
        cfg.pack(fill="x", padx=10, pady=10)

        ttk.Label(cfg, text="Token DEMO:").grid(row=0, column=0, sticky="w", padx=6, pady=4)
        self.demo_token = ttk.Entry(cfg, width=70, show="•")
        self.demo_token.grid(row=0, column=1, sticky="w", padx=6, pady=4)

        ttk.Label(cfg, text="Token REAL:").grid(row=1, column=0, sticky="w", padx=6, pady=4)
        self.real_token = ttk.Entry(cfg, width=70, show="•")
        self.real_token.grid(row=1, column=1, sticky="w", padx=6, pady=4)

        ttk.Label(cfg, text="Modo (Gatilho):").grid(row=0, column=2, sticky="w", padx=10, pady=4)
        self.trigger_mode = tk.StringVar(value="SEQUENCIA")
        ttk.OptionMenu(cfg, self.trigger_mode, "SEQUENCIA", "SEQUENCIA", "REVERSAO").grid(row=0, column=3, sticky="w", padx=6, pady=4)

        self.virtual_mode = tk.BooleanVar(value=True)
        ttk.Checkbutton(cfg, text="Modo Virtual", variable=self.virtual_mode).grid(row=1, column=2, sticky="w", padx=10, pady=4)

        ttk.Label(cfg, text="Win Virtual (0 desativa):").grid(row=0, column=4, sticky="w", padx=10, pady=4)
        self.vwin = ttk.Entry(cfg, width=8)
        self.vwin.insert(0, "0")
        self.vwin.grid(row=0, column=5, sticky="w", padx=6, pady=4)

        ttk.Label(cfg, text="Loss Virtual (0 desativa):").grid(row=1, column=4, sticky="w", padx=10, pady=4)
        self.vloss = ttk.Entry(cfg, width=8)
        self.vloss.insert(0, "0")
        self.vloss.grid(row=1, column=5, sticky="w", padx=6, pady=4)

        ttk.Label(cfg, text="Stake:").grid(row=2, column=0, sticky="w", padx=6, pady=4)
        self.stake = ttk.Entry(cfg, width=10)
        self.stake.insert(0, "1.00")
        self.stake.grid(row=2, column=1, sticky="w", padx=6, pady=4)

        ttk.Label(cfg, text="Gale (max):").grid(row=2, column=2, sticky="w", padx=10, pady=4)
        self.gale = ttk.Entry(cfg, width=8)
        self.gale.insert(0, "0")
        self.gale.grid(row=2, column=3, sticky="w", padx=6, pady=4)

        ttk.Label(cfg, text="Multiplicador:").grid(row=2, column=4, sticky="w", padx=10, pady=4)
        self.mult = ttk.Entry(cfg, width=8)
        self.mult.insert(0, "2.0")
        self.mult.grid(row=2, column=5, sticky="w", padx=6, pady=4)

        ttk.Label(cfg, text="Stop Win (0 desativa):").grid(row=3, column=0, sticky="w", padx=6, pady=4)
        self.stop_win = ttk.Entry(cfg, width=10)
        self.stop_win.insert(0, "0")
        self.stop_win.grid(row=3, column=1, sticky="w", padx=6, pady=4)

        btns = ttk.Frame(cfg)
        btns.grid(row=4, column=0, columnspan=6, sticky="w", padx=6, pady=8)

        ttk.Button(btns, text="Iniciar", command=self.on_start).pack(side="left", padx=5)
        ttk.Button(btns, text="Parar", command=self.on_stop).pack(side="left", padx=5)
        ttk.Button(btns, text="Resetar (limpa tela/contadores, mantém tokens/config)", command=self.on_reset).pack(side="left", padx=5)

        status = ttk.LabelFrame(self.root_frame, text="Status / Banca (REAL)")
        status.pack(fill="x", padx=10, pady=(0, 10))

        self.lbl_balance = ttk.Label(status, text="Saldo REAL: --")
        self.lbl_balance.grid(row=0, column=0, sticky="w", padx=6, pady=4)

        self.lbl_growth = ttk.Label(status, text="Crescimento/Prejuízo: --")
        self.lbl_growth.grid(row=0, column=1, sticky="w", padx=16, pady=4)

        self.lbl_wl = ttk.Label(status, text="WIN/LOSS (sinal final): 0 / 0")
        self.lbl_wl.grid(row=0, column=2, sticky="w", padx=16, pady=4)

        self.lbl_profit = ttk.Label(status, text="Ganho/Perda total (REAL): 0.00")
        self.lbl_profit.grid(row=0, column=3, sticky="w", padx=16, pady=4)

        self.lbl_virtual = ttk.Label(status, text="Virtual streak (DEMO): W=0 L=0 | armado REAL: não")
        self.lbl_virtual.grid(row=1, column=0, columnspan=4, sticky="w", padx=6, pady=4)

        self.nb = ttk.Notebook(self.root_frame)
        self.nb.pack(fill="both", expand=True, padx=10, pady=10)

        self.tab_ops = ttk.Frame(self.nb)
        self.nb.add(self.tab_ops, text="Operações")

        cols = ("time", "symbol", "account", "direction", "stake", "gale", "status", "profit")
        self.ops_tree = ttk.Treeview(self.tab_ops, columns=cols, show="headings", height=16)
        for c in cols:
            self.ops_tree.heading(c, text=c.upper())
            self.ops_tree.column(c, width=130 if c in ("time", "symbol") else 110, anchor="w")
        self.ops_tree.column("profit", width=120, anchor="e")
        self.ops_tree.pack(side="left", fill="both", expand=True)

        ops_scroll = ttk.Scrollbar(self.tab_ops, orient="vertical", command=self.ops_tree.yview)
        self.ops_tree.configure(yscrollcommand=ops_scroll.set)
        ops_scroll.pack(side="right", fill="y")

        self.tab_log = ttk.Frame(self.nb)
        self.nb.add(self.tab_log, text="Logs Geral")
        self.txt_log = ScrolledText(self.tab_log, height=18)
        self.txt_log.pack(fill="both", expand=True)

        self.market_text = {}
        for sym in ALLOWED_SYMBOLS:
            t = ttk.Frame(self.nb)
            self.nb.add(t, text=sym)
            txt = ScrolledText(t, height=18)
            txt.pack(fill="both", expand=True)
            self.market_text[sym] = txt

        self._op_items = {}

    def _start_async_loop(self):
        self.engine.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.engine.loop)
        self.engine.loop.run_forever()

    def on_start(self):
        try:
            if not self.engine.loop:
                raise RuntimeError("Loop asyncio ainda não iniciou. Feche e abra novamente.")

            self._save_config()

            demo_token = self.demo_token.get()
            real_token = self.real_token.get()

            vm = self.virtual_mode.get()
            vwin = int((self.vwin.get().strip() or "0"))
            vloss = int((self.vloss.get().strip() or "0"))
            trig = self.trigger_mode.get()
            stake = float(self.stake.get().strip().replace(",", "."))
            gale = int((self.gale.get().strip() or "0"))
            mult = float(self.mult.get().strip().replace(",", "."))
            stop_win = float((self.stop_win.get().strip() or "0").replace(",", "."))

            if stake <= 0:
                raise ValueError("Stake deve ser > 0")
            if stop_win < 0:
                raise ValueError("Stop Win deve ser >= 0")

            self.engine.set_config(
                virtual_mode=vm,
                vwin_target=vwin,
                vloss_target=vloss,
                trigger_mode=trig,
                stake=round2(stake),
                max_gale=gale,
                mult=mult,
                stop_win=round2(stop_win),
            )

            fut = asyncio.run_coroutine_threadsafe(self.engine.start(demo_token, real_token), self.engine.loop)

            def _done(f):
                try:
                    f.result()
                except Exception as e:
                    self.ui_queue.put(("log_general", f"{utc_ts()} | [UI] ERRO start(): {repr(e)}"))

            fut.add_done_callback(_done)

        except Exception as e:
            messagebox.showerror("Erro", str(e))

    def on_stop(self):
        if self.engine.loop:
            asyncio.run_coroutine_threadsafe(self.engine.stop(), self.engine.loop)
        self._save_config()

    def on_reset(self):
        self.engine.reset_counters_and_views()
        self._save_config()

    def on_close(self):
        try:
            self._save_config()
            self.on_stop()
        except Exception:
            pass
        self.destroy()

    def _poll_ui_queue(self):
        try:
            while True:
                item = self.ui_queue.get_nowait()
                self._handle_ui_event(item)
        except queue.Empty:
            pass
        self.after(60, self._poll_ui_queue)

    def _handle_ui_event(self, item):
        kind = item[0]

        if kind == "log_general":
            line = item[1]
            self.txt_log.insert("end", line + "\n")
            self.txt_log.see("end")

        elif kind == "log_market":
            payload = item[1]
            sym = payload["symbol"]
            line = payload["line"]
            txt = self.market_text.get(sym)
            if txt:
                txt.insert("end", line + "\n")
                txt.see("end")

        elif kind == "log_market_exec":
            payload = item[1]
            sym = payload["symbol"]
            line = payload["line"]
            txt = self.market_text.get(sym)
            if txt:
                txt.insert("end", line + "\n")
                txt.see("end")

        elif kind == "op_add":
            p = item[1]
            iid = p["id"]
            vals = (p["time"], p["symbol"], p["account"], p["direction"],
                    f"{p['stake']:.2f}", str(p["gale"]), p["status"], p["profit"])
            tree_iid = self.ops_tree.insert("", "end", values=vals)
            self._op_items[iid] = tree_iid
            self.ops_tree.yview_moveto(1.0)

        elif kind == "op_update":
            p = item[1]
            iid = p["id"]
            tree_iid = self._op_items.get(iid)
            if tree_iid:
                cur = list(self.ops_tree.item(tree_iid, "values"))
                cur[5] = str(p.get("gale", cur[5]))
                cur[6] = p.get("status", cur[6])
                cur[7] = p.get("profit", cur[7])
                self.ops_tree.item(tree_iid, values=tuple(cur))
                self.ops_tree.yview_moveto(1.0)

        elif kind == "ui_balance":
            p = item[1]
            bal = p.get("balance")
            start = p.get("start")
            if bal is not None:
                self.lbl_balance.config(text=f"Saldo REAL: {bal:.2f} {CURRENCY}")
            if bal is not None and start is not None:
                diff = bal - start
                self.lbl_growth.config(text=f"Crescimento/Prejuízo: {diff:.2f} {CURRENCY}")
            elif bal is not None and start is None:
                self.lbl_growth.config(text=f"Crescimento/Prejuízo: 0.00 {CURRENCY}")

        elif kind == "ui_pl":
            p = item[1]
            wins = p.get("wins", 0)
            losses = p.get("losses", 0)
            prof = p.get("profit", 0.0)
            self.lbl_wl.config(text=f"WIN/LOSS (sinal final): {wins} / {losses}")
            self.lbl_profit.config(text=f"Ganho/Perda total (REAL): {prof:.2f} {CURRENCY}")

            bal = p.get("balance")
            start = p.get("start")
            if bal is not None and start is not None:
                diff = bal - start
                self.lbl_growth.config(text=f"Crescimento/Prejuízo: {diff:.2f} {CURRENCY}")
            elif bal is not None and start is None:
                self.lbl_growth.config(text=f"Crescimento/Prejuízo: 0.00 {CURRENCY}")

        elif kind == "ui_virtual_state":
            p = item[1]
            vwin = p.get("vwin", 0)
            vloss = p.get("vloss", 0)
            armed = p.get("armed", False)
            self.lbl_virtual.config(text=f"Virtual streak (DEMO): W={vwin} L={vloss} | armado REAL: {'sim' if armed else 'não'}")

        elif kind == "ui_reset_views":
            self.txt_log.delete("1.0", "end")
            for txt in self.market_text.values():
                txt.delete("1.0", "end")
            for iid in self.ops_tree.get_children():
                self.ops_tree.delete(iid)
            self._op_items.clear()

    def run(self):
        self.mainloop()


if __name__ == "__main__":
    app = App()
    app.run()
