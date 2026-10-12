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

import {
  alpha,
  createTheme,
  lighten,
  type Breakpoint,
  type PaletteColor,
  type PaletteColorOptions,
} from '@mui/material/styles';

// The brand colours. A deployment configures them in /libs/iap/conf/ThemeColor, which reaches the
// page as these meta tags, and the fallbacks are QuorumPath's. An empty `content` falls back as well
// as a missing tag, so this is `||`, not `??`.
// eslint-disable-next-line @typescript-eslint/prefer-nullish-coalescing
const primaryColor = document.querySelector<HTMLMetaElement>('meta[name="primaryColor"]')?.content || "#192958";
// eslint-disable-next-line @typescript-eslint/prefer-nullish-coalescing
const secondaryColor = document.querySelector<HTMLMetaElement>('meta[name="secondaryColor"]')?.content || "#A3213F";

// The sizes of the page shell's frame regions, kept in the theme with the rest of the styling. Each
// region has its own settings, so the start rail can stay on narrower screens than the end rail.
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
  // Backgrounds besides `default` and `paper`. `muted` is a translucent gray, for a region set apart
  // from what surrounds it. `tinted` is paper with some of the primary colour mixed in, as the frame
  // bars and an emphasised widget have. `tintedStrong` has more of the primary colour.
  interface TypeBackground {
    muted: string;
    tinted: string;
    tintedStrong: string;
  }
  // The colours a comparison of two snapshots of content marks added and removed content with. `main`
  // is for the markers, `line` the background of a changed line, and `word` the stronger shade of the
  // words that changed in it. Moved content has no `word`. Nor has `hunk`, the band that stands in for
  // unchanged content left out.
  interface DiffColors {
    main: string;
    line: string;
    word: string;
  }
  // `assistant` colours whatever an AI assistant contributed. `annotation` colours what a person marked.
  // `highlight` holds the marks both make over a document's content.
  interface TypeHighlight {
    assistant: string;
    assistantCurrent: string;
    annotation: string;
    annotationCurrent: string;
    search: string;
    searchCurrent: string;
  }
  // The colours of content that applies only under a condition: `main` for its icon, `surface` behind the line
  // saying when it applies
  interface Palette {
    condition: { main: string; surface: string };
    diff: { added: DiffColors; removed: DiffColors; moved: Omit<DiffColors, "word">; hunk: Omit<DiffColors, "word"> };
    assistant: PaletteColor;
    annotation: PaletteColor;
    highlight: TypeHighlight;
  }
  interface PaletteOptions {
    condition?: { main: string; surface: string };
    diff?: { added: DiffColors; removed: DiffColors; moved: Omit<DiffColors, "word">; hunk: Omit<DiffColors, "word"> };
    assistant?: PaletteColorOptions;
    annotation?: PaletteColorOptions;
    highlight?: TypeHighlight;
  }
  // The custom text roles. `pageTitle` is the main title of a screen, and `subheading` a muted heading
  // that introduces a part of it. `description` is secondary prose that explains, and `placeholder`
  // stands in for missing content. `code` is an identifier or a path.
  interface TypographyVariants {
    pageTitle: CSSProperties;
    subheading: CSSProperties;
    description: CSSProperties;
    placeholder: CSSProperties;
    code: CSSProperties;
  }
  interface TypographyVariantsOptions {
    pageTitle?: CSSProperties;
    subheading?: CSSProperties;
    description?: CSSProperties;
    placeholder?: CSSProperties;
    code?: CSSProperties;
  }
}

declare module "@mui/material/Button" {
  interface ButtonPropsColorOverrides {
    assistant: true;
    annotation: true;
  }
}

declare module "@mui/material/Chip" {
  interface ChipPropsColorOverrides {
    assistant: true;
    annotation: true;
  }
}

declare module "@mui/material/IconButton" {
  interface IconButtonPropsColorOverrides {
    assistant: true;
    annotation: true;
  }
}

declare module "@mui/material/SvgIcon" {
  interface SvgIconPropsColorOverrides {
    assistant: true;
    annotation: true;
  }
}

declare module "@mui/material/Typography" {
  interface TypographyPropsVariantOverrides {
    pageTitle: true;
    subheading: true;
    description: true;
    placeholder: true;
    code: true;
  }
}

