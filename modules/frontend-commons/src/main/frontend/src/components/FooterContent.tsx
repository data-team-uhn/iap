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
import { useColorScheme, useTheme, type Theme } from "@mui/material/styles";
import { type SystemStyleObject } from "@mui/system";

import { type Extension } from "@iap/ui-extension/ExtensionList";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

import Logo from "./Logo";

const DATA_TEAM_URL = "https://uhndata.io";
// Below this viewport width, in pixels, the footer's links and credits stack vertically
const STACK_BELOW_WIDTH = 400;
// The DATA logo: the plain image is for a dark background, and the `_light_bg` one for a light
// background. It is the same in every deployment.
const DATA_LOGO = {
  dark: "/libs/iap/resources/media/default/data-logo.png",
  light: "/libs/iap/resources/media/default/data-logo_light_bg.png",
} as const;

interface FooterCreditsProps {
  // On a wide screen, puts the credits at the end of their row, with an automatic start margin
  endAnchored?: boolean;
  // Styles for the container, such as its placement
  sx?: SystemStyleObject<Theme>;
}

// The platform's name and version, and the "Built by DATA" credit. FooterContent shows them, and a
// page may show them elsewhere instead.
function FooterCredits({ endAnchored = false, sx }: FooterCreditsProps) {
  const { mode, systemMode } = useColorScheme();
  const resolvedMode = (mode === "system" ? systemMode : mode) ?? "light";
  // Media query matching viewports wide enough for the credits to lay out as one row
  const oneRow = useTheme().breakpoints.up(STACK_BELOW_WIDTH);
  const meta = (name: string) => document.querySelector<HTMLMetaElement>(`meta[name="${name}"]`)?.content;
  // Such as "QuorumPath 0.1.0-SNAPSHOT": the platform's own name, not the application's
  const platformVersion = [ meta("platformName"), meta("version") ].filter(Boolean).join(" ");

  return (
    <Box
      sx={[
        {
          display: "flex",
          flexDirection: "column",
          alignItems: "flex-start",
          gap: 0.5,
          [oneRow]: {
            flexDirection: "row",
            alignItems: "center",
            gap: 2,
            marginInlineStart: endAnchored ? "auto" : 0,
          },
        },
        sx ?? {},
      ]}
    >
      {platformVersion && (
        <Typography variant="caption">{platformVersion}</Typography>
      )}
      <Link
        href={DATA_TEAM_URL}
        target="_blank"
        rel="noopener"
        underline="none"
        // The logo's bottom edge sits on the text's baseline, like a glyph
        sx={{ display: "flex", alignItems: "baseline", gap: 1, color: "text.secondary" }}
      >
        <Typography variant="caption">Built by</Typography>
        <Box component="img" src={DATA_LOGO[resolvedMode]} alt="DATA" sx={{ display: "block", blockSize: 14 }} />
      </Link>
    </Box>
  );
}

interface FooterContentProps {
  // Styles for the container, such as its padding and background
  sx?: SystemStyleObject<Theme>;
  // The props of a link to the given in-app path, such as those of a react-router Link, which keep
  // the navigation in the app. By default a plain `href`, which loads the whole page.
  internalLinkProps?: (url: string) => object;
  // Whether FooterCredits are shown at the end; true by default
  credits?: boolean;
}

// The content of a page footer: the affiliated institution's logo, the links on the
// `iap/footer/link` extension point, and FooterCredits.
//
// A footer link is a data-only extension, with its label in `ext:name` and its target in
// `ext:targetURL`. A URL with a scheme, such as `https:`, opens in a new tab. Any other target is
// an in-app path.
function FooterContent(
  { sx, internalLinkProps = (url: string) => ({ href: url }), credits = true }: FooterContentProps
) {
  // Media query matching viewports wide enough for the footer to lay out as one row
  const oneRow = useTheme().breakpoints.up(STACK_BELOW_WIDTH);
  const [ links, setLinks ] = useState<Extension[]>([]);

  useEffect(() => {
    loadExtensions("FooterLink")
      .then(extensions => setLinks(extensions))
      .catch((err: unknown) => console.error("Something went wrong loading the footer links", err));
  }, []);

  return (
    <Box
      component="footer"
      sx={[
        {
          display: "flex",
          alignItems: "center",
          flexWrap: "wrap",
          gap: 2,
        },
        sx ?? {},
      ]}
    >
      <Logo source="affiliation" sx={{ display: "block", maxBlockSize: 40, maxInlineSize: 160 }} />
      {links.length > 0 && (
        <Box
          component="nav"
          aria-label="Footer"
          sx={{
            display: "flex",
            // On a narrow screen, the links stack on a full-width row of their own
            flexDirection: "column",
            flexWrap: "wrap",
            gap: 0.5,
            inlineSize: "100%",
            [oneRow]: { flexDirection: "row", gap: 2, inlineSize: "auto" },
          }}
        >
          {
            links.map((link, index) => {
              const url = (link["ext:targetURL"] as string | undefined) ?? "";
              const label = (link["ext:name"] as string | undefined) ?? url;
              const external = /^[a-z]+:/i.test(url);
              return (
                <Link
                  key={"link-" + index}
                  variant="caption"
                  color="textSecondary"
                  underline="hover"
                  {...(external
                    ? { href: url, target: "_blank", rel: "noopener" }
                    : internalLinkProps(url))}
                >
                  {label}
                </Link>
              );
            })
          }
        </Box>
      )}
      {credits && <FooterCredits endAnchored />}
    </Box>
  );
}

export default FooterContent;
export { FooterCredits };
