"""Backend the Android app is allowed to call.

Inbox session material (HMAC) and FCM token registration both go through here.
The Novu secret key and the subscriber id are read from the environment.
Neither is chosen by the person using the Android app.
"""

from contextlib import asynccontextmanager
from typing import Any

import novu_py
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .novu_service import NovuService
from .settings import load_settings

settings = load_settings()
novu = NovuService(settings)


@asynccontextmanager
async def lifespan(_: FastAPI):
    yield
    novu.close()


app = FastAPI(title="Novu Kotlin Inbox + FCM example", lifespan=lifespan)


class DeviceBody(BaseModel):
    device_token: str = Field(alias="deviceToken", min_length=1)

    model_config = {"populate_by_name": True}


class TriggerBody(BaseModel):
    title: str = "New notification"
    body: str = "Sent from the Python example server"
    deeplink: str | None = None


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/inbox/bootstrap")
def inbox_bootstrap() -> dict[str, str]:
    """Return everything the app needs to open an Inbox session.

    The subscriber id comes from SUBSCRIBER_ID. The hash is computed now and
    not stored. The app then calls Novu POST /v1/inbox/session itself.
    """
    subscriber_id = settings.subscriber_id
    return {
        "subscriberId": subscriber_id,
        "subscriberHash": novu.subscriber_hash(subscriber_id),
        "applicationIdentifier": settings.application_identifier,
        "inboxApiUrl": settings.inbox_api_url,
        "socketUrl": settings.socket_url,
    }


def call_novu(work):
    try:
        return work()
    except novu_py.NovuError as error:
        code = getattr(error, "status_code", None)
        detail = f"Novu rejected the request ({code})." if code else "Novu rejected the request."
        raise HTTPException(status_code=502, detail=detail) from error


@app.post("/devices")
def register_device(body: DeviceBody) -> dict[str, Any]:
    tokens = call_novu(lambda: novu.register_fcm_token(settings.subscriber_id, body.device_token))
    return {"subscriberId": settings.subscriber_id, "deviceTokenCount": len(tokens)}


@app.delete("/devices")
def unregister_device(body: DeviceBody) -> dict[str, Any]:
    tokens = call_novu(lambda: novu.remove_fcm_token(settings.subscriber_id, body.device_token))
    return {"subscriberId": settings.subscriber_id, "deviceTokenCount": len(tokens)}


@app.get("/devices")
def list_devices() -> dict[str, Any]:
    tokens = call_novu(lambda: novu.list_fcm_tokens(settings.subscriber_id))
    # Show only a suffix. The full token is already on the device that owns it.
    return {
        "subscriberId": settings.subscriber_id,
        "integrationIdentifier": settings.fcm_integration_identifier,
        "tokens": [f"...{token[-8:]}" if len(token) > 8 else token for token in tokens],
        "deviceTokenCount": len(tokens),
    }


@app.post("/workflows/trigger")
def trigger_workflow(body: TriggerBody) -> dict[str, str]:
    call_novu(
        lambda: novu.trigger_notification(
            settings.subscriber_id,
            title=body.title,
            body=body.body,
            deeplink=body.deeplink,
        )
    )
    return {"status": "triggered", "workflowId": settings.workflow_id}