// The application theme: the colours, the light and dark colour schemes, and the component defaults.
// Extend it rather than styling a component with `sx` or `styled`, so that the app stays consistent.
//
// Light and dark are both MUI colour schemes, with CSS variables. A ThemeProvider given
// `defaultMode="system"` follows the system preference, and `useColorScheme()` switches the scheme.

// Headings carry the brand's primary colour. The CSS variable follows the colour scheme, where the raw
// colour would not, so the dark scheme's lighter primary applies when the scheme switches.
const headingColor = "var(--mui-palette-primary-main)";

// The colour of muted text, as a CSS variable so that it follows the colour scheme. The variants
// below carry it, so that no call site sets it.
const mutedColor = "var(--mui-palette-text-secondary)";

// Mixed from the scheme's own CSS variables, so the same definition serves both colour schemes.
const TINTED_SURFACE = "color-mix(in srgb, var(--mui-palette-primary-main) 16%, var(--mui-palette-background-paper))";
const STRONGLY_TINTED_SURFACE =
  "color-mix(in srgb, var(--mui-palette-primary-main) 28%, var(--mui-palette-background-paper))";

// Both accents are too pale to read as text or an icon on a light surface. The light scheme writes
// with a darker shade and keeps the accent itself for fills.
const LILAC = "#C58BBD";
const SOFT_YELLOW = "#F6D96B";
// The matches of a search, which are no one's mark, wash the text with the brand's cobalt instead.
const COBALT = "#255CAA";

const ASSISTANT_LIGHT: PaletteColorOptions = { main: "#8A6184", light: LILAC, dark: "#6C4C68", contrastText: "#fff" };
const ASSISTANT_DARK: PaletteColorOptions = {
  main: LILAC,
  light: lighten(LILAC, 0.3),
  dark: "#8A6184",
  contrastText: "rgba(0, 0, 0, 0.87)",
};
const ANNOTATION_LIGHT: PaletteColorOptions = {
  main: "#866A00",
  light: SOFT_YELLOW,
  dark: "#5E4A00",
  contrastText: "#fff",
};
const ANNOTATION_DARK: PaletteColorOptions = {
  main: SOFT_YELLOW,
  light: lighten(SOFT_YELLOW, 0.3),
  dark: "#C9A93E",
  contrastText: "rgba(0, 0, 0, 0.87)",
};

// Marks over a document's own content. That content stays light in both schemes, so the marks do not
// change with the scheme. The current mark is the one in focus, such as the match a search moved to.
const HIGHLIGHT = {
  assistant: alpha(LILAC, 0.4),
  assistantCurrent: alpha(LILAC, 0.65),
  annotation: alpha(SOFT_YELLOW, 0.55),
  annotationCurrent: alpha(SOFT_YELLOW, 0.85),
  search: alpha(COBALT, 0.2),
  searchCurrent: alpha(COBALT, 0.45),
};

// MUI's default typography, which the custom variants below start from.
const baseTypography = createTheme().typography;

