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

import { Box, type SxProps } from '@mui/material';
import { useColorScheme, type Theme } from '@mui/material/styles';

interface LogoProps {
  // Which image: the application's logo, by default, or the logo of the institution the
  // deployment belongs to
  source?: "app" | "affiliation";
  // The image's size and placement
  sx?: SxProps<Theme>;
}

// A branding image configured under /libs/iap/conf, in the variant for the current colour scheme.
// The page's `<meta>` tags carry it. The application's logo is `logoLight` or `logoDark`, named by
// `title`. The institution's is `affiliationLogoLight` or `affiliationLogoDark`, named by
// `affiliationName`. Renders nothing if the deployment configures no such image.
export default function Logo({ source = "app", sx }: LogoProps) {
  const { mode, systemMode } = useColorScheme();
  const resolvedMode = (mode === "system" ? systemMode : mode) ?? "light";
  const variant = resolvedMode === "dark" ? "Dark" : "Light";
  const meta = (name: string) => document.querySelector<HTMLMetaElement>(`meta[name="${name}"]`)?.content;

  const src = meta(`${source === "app" ? "logo" : "affiliationLogo"}${variant}`);
  if (!src) {
    return null;
  }
  return <Box component="img" src={src} alt={meta(source === "app" ? "title" : "affiliationName") ?? ""} sx={sx} />;
}
