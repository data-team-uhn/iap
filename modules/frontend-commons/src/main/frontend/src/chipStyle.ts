/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { type Theme } from "@mui/material/styles";

import { safeCssColor } from "./safeColor";

// The tunables of the soft chip derivation, in one place: how much of the declared color
// tints the background, how much of the text color goes into the border, and the lightness
// bands the text is clamped into on light and dark surfaces.
const SOFT_BACKGROUND_COLOR_SHARE = 10;
const BORDER_TEXT_SHARE = 22;
// The clamp works on OKLCH's perceptual lightness (a unitless 0-1 channel; mixing in
// percentages would invalidate the whole color): HSL's lightness would let a "45% light"
// yellow through that still reads blazing bright next to an equally-"light" purple, while
// an OKLCH bound holds every hue to the same perceived lightness, and thus contrast. The
// light-mode upper bound of 0.5 is what keeps every hue at AA contrast (4.5:1 for the small
// chip label) against the tinted background — at 0.55, full-brightness greens and cyans
// slip to about 3.8.
const LIGHT_TEXT_LIGHTNESS = "clamp(0.3, l, 0.5)";
const DARK_TEXT_LIGHTNESS = "clamp(0.65, l, 0.85)";

// The style of a colored chip (e.g. a tag), ready to spread into a Chip's sx or to use as
// its inline style. The stock background-color transition is disabled on every styled chip:
// when the color scheme flips, only the CSS variable inside a color-mix() background
// changes, and Chrome then keeps the stale resolved color indefinitely instead of
// transitioning to the new one. (The filled variant has no such variable, but keeps the
// disabled transition for a uniform contract.)
export interface ChipStyle {
  backgroundColor: string;
  color: string;
  border?: string;
  transition: "none";
}

// Whether the browser can resolve the scheme-adaptive text recipe below.
// An environment with no CSS.supports at all (jsdom, server rendering) is treated as capable.
function supportsAdaptiveText(): boolean {
  if (typeof CSS === "undefined" || typeof CSS.supports !== "function") {
    return true;
  }
  return CSS.supports("color", "light-dark(red, blue)")
    && CSS.supports("color", "oklch(from red 0.5 c h)");
}

// The declared color pulled into a lightness band readable on the active scheme: light-dark()
// follows the color-scheme MUI sets per scheme, even in inline styles.
// Where that cannot be resolved, the declared color stands in unchanged.
function readableText(color: string): string {
  if (!supportsAdaptiveText()) {
    return color;
  }
  return `light-dark(oklch(from ${color} ${LIGHT_TEXT_LIGHTNESS} c h), `
    + `oklch(from ${color} ${DARK_TEXT_LIGHTNESS} c h))`;
}

// The black-or-white text a fill needs to stay readable. MUI's palette helpers only parse the
// legacy comma-separated rgb()/hsl() channels, and answer the modern space-separated notation --
// which safeCssColor accepts just as happily -- with a NaN luminance that getContrastText silently
// reads as "dark background", handing back white text for even a near-white fill. Normalizing the
// separators first keeps the contrast decision honest for every notation a definition may use.
function contrastText(theme: Theme, color: string): string {
  const open = color.indexOf("(");
  if (open < 0) {
    return theme.palette.getContrastText(color);
  }
  const channels = color.slice(open + 1, -1).trim().replace(/\s*[,/]\s*/g, ",").replace(/\s+/g, ",");
  return theme.palette.getContrastText(`${color.slice(0, open)}(${channels})`);
}

// How a chip declaring a color should be styled, per its variant, or undefined for a plain
// unstyled chip. The color comes from repository-editable content and is interpolated into
// generated CSS, so it is whitelisted here (see safeCssColor); an unusable value counts as
// not declared.
//
// The default "soft" variant derives everything from the one color as scheme-adaptive CSS —
// deliberately with no JavaScript branching on the mode, which would not even work: under the
// theme's CSS-variable color schemes, theme.palette.mode always reads "light". The background
// is a tint of the color over the scheme's own paper, the text is the color clamped into a
// readable lightness band (see readableText), and the border mixes the two, so same-hue chips
// keep a crisp edge on any surface.
//
// The "outlined" variant is quieter: no background at all, just the readably-clamped color as
// the text and the border.
//
// The "filled" variant is the classic loud chip: the color as the fill, under a contrasting
// black or white text.
export function chipStyle(theme: Theme, color?: string, variant?: string): ChipStyle | undefined {
  const safeColor = safeCssColor(color);
  if (!safeColor) {
    return undefined;
  }
  if (variant === "filled") {
    return {
      backgroundColor: safeColor,
      color: contrastText(theme, safeColor),
      transition: "none",
    };
  }
  if (variant === "outlined") {
    const textColor = readableText(safeColor);
    return {
      backgroundColor: "transparent",
      color: textColor,
      border: `1px solid ${textColor}`,
      transition: "none",
    };
  }
  // Mixing over the CSS variable makes the tint follow the active scheme; only a theme
  // without CSS variables (like the defaults created in tests) falls back to static paper
  const surface = theme.vars?.palette.background.paper ?? theme.palette.background.paper;
  const backgroundColor = `color-mix(in srgb, ${safeColor} ${SOFT_BACKGROUND_COLOR_SHARE}%, ${surface})`;
  const textColor = readableText(safeColor);
  return {
    backgroundColor,
    color: textColor,
    border: `1px solid color-mix(in srgb, ${textColor} ${BORDER_TEXT_SHARE}%, ${backgroundColor})`,
    transition: "none",
  };
}
