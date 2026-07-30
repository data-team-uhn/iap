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

import type { CSSProperties } from 'react';

import { alpha, createTheme, lighten, type Breakpoint } from '@mui/material/styles';

// Fallbacks matching the QuorumPath brand; deployments configure their own colours through
// /libs/iap/conf/ThemeColor, which reaches the page as these meta tags
// An explicitly empty content attribute should fall back to the default too, not just a missing
// meta tag, so `||` (not `??`) is intentional here.
// eslint-disable-next-line @typescript-eslint/prefer-nullish-coalescing
const primaryColor = document.querySelector<HTMLMetaElement>('meta[name="primaryColor"]')?.content || "#192958";
// eslint-disable-next-line @typescript-eslint/prefer-nullish-coalescing
const secondaryColor = document.querySelector<HTMLMetaElement>('meta[name="secondaryColor"]')?.content || "#C0233C";

// The dimensions of the page shell (see PageLayout in the homepage module), read from the theme
// so they are configured here, alongside the rest of the styling.
// Each frame region is configured independently — e.g. a navigation-like start rail can stick
// around on narrower screens than a nice-to-have end rail.
interface RailConfig {
  // The inline size (width) of the rail, in px.
  width: number;
  // The viewport width below which the rail collapses into an edge pull-tab drawer:
  // a breakpoint name or a px number.
  collapseWidth: number | Breakpoint;
}

interface BarConfig {
  // The viewport height below which the bar collapses into an edge pull-tab drawer, in px.
  collapseHeight: number;
}

interface IapShellConfig {
  frameStart: Partial<RailConfig>;
  frameEnd: Partial<RailConfig>;
  frameTop: Partial<BarConfig>;
  frameBottom: Partial<BarConfig>;
}

declare module "@mui/material/styles" {
  interface Theme {
    iapShell?: Partial<IapShellConfig>;
  }
  interface ThemeOptions {
    iapShell?: Partial<IapShellConfig>;
  }
  // Two backgrounds besides `default` and `paper`: `muted` is a subtly tinted static surface for
  // page regions that should read as "background, but set apart"; `admin` is the canvas of the
  // administration area, a faint tint of the primary brand colour signalling that the user is
  // somewhere with more responsibility (and giving paper surfaces contrast to stand out against).
  interface TypeBackground {
    muted: string;
    admin: string;
  }
  // A dedicated palette slot for marking administrative zones (e.g. the border around the admin
  // working panel), so that marking can be tuned - or diverged from the brand secondary - in one
  // place. It currently matches secondary.
  interface Palette {
    admin: Palette["secondary"];
  }
  interface PaletteOptions {
    admin?: PaletteOptions["secondary"];
  }
  // The main title of a screen (see `pageTitle` in the typography section below)
  interface TypographyVariants {
    pageTitle: CSSProperties;
  }
  interface TypographyVariantsOptions {
    pageTitle?: CSSProperties;
  }
}

declare module "@mui/material/Typography" {
  interface TypographyPropsVariantOverrides {
    pageTitle: true;
  }
}

// The application theme, and the single home for styling. Colours, the light/dark colour schemes,
// and component defaults all live here, so the rest of the app doesn't have to hand-roll its own
// styles (and keep them in sync) to stay consistent. Prefer extending this theme over reaching for
// ad-hoc `sx`/`styled` in components.
//
// Light and dark are both first-class MUI colour schemes (CSS variables). The active scheme follows
// the user's system preference by default (see the ThemeProvider `defaultMode="system"` at each
// entry point) and can be switched at runtime via MUI's `useColorScheme()`.
// Headings carry the brand's primary colour. Referencing the palette through its CSS variable
// (rather than the raw colour) keeps them scheme-aware: the dark scheme's lightened primary
// applies automatically when the scheme switches.
const headingColor = "var(--mui-palette-primary-main)";

// A default theme, used only to read MUI's standard typography metrics when deriving the custom
// variants below, so they don't hardcode (and drift from) the library's values.
const baseTypography = createTheme().typography;

const appTheme = createTheme({
  // A class-based selector (rather than the default `media`) is what lets `useColorScheme()`
  // switch the scheme at runtime — MUI toggles a `light`/`dark` class on the root element. With
  // `media` the scheme would only follow the OS preference and the toggle would do nothing.
  cssVariables: {
    colorSchemeSelector: "class",
  },
  typography: {
    h1: { color: headingColor },
    h2: { color: headingColor },
    h3: { color: headingColor },
    h4: { color: headingColor },
    h5: { color: headingColor },
    h6: { color: headingColor },
    // The main title of a screen: h4-sized (the h1-h3 styles are outsized for an app page title)
    // but bold, and rendered as a semantic <h1> (see the MuiTypography variant mapping below).
    // Use it as `<Typography variant="pageTitle">` for the one top-level heading of any view.
    pageTitle: {
      ...baseTypography.h4,
      fontWeight: baseTypography.fontWeightBold,
      color: headingColor,
    },
  },
  iapShell: {
    // The start rail hosts the more important (navigation-like) content, so it stays in the
    // page flow on narrower screens than the auxiliary end rail.
    frameStart: { width: 200, collapseWidth: "md" },
    frameEnd: { width: 200, collapseWidth: "xl" },
    frameTop: { collapseHeight: 500 },
    frameBottom: { collapseHeight: 500 },
  },
  colorSchemes: {
    light: {
      palette: {
        primary: { main: primaryColor },
        secondary: { main: secondaryColor },
        admin: { main: secondaryColor },
        // Translucent, so they compose with whatever they overlap rather than assuming white
        background: { muted: "rgba(0, 0, 0, 0.04)", admin: alpha(primaryColor, 0.06) },
      },
    },
    dark: {
      palette: {
        // The brand primary is typically dark (tuned for light surfaces); lighten it so it stays
        // legible on the dark scheme's dark surfaces.
        primary: { main: lighten(primaryColor, 0.6) },
        secondary: { main: secondaryColor },
        admin: { main: secondaryColor },
        // The same lightened primary as the scheme's primary colour, so the tint stays a hue
        // shift rather than a muddy darkening
        background: { muted: "rgba(255, 255, 255, 0.08)", admin: alpha(lighten(primaryColor, 0.6), 0.1) },
      },
    },
  },
  components: {
    // Outlined by default across the app — a flatter, more intentional surface than the elevated
    // default. Widgets and other cards inherit this automatically.
    MuiPaper: {
      defaultProps: {
        variant: "outlined",
      },
    },
    // Custom variants map to their proper document-outline element; the built-in variants keep
    // their default mapping (Typography falls back to it per variant).
    MuiTypography: {
      defaultProps: {
        variantMapping: {
          pageTitle: "h1",
        },
      },
    },
    // Space Stack children with the CSS gap property instead of the default margin takeover,
    // which overwrites children's own margins and misbehaves with flexWrap
    MuiStack: {
      defaultProps: {
        useFlexGap: true,
      },
    },
    MuiButton: {
      defaultProps: {
        disableElevation: true,
        variant: "outlined",
      },
      styleOverrides: {
        root: {
          textTransform: "none",
        },
      },
    },
  },
});

export { appTheme };