const appTheme = createTheme({
  // A class selector lets `useColorScheme()` switch the scheme at runtime, by setting a `light` or
  // `dark` class on the root element. With the default `media` selector, the scheme follows only the
  // system preference.
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
    // The one top-level heading of a screen, as `<Typography variant="pageTitle">`. It has h4's size
    // in bold, and renders as an `<h1>`.
    pageTitle: {
      ...baseTypography.h4,
      fontWeight: baseTypography.fontWeightBold,
      color: headingColor,
    },
    // A heading that divides a screen, such as the title of a group of dashboard widgets. It renders
    // as an `<h2>`.
    subheading: {
      ...baseTypography.overline,
      color: mutedColor,
    },
    // Small print, such as field labels and metadata lines, is always muted.
    caption: { color: mutedColor },
    overline: { color: mutedColor },
    // Secondary prose about what is on screen: the sentence under a section title, an item's
    // description, a hint, an attribution line.
    description: {
      ...baseTypography.body2,
      color: mutedColor,
    },
    // Muted text that stands in for missing content, such as an empty list ("No categories are
    // defined yet.") or content the user may not see. It looks like `description`, but is a separate
    // role, so either can change alone.
    placeholder: {
      ...baseTypography.body2,
      color: mutedColor,
    },
    // An identifier or a path, set apart from the prose around it
    code: {
      ...baseTypography.caption,
      fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace",
      color: mutedColor,
    },
    // Button labels keep their own casing. This is set on the typography rather than on MuiButton,
    // so that a `Typography variant="button"` matches the buttons.
    button: {
      textTransform: "none",
    },
  },
  iapShell: {
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
        // Shifted toward orange, so that errors do not read as the red brand secondary
        error: { main: "#C8471E" },
        assistant: ASSISTANT_LIGHT,
        annotation: ANNOTATION_LIGHT,
        highlight: HIGHLIGHT,
        // A gray canvas, against which paper stands out. `muted` is translucent, so it shades
        // whatever it lies on
        background: {
          default: "#ebedf0",
          paper: "#ffffff",
          muted: "rgba(0, 0, 0, 0.04)",
          tinted: TINTED_SURFACE,
          tintedStrong: STRONGLY_TINTED_SURFACE,
        },
        condition: { main: "#0277bd", surface: "#e6f2fa" },
        diff: {
          added: { main: "#1a7f37", line: "#e6ffec", word: "#abf2bc" },
          removed: { main: "#cf222e", line: "#ffebe9", word: "rgba(255, 129, 130, 0.4)" },
          moved: { main: "#9a6700", line: "#fffaee" },
          hunk: { main: "#4a6a8a", line: "#f2f7fc" },
        },
      },
    },
    dark: {
      palette: {
        // The brand colours are tuned for light surfaces, and are lightened to stay legible on dark ones
        primary: { main: lighten(primaryColor, 0.6) },
        secondary: { main: lighten(secondaryColor, 0.35) },
        // A lighter red, legible on dark surfaces
        error: { main: "#EE5F55" },
        assistant: ASSISTANT_DARK,
        annotation: ANNOTATION_DARK,
        highlight: HIGHLIGHT,
        background: {
          default: "#101010",
          paper: "#1a1a1a",
          muted: "rgba(255, 255, 255, 0.08)",
          tinted: TINTED_SURFACE,
          tintedStrong: STRONGLY_TINTED_SURFACE,
        },
        condition: { main: "#4fc3f7", surface: "rgba(79, 195, 247, 0.14)" },
        diff: {
          added: { main: "#3fb950", line: "rgba(46, 160, 67, 0.15)", word: "rgba(46, 160, 67, 0.4)" },
          removed: { main: "#f85149", line: "rgba(248, 81, 73, 0.15)", word: "rgba(248, 81, 73, 0.4)" },
          moved: { main: "#d29922", line: "rgba(187, 128, 9, 0.1)" },
          hunk: { main: "#8aa4c2", line: "rgba(56, 139, 253, 0.07)" },
        },
      },
    },
  },
  components: {
    // A stylesheet loaded later may set a color-scheme on :root, as PDF.js's does. These rules outrank
    // it, so native controls and light-dark() colours keep following the app's scheme.
    MuiCssBaseline: {
      styleOverrides: {
        "html.light": { colorScheme: "light" },
        "html.dark": { colorScheme: "dark" },
      },
    },
    // Paper is outlined, not elevated
    MuiPaper: {
      defaultProps: {
        variant: "outlined",
      },
    },
    // Outlined fields are transparent by default, which reads as a hole in the gray canvas
    MuiOutlinedInput: {
      styleOverrides: {
        root: ({ theme }) => ({ backgroundColor: theme.vars.palette.background.paper }),
      },
    },
    // The elements the custom variants render as. A variant missing here, such as `body1`, keeps
    // MUI's own mapping.
    MuiTypography: {
      defaultProps: {
        variantMapping: {
          pageTitle: "h1",
          subheading: "h2",
          description: "p",
          placeholder: "p",
          code: "code",
        },
      },
    },
    // A Stack spaces its children with the CSS `gap`. The default sets margins on the children, which
    // overrides their own margins and breaks with `flexWrap`.
    MuiStack: {
      defaultProps: {
        useFlexGap: true,
      },
    },
    MuiButton: {
      defaultProps: {
        disableElevation: true,
        variant: "outlined",
        // A button that should stand out, such as a page's main action, asks for its size
        size: "small",
        // The label stays beside the spinner
        loadingPosition: "start",
      },
      // The casing comes from `typography.button` above
    },
  },
});

export { appTheme };
