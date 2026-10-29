# Friend Chat

A light, private friends list and chat for Minecraft Java (Fabric, **26.3**, Java 25). Think "Essential, but less invasive":
nothing runs in the game thread, there is no account, and you do not need to host anything.

- **DMs and group chats**, **voice notes** (Opus, same codec setup as Simple Voice Chat), **pictures / GIFs / videos / files**
- **Online / in-game / offline** status for your friends (and only your friends)
- Works for **offline-mode (no license) players**: identity is a keypair made on first launch, not a Minecraft login
- **No server of your own.** It talks over public [Nostr](https://nostr.com) relays and stores files on public Blossom
  servers. Everything (messages, presence, files) is end-to-end encrypted before it leaves your PC
- Buttons on the **main menu** and **pause menu** (top-left chat bubble), or press **F6** (rebindable)
- Pixel-art look shared with PixelTunes; toast notifications for new messages, requests and errors

## Using it

1. Put `friendchat.jar` and Fabric API in `.minecraft/mods`.
2. Click the chat bubble (top-left of the main menu / pause menu) or press **F6**.
3. **COPY MY CODE** gives you a friend code (`npub1...`). Send it to a friend; they press **+ FRIEND** and paste it.
   They get a request, accept it, and you can chat.
4. In a chat: clip = send a file, mic = start/stop a voice note, type + Enter = send. Click a voice note to play it,
   click a file to save it (to `config/friend-chat/downloads`).

## Honest notes

- Messages to someone offline are kept by relays and arrive when they next connect (relays may drop old events).
- Public relays can see *who* talks to *whom* and *when*, never the content.
- Names are not globally unique (there is no central authority); friends are added by code, and the `#tag` is derived from the key.
- Settings: `config/friend-chat/friend-chat.properties` (relays, storage servers, theme, toasts, max file size).
  Your private key is `config/friend-chat/identity.key`. **Back it up and never share it.**

## Build

`./gradlew build` (JDK 25). Tests that need no Minecraft: `./test/run-tests.sh`.
The jar is also built by GitHub Actions on every push.

Code layout: `src/client/java/friendchat/core` is Minecraft-free (crypto, Nostr client, chat engine, UI layout/painting)
and fully tested; `friendchat/client` is the thin Minecraft glue. Media preparation (`core/media`) is reused from the
ChatImage mod; the voice codec setup follows Simple Voice Chat.
