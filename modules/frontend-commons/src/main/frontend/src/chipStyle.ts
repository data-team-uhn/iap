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

// The percentage of the declared color that tints a soft chip's background, and of the text color
// that goes into its border.
const SOFT_BACKGROUND_COLOR_SHARE = 10;
const BORDER_TEXT_SHARE = 22;
// The lightness bands the text is clamped into, on light and on dark surfaces. They bound OKLCH's
// perceptual lightness, which holds every hue to the same contrast. An HSL bound lets a yellow
// through that reads far brighter than a purple of the same HSL lightness. The values are unitless,
// from 0 to 1, since a percentage beside `l` makes the color invalid. The light upper bound of 0.5
// keeps every hue at AA contrast, 4.5:1, against the tinted background.
const LIGHT_TEXT_LIGHTNESS = "clamp(0.3, l, 0.5)";
const DARK_TEXT_LIGHTNESS = "clamp(0.65, l, 0.85)";

// The style of a colored chip, such as a tag, for a Chip's `sx` or its inline style. The background
// color has no transition. When the color scheme changes, only the CSS variable inside a
// `color-mix()` background changes, and Chrome keeps the old color instead of moving to the new one.
// The filled variant has no such variable, and no transition either.
export interface ChipStyle {
  backgroundColor: string;
  color: string;
  border?: string;
  transition: "none";
}

// Whether the browser can resolve the text color readableText builds. An environment without
// `CSS.supports`, such as jsdom, counts as able.
function supportsAdaptiveText(): boolean {
  if (typeof CSS === "undefined" || typeof CSS.supports !== "function") {
    return true;
  }
  return CSS.supports("color", "light-dark(red, blue)")
    && CSS.supports("color", "oklch(from red 0.5 c h)");
}

// The declared color, clamped into a lightness band that is readable on the active color scheme.
// `light-dark()` follows the `color-scheme` MUI sets for each scheme, in inline styles too. A browser
// that cannot resolve it gets the declared color unchanged.
function readableText(color: string): string {
  if (!supportsAdaptiveText()) {
    return color;
  }
  return `light-dark(oklch(from ${color} ${LIGHT_TEXT_LIGHTNESS} c h), `
    + `oklch(from ${color} ${DARK_TEXT_LIGHTNESS} c h))`;
}

// Black or white, whichever text color is readable on the fill. MUI's palette helpers parse only
// comma-separated rgb() and hsl(). A space-separated color, which safeCssColor accepts, gets a NaN
// luminance, and getContrastText answers white even for a near-white fill. So the separators become
// commas first.
function contrastText(theme: Theme, color: string): string {
  const open = color.indexOf("(");
  if (open < 0) {
    return theme.palette.getContrastText(color);
  }
  const channels = color.slice(open + 1, -1).trim().replace(/\s*[,/]\s*/g, ",").replace(/\s+/g, ",");
  return theme.palette.getContrastText(`${color.slice(0, open)}(${channels})`);
}

// The style of a chip that declares a color, by variant; undefined if the color is missing or not
// one safeCssColor accepts. The color comes from content people edit, and goes into generated CSS.
//
// Any variant but "filled" and "outlined" is "soft". It derives everything from the one color, in
// CSS that follows the color scheme. JavaScript cannot branch on the scheme here: with the theme's
// CSS variables, `theme.palette.mode` always reads "light". The background is a tint of the color
// over the scheme's paper. The text is the color clamped into a readable lightness. The border
// mixes the two, so that the chip keeps an edge on a surface of the same hue.
//
// "outlined" has no background, and the clamped color as its text and border.
//
// "filled" has the color as its fill, under black or white text.
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
  // The CSS variable makes the tint follow the color scheme. A theme without CSS variables, such as
  // a test's default theme, has only the static paper color.
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
