---
name: TelePlay
description: An Apple TV-style Android TV player: black ground, full-bleed hero, horizontal shelves, white-on-focus capsule controls.
colors:
  background: "#000000"
  content1: "#1C1C1E"
  content2: "#2C2C2E"
  content3: "#3A3A3C"
  foreground: "#FFFFFF"
  foreground-muted: "#98989F"
  divider: "#FFFFFF1F"
  primary: "#FFFFFF"
  primary-soft: "#E5E5EA"
  warning: "#F5A524"
  danger: "#F31260"
  danger-soft: "#F54180"
  success: "#17C964"
typography:
  display:
    fontFamily: "Roboto (FontFamily.Default)"
    fontSize: "40sp"
    fontWeight: 700
    lineHeight: "48sp"
    letterSpacing: "-0.5sp"
  headline:
    fontFamily: "Roboto (FontFamily.Default)"
    fontSize: "32sp"
    fontWeight: 600
    lineHeight: "40sp"
  title:
    fontFamily: "Roboto (FontFamily.Default)"
    fontSize: "20sp"
    fontWeight: 600
    lineHeight: "28sp"
  body:
    fontFamily: "Roboto (FontFamily.Default)"
    fontSize: "20sp"
    fontWeight: 400
    lineHeight: "30sp"
  label:
    fontFamily: "Roboto (FontFamily.Default)"
    fontSize: "16sp"
    fontWeight: 500
    lineHeight: "22sp"
rounded:
  sm: "8dp"
  md: "12dp"
  lg: "16dp"
  xl: "24dp"
spacing:
  gap: "16dp"
  screen: "48dp"
  panel: "40dp"
components:
  button-solid:
    backgroundColor: "{colors.primary}"
    textColor: "#FFFFFF"
    rounded: "{rounded.md}"
    padding: "14dp 28dp"
  button-flat:
    backgroundColor: "{colors.content2}"
    textColor: "{colors.foreground}"
    rounded: "{rounded.md}"
    padding: "14dp 28dp"
  card:
    backgroundColor: "{colors.content1}"
    rounded: "{rounded.lg}"
---

# Design System: TelePlay

## Overview

**Creative North Star: "The Living-Room Cinema"**

TelePlay follows the Apple TV app: a black ground, full-bleed artwork fading into it, large titles, and horizontal shelves of 16:9 cards. White is the only accent. Dark only, because the scene is a dim living room.

**Key Characteristics:**
- One focus language: the focused thing lifts (scale 1.08) and turns white. Capsule buttons fill white with black text; cards gain a 3dp white edge.
- Resting controls are translucent glass (white at 20%).
- The primary action (Play / Resume) is a solid white capsule with a ring when focused.
- Gradients are scrims only: they fade artwork to black so text stays legible.

## Layout

Library: a hero (80% of screen height) with artwork, title, details, resume progress and Play / Resume (+ Start over); a top bar with wordmark, Refresh and Settings; then shelves "Continue watching" (320dp cards) and "Recently added" (256dp cards). 48dp screen edge, 20dp card gap. Initial focus: the hero's Play button, or the card the viewer just left.

Player: video full screen; on interaction a top and bottom scrim, state glyph and title, a thin white scrubber with elapsed and remaining time at its ends, and glass buttons top right.

## Components

- `HeroButton`: capsule, glass at rest, white when focused. `solid` = white at rest.
- `HeroChip`: glass pill for status; warning tint for offline.
- Video card: artwork, thin white resume bar, title and "x min left" or "duration · date" below.

## Do's and Don'ts

- Do keep the Hero* components in `ui/common/HeroUi.kt` for new UI (names kept, values are tvOS).
- Don't add a second accent colour; state colours appear only with text.
- Don't put a ring on every control: lift and white fill are the focus signal.
