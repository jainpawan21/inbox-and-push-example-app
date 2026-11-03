from pathlib import Path

from pydantic import Field, ValidationError, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

_ENV_FILE = Path(__file__).resolve().parent.parent / ".env"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=_ENV_FILE, extra="ignore")

    secret_key: str = Field(validation_alias="NOVU_SECRET_KEY")
    application_identifier: str = Field(validation_alias="NOVU_APPLICATION_IDENTIFIER")
    fcm_integration_identifier: str = Field(validation_alias="NOVU_FCM_INTEGRATION_IDENTIFIER")
    workflow_id: str = Field(validation_alias="NOVU_WORKFLOW_ID")
    api_url: str = Field(default="https://api.novu.co", validation_alias="NOVU_API_URL")
    inbox_api_url: str = Field(default="https://api.novu.co/v1", validation_alias="NOVU_INBOX_API_URL")
    socket_url: str = Field(default="wss://socket.novu.co", validation_alias="NOVU_SOCKET_URL")
    subscriber_id: str = Field(validation_alias="SUBSCRIBER_ID")

    @field_validator("api_url", "inbox_api_url", "socket_url")
    @classmethod
    def strip_trailing_slash(cls, value: str) -> str:
        return value.rstrip("/")


def load_settings() -> Settings:
    try:
        return Settings()
    except ValidationError as error:
        missing = ", ".join(".".join(str(part) for part in err["loc"]) for err in error.errors())
        raise RuntimeError(
            f"Missing server settings ({missing}). Copy server/.env.example to server/.env."
        ) from error
