# Kotlin Inbox and FCM with a Python backend

Android app and Python server for Novu Inbox plus Firebase Cloud Messaging. FCM is free. This does not need an Apple Developer account.

- **Push** is backend-only. The Android app sends its FCM registration token to this Python server. The server stores it on the Novu subscriber with the official Python SDK. The Novu secret key never ships in the app.
- **Inbox** is a separate channel. The server only mints an HMAC of the subscriber id (`subscriberHash`). The app opens a Novu Inbox session, holds the subscriber JWT, and calls the public Inbox API for the feed, read, archive, and delete. There is no Kotlin Inbox SDK.

```
Android app                     Python server                      Novu
-----------                     -------------                      ----
POST /inbox/bootstrap    ->     HMAC(secret, subscriberId)
                                returns subscriberHash + public app id
POST /v1/inbox/session   ----------------------------------------> subscriber JWT
GET/PATCH /v1/inbox/...  ----------------------------------------> feed, read, archive
WebSocket ?token=JWT     ----------------------------------------> live hints

FCM registration token
POST /devices            ->     subscribers.create
                                PUT credentials.deviceTokens
                                scoped by integrationIdentifier
```

## What the Android app can do

- Open and refresh an Inbox session, including JWT expiry and a 401 retry
- Keep the Novu Cloud WebSocket up, with a 25-second `ping` and reconnect backoff
- Refetch REST after reconnect and when the app returns to the foreground
- List inbox and archived notifications in a scrollable screen
- Mark read, mark unread, archive, unarchive, and delete
- Show the unread count
- Register and remove an FCM device token through the backend
- Trigger a test workflow

The subscriber id comes from `SUBSCRIBER_ID` in `server/.env`. The app does not ask for it. There is no demo password.

## Novu and Firebase setup

You need two files from the **same** Firebase project. They are not interchangeable.

