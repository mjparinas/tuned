# Google Play store listing assets

Built for Play Console from Tuned’s brand mark and sample-data screenshots (no real providers, networks or sports brands). [premium](../decisions/premium.md).

## Upload order

| Console field              | File                                                                   |
| -------------------------- | ---------------------------------------------------------------------- |
| App name                   | see `listing.txt`                                                      |
| Short / full description   | see `listing.txt`                                                      |
| App icon (512×512)         | `assets/app-icon-512.png`                                              |
| Feature graphic (1024×500) | `assets/feature-graphic-1024x500.png`                                  |
| Phone screenshots (2–8)    | `assets/phone-01-…` through `phone-08-…` (1080×1920, 9:16)             |
| Optional landscape         | `phone-09-player-landscape.png`, `phone-10-android-tv.png` (1920×1080) |
| Optional tablet            | `assets/tablet-01.png`                                                 |
| Video                      | skip unless you have a YouTube URL                                     |

Use at least **phone-01** through **phone-04** (all ≥1080 px) for promotion eligibility.

## Rebuild

From the repo root (needs Pillow):

```bash
python3 - <<'PY'
# or re-run the generation script used to create assets/
print('See chat / regenerate from devices/public/img + docs/branding/icon-512.png')
PY
```
