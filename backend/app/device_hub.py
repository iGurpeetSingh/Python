import asyncio
import hmac
import json
import uuid
from dataclasses import dataclass
from typing import Any

from fastapi import HTTPException, WebSocket, WebSocketDisconnect

from .config import GURI_PAIRING_CODE


@dataclass
class ConnectedDevice:
    websocket: WebSocket
    device_id: str


class DeviceHub:
    """Single-instance WebSocket broker for the paired Windows and Android agents."""

    def __init__(self) -> None:
        self._devices: dict[str, ConnectedDevice] = {}
        self._pending: dict[str, asyncio.Future] = {}
        self._lock = asyncio.Lock()

    def verify_code(self, code: str | None) -> bool:
        expected = GURI_PAIRING_CODE
        return bool(expected and code and hmac.compare_digest(expected, code))

    async def device_names(self) -> list[str]:
        async with self._lock:
            return sorted(self._devices.keys())

    async def connect(self, device_id: str, websocket: WebSocket, code: str | None) -> None:
        if device_id not in {"windows", "android"} or not self.verify_code(code):
            await websocket.close(code=4403, reason="Invalid pairing code or device id")
            return

        await websocket.accept()
        async with self._lock:
            old = self._devices.get(device_id)
            self._devices[device_id] = ConnectedDevice(websocket, device_id)

        if old is not None and old.websocket is not websocket:
            try:
                await old.websocket.close(code=4001, reason="Replaced by a newer device session")
            except Exception:
                pass

        try:
            await websocket.send_json({"type": "connected", "device_id": device_id})
            while True:
                message = await websocket.receive_json()
                if not isinstance(message, dict):
                    continue

                kind = message.get("type")
                if kind == "heartbeat":
                    await websocket.send_json({"type": "heartbeat_ack"})
                elif kind == "result":
                    request_id = str(message.get("id") or "")
                    async with self._lock:
                        future = self._pending.get(request_id)
                    if future is not None and not future.done():
                        future.set_result(message.get("result") or {})
                elif kind == "agent_status":
                    await websocket.send_json({"type": "status_ack"})
        except WebSocketDisconnect:
            pass
        except Exception:
            pass
        finally:
            async with self._lock:
                current = self._devices.get(device_id)
                if current is not None and current.websocket is websocket:
                    self._devices.pop(device_id, None)

    async def dispatch(
        self,
        device_id: str,
        command: str,
        timeout_seconds: int = 75,
    ) -> dict[str, Any]:
        request_id = uuid.uuid4().hex
        loop = asyncio.get_running_loop()
        future: asyncio.Future = loop.create_future()

        async with self._lock:
            connected = self._devices.get(device_id)
            if connected is None:
                raise HTTPException(
                    status_code=409,
                    detail=(
                        f"{device_id} device is not connected to Guri Cloud. "
                        "Open the Guri companion on that device and connect it using "
                        "the Render pairing code."
                    ),
                )
            self._pending[request_id] = future

        try:
            await connected.websocket.send_json({
                "type": "command",
                "id": request_id,
                "command": command,
            })
            return await asyncio.wait_for(future, timeout=timeout_seconds)
        except asyncio.TimeoutError as exc:
            raise HTTPException(
                status_code=504,
                detail=f"{device_id} device did not confirm the action within {timeout_seconds} seconds.",
            ) from exc
        except Exception as exc:
            raise HTTPException(
                status_code=502,
                detail=f"Could not deliver the command to {device_id}: {type(exc).__name__}: {exc}",
            ) from exc
        finally:
            async with self._lock:
                self._pending.pop(request_id, None)


device_hub = DeviceHub()
