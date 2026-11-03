"""Novu calls that must stay on the server.

The secret key never leaves this process. The Android app receives only:
- an HMAC of the subscriber id (Inbox auth)
- confirmation that an FCM device token was stored
"""

from __future__ import annotations

import hashlib
import hmac
from typing import Any, NamedTuple

import novu_py
from novu_py import Novu

from .settings import Settings

MAX_FCM_TOKENS = 100


class PushChannel(NamedTuple):
    provider_id: str
    integration_identifier: str
    device_tokens: list[str]


def inbox_subscriber_hash(secret: str, subscriber_id: str) -> str:
    """HMAC-SHA256(secret, subscriberId), lowercase hex. Do not store the result."""
    return hmac.new(secret.encode(), subscriber_id.encode(), hashlib.sha256).hexdigest()


def normalize_token(token: str) -> str:
    """Trim an FCM token. Do not change its case. FCM tokens are case-sensitive."""
    return token.strip()


def merge_device_tokens(existing: list[str], device_token: str, limit: int = MAX_FCM_TOKENS) -> list[str]:
    """Append one token. A repeat is ignored. The newest tokens are kept at the limit."""
    token = normalize_token(device_token)
    tokens = list(existing)
    if token not in tokens:
        tokens.append(token)
    if len(tokens) > limit:
        tokens = tokens[-limit:]
    return tokens


def remove_device_token(existing: list[str], device_token: str) -> list[str]:
    token = normalize_token(device_token)
    return [item for item in existing if item != token]


def tokens_for_integration(channels: list[PushChannel], identifier: str) -> list[str]:
    for channel in channels:
        if channel.provider_id != "fcm":
            continue
        if channel.integration_identifier != identifier:
            continue
        return [normalize_token(token) for token in channel.device_tokens]
    return []


class NovuService:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._novu = Novu(secret_key=settings.secret_key, server_url=settings.api_url)

    def close(self) -> None:
        self._novu.__exit__(None, None, None)

    def subscriber_hash(self, subscriber_id: str) -> str:
        """Generate the Inbox hash on every bootstrap. Do not store it.

        Rotating NOVU_SECRET_KEY then only requires the app to ask again.
        Device tokens stay on the subscriber and do not need to be re-registered.
        """
        return inbox_subscriber_hash(self.settings.secret_key, subscriber_id)

    def ensure_subscriber(self, subscriber_id: str) -> None:
        """Create the subscriber if this is their first device or first trigger.

        `fail_if_exists=False` updates an existing subscriber instead of erroring.
        """
        self._novu.subscribers.create(
            create_subscriber_request_dto={"subscriber_id": subscriber_id},
            fail_if_exists=False,
        )

    def register_fcm_token(self, subscriber_id: str, device_token: str) -> list[str]:
        """Store one FCM registration token on the subscriber.

        PUT /subscribers/{id}/credentials replaces the token list for that
        integration. Read the current list, append this device, and write the
        full list back so a second phone is not dropped.

        Tokens live on subscriber.channels[].credentials.deviceTokens, scoped by
        integrationIdentifier. They are not channel endpoints.
        FCM tokens are case-sensitive, so this only trims whitespace.
        """
        self.ensure_subscriber(subscriber_id)
        tokens = merge_device_tokens(self._tokens_for_integration(subscriber_id), device_token)
        self._replace_tokens(subscriber_id, tokens)
        return tokens

    def remove_fcm_token(self, subscriber_id: str, device_token: str) -> list[str]:
        """Remove one device on logout.

        An empty list clears the integration. Novu removes stale FCM tokens on
        its own. An unknown subscriber or a token that is not stored is left unchanged.
        """
        current = self._tokens_for_integration(subscriber_id)
        tokens = remove_device_token(current, device_token)
        if tokens == current:
            return current
        self._replace_tokens(subscriber_id, tokens)
        return tokens

    def list_fcm_tokens(self, subscriber_id: str) -> list[str]:
        return self._tokens_for_integration(subscriber_id)

    def trigger_notification(
        self,
        subscriber_id: str,
        *,
        title: str,
        body: str,
        deeplink: str | None,
    ) -> None:
        """Trigger a workflow that has both an Inbox step and a Push step.

        `payload` is available to the workflow template. The FCM notification
        title and body come from that Push step. `overrides.providers.fcm`
        sets the Android channel and attaches the deeplink as FCM data.
        FCM requires every data value to be a string.
        """
        self.ensure_subscriber(subscriber_id)
        payload: dict[str, Any] = {"title": title, "body": body}
        if deeplink:
            payload["deeplink"] = deeplink

        fcm: dict[str, Any] = {
            "android": {
                "priority": "high",
                "notification": {
                    "channelId": "novu",
                    "sound": "default",
                },
            }
        }
        if deeplink:
            fcm["data"] = {"deeplink": deeplink}

        self._novu.trigger(
            trigger_event_request_dto=novu_py.TriggerEventRequestDto(
                workflow_id=self.settings.workflow_id,
                to=novu_py.SubscriberPayloadDto(subscriber_id=subscriber_id),
                payload=payload,
                overrides=novu_py.Overrides(providers={"fcm": fcm}),
            )
        )

    def _tokens_for_integration(self, subscriber_id: str) -> list[str]:
        try:
            subscriber = self._novu.subscribers.retrieve(subscriber_id=subscriber_id).result
        except novu_py.NovuError as error:
            if error.status_code == 404:
                return []
            raise

        channels = []
        for channel in subscriber.channels or []:
            provider = getattr(channel.provider_id, "value", channel.provider_id)
            credentials = channel.credentials
            device_tokens = list(credentials.device_tokens or []) if credentials is not None else []
            channels.append(
                PushChannel(
                    provider_id=str(provider),
                    integration_identifier=channel.integration_identifier or "",
                    device_tokens=device_tokens,
                )
            )
        return tokens_for_integration(channels, self.settings.fcm_integration_identifier)

    def _replace_tokens(self, subscriber_id: str, tokens: list[str]) -> None:
        # PUT replaces. PATCH would union and could not delete a single token.
        self._novu.subscribers.credentials.update(
            subscriber_id=subscriber_id,
            update_subscriber_channel_request_dto={
                "provider_id": novu_py.ChatOrPushProviderEnum.FCM,
                "integration_identifier": self.settings.fcm_integration_identifier,
                "credentials": {"device_tokens": tokens},
            },
        )
