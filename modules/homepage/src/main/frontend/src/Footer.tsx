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

import { useEffect, useState } from "react";

import { Box, Link, Typography } from "@mui/material";
import { useColorScheme, useTheme } from "@mui/material/styles";
import { Link as RouterLink } from "react-router";

import Logo from "@iap/frontend-commons/components/Logo";
import { type Extension } from "@iap/ui-extension/ExtensionList";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

const DATA_TEAM_URL = "https://uhndata.io";
// Below this viewport width (px) the footer's links and version/credit stack vertically
const STACK_BELOW_WIDTH = 400;
// The DATA logo pair follows the media naming convention: the plain variant suits dark
// backgrounds, the _light_bg variant light ones. The same in every deployment, so not configurable.
const DATA_LOGO = {
  dark: "/libs/iap/resources/media/default/data-logo.png",
  light: "/libs/iap/resources/media/default/data-logo_light_bg.png",
} as const;

// The standard page footer: the affiliated institution's logo at the start (configured through
// the affiliationLogo* properties of /libs/iap/conf/Media), links contributed through the
// `iap/footer/link` extension point in the middle, and the platform version and the
// "Built by DATA" credit at the end.
//
// A footer link is a data-only extension — no component, just an `iap:extensionName` (the
// label) and an `iap:targetURL`: paths are navigated within the app, full URLs open in a new
// tab.
// Registered on the `iap/coreUI/pageBottom` extension point, so it scrolls with the content.
function Footer() {
  const { mode, systemMode } = useColorScheme();
  const resolvedMode = (mode === "system" ? systemMode : mode) ?? "light";
  // Media query matching viewports wide enough for the footer to lay out as one row
  const oneRow = useTheme().breakpoints.up(STACK_BELOW_WIDTH);
  const [ links, setLinks ] = useState<Extension[]>([]);

  useEffect(() => {
    loadExtensions("FooterLink")
      .then(extensions => setLinks(extensions))
      .catch((err: unknown) => console.error("Something went wrong loading the footer links", err));
  }, []);
  const meta = (name: string) => document.querySelector<HTMLMetaElement>(`meta[name="${name}"]`)?.content;
  // E.g. "IAP 0.1.0-SNAPSHOT" — the platform identifies itself independently of the app branding
  const platformVersion = [ meta("platformName"), meta("version") ].filter(Boolean).join(" ");

  return (
    <Box
      component="footer"
      sx={{
        display: "flex",
        alignItems: "center",
        flexWrap: "wrap",
        gap: 2,
        px: 3,
        py: 2,
        bgcolor: "background.muted",
      }}
    >
      <Logo source="affiliation" sx={{ display: "block", maxBlockSize: 40, maxInlineSize: 160 }} />
      {links.length > 0 && (
        <Box
          component="nav"
          aria-label="Footer"
          sx={{
            display: "flex",
            // On narrow screens the links stack vertically on their own full-width row, instead
            // of wrapping awkwardly around the other footer content
            flexDirection: "column",
            flexWrap: "wrap",
            gap: 0.5,
            inlineSize: "100%",
            [oneRow]: { flexDirection: "row", gap: 2, inlineSize: "auto" },
          }}
        >
          {
            links.map((link, index) => {
              const url = (link["iap:targetURL"] as string | undefined) ?? "";
              const label = (link["iap:extensionName"] as string | undefined) ?? url;
              const external = /^[a-z]+:/i.test(url);
              return (
                <Link
                  key={"link-" + index}
                  variant="caption"
                  color="text.secondary"
                  underline="hover"
                  {...(external
                    ? { href: url, target: "_blank", rel: "noopener" }
                    : { component: RouterLink, to: url })}
                >
                  {label}
                </Link>
              );
            })
          }
        </Box>
      )}
      { /* marginInlineStart keeps this cluster at the end of the row whether or not an
           affiliation logo is shown; on narrow screens it stacks vertically instead, aligned
           with the stacked links above it */ }
      <Box
        sx={{
          display: "flex",
          flexDirection: "column",
          alignItems: "flex-start",
          gap: 0.5,
          [oneRow]: {
            flexDirection: "row",
            alignItems: "center",
            gap: 2,
            marginInlineStart: "auto",
          },
        }}
      >
        {platformVersion && (
          <Typography variant="caption" color="text.secondary">{platformVersion}</Typography>
        )}
        <Link
          href={DATA_TEAM_URL}
          target="_blank"
          rel="noopener"
          underline="none"
          // Baseline alignment sets the logo's bottom edge on the text's baseline, like a glyph,
          // instead of letting it hang below (as centering the taller image would)
          sx={{ display: "flex", alignItems: "baseline", gap: 1, color: "text.secondary" }}
        >
          <Typography variant="caption">Built by</Typography>
          <Box component="img" src={DATA_LOGO[resolvedMode]} alt="DATA" sx={{ display: "block", blockSize: 14 }} />
        </Link>
      </Box>
    </Box>
  );
}

export default Footer;
