# Blink Live for Android TV

Watch your Blink cameras live on an Android / Google TV (built for a TCL 65P639K) with the remote.

It's an Android TV front end for the **blink-live bridge** from
[softstaticx/blink-tizen-security-dashboard](https://github.com/softstaticx/blink-tizen-security-dashboard).
That project's TV app is Samsung Tizen only; its bridge is plain Docker, so this app just talks to it.

```
Blink camera ──(Blink cloud relay)──► blink-live bridge (Docker, your LAN, :8099)
                                          │  raw MPEG-TS (low latency)  /  HLS (fallback)
                                          ▼
                                   Blink Live app on the TV (ExoPlayer)
```

Blink has no local RTSP, so live view still goes through Blink's cloud like the phone app does. Nothing else leaves your network.

## 1. Run the bridge

On any always-on machine on the same network as the TV, with Docker:

```
cd bridge
docker compose up -d --build
```

Then on your phone or PC open `http://THAT_MACHINE_IP:8099/setup`, sign in to Blink and enter the code Blink emails you. Once.

Check it: `http://THAT_MACHINE_IP:8099/api/health` should show `"ready": true`.

## 2. Install the app on the TV

1. Grab `app-release.apk` from the latest **Build APK** run (Actions tab → run → Artifacts).
2. Sideload it: e.g. `adb connect TV_IP` then `adb install -r app-release.apk`, or put it on a USB stick / use *Send Files to TV* and open it with a file manager.
3. It shows up in the apps row as **Blink Live**.

First launch asks for the bridge address. Just the IP is fine; `:8099` is added for you.

## Remote

| Where | Key | Does |
|---|---|---|
| Grid | arrows / OK | pick camera / go live |
| Grid | Settings button, or MENU | bridge address, token, audio channel |
| Live | ◀ ▶ (or CH +/-) | previous / next camera |
| Live | OK | show / hide the info bar (retry on error) |
| Live | BACK | back to the grid (frees Blink's live slot) |

## Notes

- **Waking takes a few seconds.** Battery cameras sleep; Blink wakes them on demand.
- **One live view at a time.** That's Blink. Close live view on your phone if the TV can't get a picture.
- **~5 minute limit.** Blink cuts live views around 5 minutes; the app reconnects by itself. Each reconnect wakes a battery camera again, so don't leave it on all night.
- **Raw TS first, HLS fallback.** If the TV's decoder rejects raw TS once, the app remembers and goes straight to HLS after that.
- **Silent camera?** Settings → Audio channel 2.
- **Don't port-forward 8099.** The bridge's setup page has no login.

## Licence

The bridge is GPL-3.0 (softstaticx/blink-tizen-security-dashboard, built on blinkpy). This app talks to it over HTTP and contains none of its code.
Unofficial; not affiliated with Blink, Amazon, TCL or Google. Blink can change its API whenever it fancies.
