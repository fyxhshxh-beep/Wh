# FriendCall V2 — Android starter

Native Kotlin + Jetpack Compose app with Firebase Authentication/Firestore and the Jitsi Meet Android SDK.

## Included
- Email/password sign-up and sign-in
- Basic user profile records
- Friend requests by email and accepting requests
- Basic one-to-one text chat
- Create/share a room code and launch an embedded Jitsi voice/video conference
- Screen sharing through Jitsi's Android conference UI

## Setup
1. Open this folder in Android Studio (JDK 17).
2. Create a Firebase project and register Android package `com.friendcall.app`.
3. Enable Email/Password in Firebase Authentication.
4. Create Cloud Firestore.
5. Download `google-services.json` into `app/`.
6. Sync Gradle and run on a physical Android device.

Jitsi SDK is integrated as `org.jitsi.react:jitsi-meet-sdk:2.4.0` from the official Jitsi Maven repository. The SDK's Android call UI supports screen-share controls. During a call, open **More → Share your screen** and approve Android's system screen-capture prompt. Stop sharing from the call UI. Android's permission prompt is required by design.

## Firestore rules
These are a development starting point, not production-grade security. Review carefully before inviting people or deploying publicly.

```text
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    function signedIn() { return request.auth != null; }

    match /users/{uid} {
      allow read: if signedIn();
      allow create, update: if signedIn() && request.auth.uid == uid;
    }

    match /friendRequests/{requestId} {
      allow create: if signedIn()
        && request.resource.data.fromUid == request.auth.uid;
      allow read: if signedIn()
        && (resource.data.fromUid == request.auth.uid
          || resource.data.toUid == request.auth.uid);
      allow update: if signedIn()
        && resource.data.toUid == request.auth.uid;
    }

    match /friendships/{friendshipId} {
      allow read: if signedIn() && request.auth.uid in resource.data.members;
      allow create: if signedIn() && request.auth.uid in request.resource.data.members;
    }

    match /chats/{chatId} {
      allow read: if signedIn() && request.auth.uid in resource.data.members;
      allow create: if signedIn() && request.auth.uid in request.resource.data.members;
      allow update: if signedIn() && request.auth.uid in resource.data.members;

      match /messages/{messageId} {
        function isChatMember() {
          return signedIn()
            && request.auth.uid in get(/databases/$(database)/documents/chats/$(chatId)).data.members;
        }
        allow read: if isChatMember();
        allow create: if isChatMember()
          && request.resource.data.senderUid == request.auth.uid;
      }
    }
  }
}
```

**Important:** The sample rules are still a starting point. Add strict validation for friend-request state changes, friendship creation, message size/rate limits, blocking/reporting and abuse controls before production. Public Jitsi rooms are not private merely because the room name is hard to guess. For private calls, use an authenticated Jitsi deployment with room authentication and tokens.

## Quality notes
The app requests camera and microphone access when needed. Screen capture uses Android's own consent dialog and the Jitsi SDK's screen-sharing implementation. Video/screen-share resolution adapts to device capability, bandwidth, and server conditions; 1080p cannot be guaranteed. Prefer strong Wi-Fi for clearer screen text and smoother motion.

## Known limitations
- Friend search is exact-email matching and only finds people who have already created a FriendCall account.
- Chat is a basic text-message MVP; no push notifications, read receipts, typing status, media attachments, or encryption layer beyond the service transport.
- Friend-request and chat Firestore rules need hardening before real-world release.
- Calls use the public `meet.jit.si` service by default; room names are not an access-control system.
- This is source code, not a compiled APK. Firebase setup and a local Android Studio build are required.
