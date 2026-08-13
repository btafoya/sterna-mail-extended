# Fastlane store metadata

This directory holds the app listing text and images in the standard
[Fastlane Supply](https://docs.fastlane.tools/actions/supply/) layout. It is
read **as-is** by [F-Droid](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/),
so no separate store copy is needed.

## Layout

```
metadata/android/<locale>/
  title.txt              # app name
  short_description.txt  # one line, <= 80 chars
  full_description.txt   # listing body (plain text)
  changelogs/<code>.txt  # per versionCode; highest matching file is shown
  images/
    icon.png             # 512x512 PNG
    featureGraphic.png   # 1024x500 PNG (optional)
    phoneScreenshots/    # 1.png, 2.png, ...
```

The locales are whichever directories exist under `metadata/android/`; list that
directory rather than trusting a list written here. Not every locale carries
every file: some hold the descriptions only. The changelog file is named after
the `versionCode` in `app/build.gradle.kts`, so each release adds a
`changelogs/<code>.txt` under **every** locale that already has changelogs.

## Images

The icon and the screenshots are committed. They live under `en-US` only and are
not duplicated per locale. The screenshots are always taken with a demo account,
never with real mail.

Refresh them when the screens they show change, so the listing never shows a
layout the published app no longer has. Refresh them by **replacing** the files
in `images/phoneScreenshots/`, which are numbered `1.png`, `2.png` and so on in
the order they are shown: F-Droid displays whatever is there, so a deleted file
silently shortens the listing by one screen. The 512×512 `images/icon.png` is
rendered from the adaptive launcher icon in `app/src/main/res/` and only changes
when that icon does.
