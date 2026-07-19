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

import { createTheme, lighten } from '@mui/material/styles';

const primaryColor = document.querySelector<HTMLMetaElement>('meta[name="primaryColor"]')?.content || "#003366";
const secondaryColor = document.querySelector<HTMLMetaElement>('meta[name="secondaryColor"]')?.content || "#f94900";

// The application theme, and the single home for styling. Colours, the light/dark colour schemes,
// and component defaults all live here, so the rest of the app doesn't have to hand-roll its own
// styles (and keep them in sync) to stay consistent. Prefer extending this theme over reaching for
// ad-hoc `sx`/`styled` in components.
//
// Light and dark are both first-class MUI colour schemes (CSS variables). The active scheme follows
// the user's system preference by default (see the ThemeProvider `defaultMode="system"` at each
// entry point) and can be switched at runtime via MUI's `useColorScheme()`.
const appTheme = createTheme({
  // A class-based selector (rather than the default `media`) is what lets `useColorScheme()`
  // switch the scheme at runtime — MUI toggles a `light`/`dark` class on the root element. With
  // `media` the scheme would only follow the OS preference and the toggle would do nothing.
  cssVariables: {
    colorSchemeSelector: "class",
  },
  colorSchemes: {
    light: {
      palette: {
        primary: { main: primaryColor },
        secondary: { main: secondaryColor },
      },
    },
    dark: {
      palette: {
        // The brand primary is typically dark (tuned for light surfaces); lighten it so it stays
        // legible on the dark scheme's dark surfaces.
        primary: { main: lighten(primaryColor, 0.6) },
        secondary: { main: secondaryColor },
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
    MuiButton: {
      defaultProps: {
        disableElevation: true,
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
