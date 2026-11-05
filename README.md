# Blink Live for Android TV

Watch your Blink cameras live on an Android / Google TV (built for a TCL 65P639K), driven by the remote. No server, no bridge: the app talks to Blink directly.

```
Blink camera ──► Blink's cloud relay ──(IMMIS over TLS)──► Blink Live on the TV
                                                            └─ unwraps MPEG-TS, plays it with ExoPlayer
```

It's a Java port of the bits of [blinkpy](https://github.com/fronzbot/blinkpy) it needs — OAuth sign-in with the emailed code, the homescreen camera list, and the IMMIS live-view protocol — inspired by [softstaticx/blink-tizen-security-dashboard](https://github.com/softstaticx/blink-tizen-security-dashboard).

## Install

1. Grab `app-release.apk` from the latest **Build APK** run (Actions tab → run → Artifacts).
2. Sideload it, e.g. `adb connect TV_IP` then `adb install -r app-release.apk`.
3. Open **Blink Live** from the apps row, sign in with your Blink email and password, then type the code Blink emails you. Once.

The Google TV app on your phone makes typing the password a lot less grim. The password goes to Blink only and isn't stored; the TV keeps Blink's refresh token.

## Remote

| Where | Key | Does |
|---|---|---|
| Grid | arrows / OK | pick camera / go live |
| Grid | Settings, or MENU | audio channel, sign out |
| Live | ◀ ▶ (or CH +/-) | previous / next camera |
| Live | OK | show / hide the info bar (retry on error) |
| Live | BACK | back to the grid (frees Blink's live slot) |

## Notes

- **Waking takes a few seconds.** Battery cameras sleep; Blink wakes them on demand.
- **One live view at a time.** That's Blink. Close live view on your phone if the TV can't get a picture.
- **~5 minute limit.** Blink cuts live views around 5 minutes; the app reconnects by itself. Each reconnect wakes a battery camera again, so don't leave it on all night.
- **Silent camera?** Settings → Audio channel 2.
- **If sign-in suddenly breaks,** Blink has probably changed its login again. Check blinkpy for the fix and port it into `BlinkApi.java`.

## Licence

Unofficial; not affiliated with Blink, Amazon, TCL or Google. Blink can change its API whenever it fancies.