1. In the [Novu dashboard](https://dashboard.novu.co), enable **Security HMAC encryption** on the In-App integration.
2. In [Firebase](https://console.firebase.google.com/), create a project (the free plan is enough).
3. Project settings → **Service accounts** → **Generate new private key**. In Novu, add an **FCM** integration and paste that whole JSON into **Service Account**. Copy the integration **Identifier**.
4. In Firebase, add an Android app whose package name is `co.novu.examples.inboxandpush`. Download `google-services.json` and save it as `app/google-services.json`.
5. In Novu, create a workflow with an **Inbox** step and a **Push** step. Set both the title and the body to `{{payload.title}}` and `{{payload.body}}`. Copy the workflow identifier.
6. From Novu **API Keys**, copy the secret key and the application identifier.

## Run the server

```bash
cd server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
```

Fill in `.env`:

```bash
NOVU_SECRET_KEY=the-secret-key
NOVU_APPLICATION_IDENTIFIER=the-application-identifier
NOVU_FCM_INTEGRATION_IDENTIFIER=the-fcm-integration-identifier
NOVU_WORKFLOW_ID=the-workflow-identifier
SUBSCRIBER_ID=demo-user
```

`SUBSCRIBER_ID` is the Novu subscriber this example uses for the inbox, the device token, and the test trigger. A production server should take the subscriber id from its own authenticated user. Do not share one environment value across real users.

```bash
source .venv/bin/activate
python -m uvicorn app.main:app --reload --port 3004
```

Check it:

```bash
curl http://127.0.0.1:3004/health
```

You want `{"status":"ok"}`.

For EU Cloud, set:

```bash
NOVU_API_URL=https://eu.api.novu.co
NOVU_INBOX_API_URL=https://eu.api.novu.co/v1
NOVU_SOCKET_URL=wss://eu.socket.novu.co
```

## Run the Android app

Install [Android Studio](https://developer.android.com/studio). Open the `app` folder (the one that contains `settings.gradle.kts`).

1. Let Gradle finish syncing.
2. Create a device in Device Manager. Use a system image that includes **Google Play**, otherwise FCM cannot register.
3. Press Run.
4. Leave the backend URL as `http://10.0.2.2:3004`. That address is the computer, seen from the emulator. A physical phone on the same Wi-Fi needs your computer's address, such as `http://192.168.1.20:3004`.
5. Tap **View inbox**.

The feed is a separate scrollable screen. Each card has buttons for mark read, mark unread, archive or unarchive, and delete. Swipe a card left to archive it. Switch the top control to **Archived** to see archived items. Tap a card to mark it read. Pull down to reload. A new notification is added at the top when the socket delivers it. The list also reloads when the socket reconnects and when you return to the app.

**Register FCM token** asks for notification permission, reads the Firebase token, and sends it to the Python server. If you deny permission, the token can still be stored, and the screen says that Android will not show a banner. Until `app/google-services.json` is added and the app is rebuilt, that button explains what is missing. You can still paste a token and tap **Register pasted token** to test storage. **Remove token on logout** deletes that device from Novu, deletes the Firebase token on the phone, and closes the Inbox session.

**Trigger test workflow** calls the Python server, which triggers the configured workflow for `SUBSCRIBER_ID`. The workflow payload includes `title`, `body`, and `deeplink`. The push step should render `{{payload.title}}` and `{{payload.body}}`. The FCM override sets the Android channel `novu` and puts the deeplink in the FCM `data` payload.

Allow notifications when Android asks. The `novu` channel is high importance, so a banner can appear while the app is in the background. Tapping it opens the inbox. The inbox row appears either way, because Inbox does not use Firebase. Put the app in the background before tapping **Trigger test workflow** if you want to see the system banner.

## How the subscriber hash is used

`POST /inbox/bootstrap` computes `HMAC-SHA256(NOVU_SECRET_KEY, SUBSCRIBER_ID)` on each call and returns it as `subscriberHash`. The app sends that hash to Novu `POST /v1/inbox/session`. The hash is not stored. Rotating the secret key does **not** require new device tokens. The app must call bootstrap again, then open a new session, because the old hash stops working.

The session response is a subscriber JWT (`aud: widget_user`), usually valid for 15 days. The app reads `exp`, refreshes five minutes early, and retries once after a 401. The socket is reopened with the new JWT.

The socket is a live hint only. After it drops, the app refetches `GET /v1/inbox/notifications` and the unread count. That is the backfill. There is no event replay.

## How FCM tokens are stored

`PUT /v1/subscribers/{subscriberId}/credentials` replaces the device-token list for one integration. The server:

1. Creates the subscriber if needed.
2. Reads `channels[]` and keeps the entry whose `providerId` is `fcm` and whose `integrationIdentifier` matches `NOVU_FCM_INTEGRATION_IDENTIFIER`.
3. Adds the new token, or removes it on logout.
4. Writes the full list back with that same integration identifier.

FCM tokens are case-sensitive. The server only trims whitespace. When Firebase rotates a token, the app removes the previous one and stores the new one.

Passing the integration identifier matters when you have more than one push integration. Without it, Novu stores the token on the most recently created integration.

A subscriber channel holds at most 100 device tokens. This server keeps the newest 100. It does not lock around two phones registering at the same instant; a production server should serialize updates per subscriber.

## Layout

- `server/app/main.py` — HTTP API the app is allowed to call
- `server/app/novu_service.py` — HMAC, subscriber, FCM credentials, trigger
- `app/src/main/java/co/novu/examples/inbox/InboxClient.kt` — Inbox REST, JWT, WebSocket
- `app/src/main/java/co/novu/examples/inbox/InboxViewModel.kt` — screen state and FCM registration
- `app/src/main/java/co/novu/examples/inbox/InboxScreen.kt` — the notification list

Self-hosted Novu uses Socket.IO for the WebSocket, not the raw Cloud socket this app opens. The REST Inbox calls are the same.
