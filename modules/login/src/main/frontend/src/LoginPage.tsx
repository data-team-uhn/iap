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

import { Box, Typography } from "@mui/material";
import { type Theme } from "@mui/material/styles";

import FooterContent, { FooterCredits } from "@iap/frontend-commons/components/FooterContent";
import FormattedText from "@iap/frontend-commons/components/FormattedText";
import Logo from "@iap/frontend-commons/components/Logo";

import ParticipatingInstitutions from "./ParticipatingInstitutions";
import PreLoginExtensions from "./PreLoginExtensions";
import SignInMethods from "./SignInMethods";

// The panels' inner spacing
const PANEL_PADDING = { xs: 3, md: 6 } as const;
// The maximum width of the brand panel's text, in characters, so it follows the font size
const BRAND_CONTENT_WIDTH = "80ch";
// How far the brand text and the sign-in card sit below the top of their panels on wide
// screens. Both use this one value, so their tops stay level.
const CONTENT_TOP_OFFSET = "14vh";
// The line where the two panels meet, drawn only when they are side by side. It runs down the
// end edge of the brand panel and of the footer band, and outlines the pointer between them.
// MUI's `sx` builds a border from a number only for the physical border properties, so this
// logical one is written as a full CSS value.
const SEAM_COLOR = "divider";
const seamBorder = (theme: Theme) => `1px solid ${theme.vars?.palette.divider ?? theme.palette.divider}`;

// The page where a visitor without a session signs in, and the only page they can reach.
//
// It has two panels side by side, stacked on a narrow screen. The muted brand panel introduces
// the platform with its logo, tagline and description. The other panel holds the ways of signing
// in, from SignInMethods. The texts are configuration from /libs/iap/conf, which reaches the page
// as `<meta>` tags.
//
// The footer has a grid area of its own. On a wide screen it ends the brand panel's column. On a
// narrow screen it comes after the sign-in panel, at the bottom of the page.
export default function LoginPage() {
  const meta = (name: string) => document.querySelector<HTMLMetaElement>(`meta[name="${name}"]`)?.content;
  const tagline = meta("tagline");
  const introText = meta("introText");
  const signInLabel = meta("signInLabel") ?? "Sign in";
  const signInHeading = meta("signInHeading") ?? "Continue with institutional credentials";

  return (
    <Box sx={{ minBlockSize: "100dvh", display: "flex", flexDirection: "column", bgcolor: "background.paper" }}>
      <PreLoginExtensions />
      <Box
        sx={{
          flex: 1,
          display: "grid",
          // A column of `minmax(0, 1fr)` can shrink below the width of its content. A plain
          // `1fr` cannot, and on a phone the page would scroll sideways.
          gridTemplateColumns: { xs: "minmax(0, 1fr)", md: "repeat(2, minmax(0, 1fr))" },
          gridTemplateRows: { md: "1fr auto" },
          gridTemplateAreas: {
            xs: `"brand" "signin" "credits" "footer"`,
            md: `"brand signin" "footer credits"`,
          },
        }}
      >
        <Box
          component="section"
          aria-label="About the platform"
          sx={theme => ({
            gridArea: "brand",
            position: "relative",
            display: "flex",
            flexDirection: "column",
            bgcolor: "background.muted",
            borderInlineEnd: { md: seamBorder(theme) },
            p: PANEL_PADDING,
            paddingBlockEnd: { md: 0 },
          })}
        >
          { /* A small pointer from the brand panel toward the sign-in heading, level with it.
               The triangle is filled with the seam's colour. Two layers inside it, the paper
               and then the panel's translucent tint, give the panel's colour. They are
               clipped slightly smaller, so the seam outlines the pointer. */ }
          <Box
            aria-hidden="true"
            sx={theme => ({
              display: { xs: "none", md: "block" },
              position: "absolute",
              insetInlineEnd: -18,
              insetBlockStart: `calc(${theme.spacing(6)} + ${CONTENT_TOP_OFFSET} - 2px)`,
              inlineSize: 18,
              blockSize: 36,
              clipPath: "polygon(0 0, 0 100%, 100% 50%)",
              bgcolor: SEAM_COLOR,
              transform: theme.direction === "rtl" ? "scaleX(-1)" : undefined,
              // The inner triangle's slanted edges sit 1px inside the outer one's, which is
              // 1.4px along each axis for a 45-degree slant. Its base is not moved in, since
              // the panel's border already draws that line.
              "&::before, &::after": {
                content: '""',
                position: "absolute",
                inset: 0,
                clipPath: "polygon(0 1.4px, 0 calc(100% - 1.4px), calc(100% - 1.4px) 50%)",
              },
              "&::before": {
                bgcolor: "background.paper",
              },
              "&::after": {
                bgcolor: "background.muted",
              },
            })}
          />
          { /* On a wide screen, the automatic start margin puts the text against the seam, so
               the two panels' contents stay together however wide the page is. The top padding
               keeps the logo level with the sign-in card. */ }
          <Box
            sx={{
              marginInlineStart: { md: "auto" },
              paddingBlockStart: { xs: 3, md: CONTENT_TOP_OFFSET },
              paddingBlockEnd: { xs: 3, md: 5 },
              maxInlineSize: BRAND_CONTENT_WIDTH,
            }}
          >
            { /* The logo is the page's heading, and its alternative text names it */ }
            <Typography component="h1" sx={{ margin: 0 }}>
              <Logo sx={{ display: "block", inlineSize: "100%", maxInlineSize: 232 }} />
            </Typography>
            {tagline && (
              <Typography
                variant="overline"
                component="p"
                sx={{ marginBlockStart: 1, lineHeight: 1.6 }}
              >
                {tagline}
              </Typography>
            )}
            { /* An opening paragraph: body text a step larger, in a light weight so it does
                 not read as a heading. Its bold parts take the primary colour. */ }
            {introText && (
              <FormattedText
                variant="body1"
                color="textSecondary"
                sx={{
                  marginBlockStart: 8,
                  fontSize: "1.125rem",
                  fontWeight: 300,
                  lineHeight: 1.65,
                  "& strong": { color: "primary.main" },
                }}
              >
                {introText}
              </FormattedText>
            )}
          </Box>
        </Box>
        { /* On a wide screen, the card sits against the seam */ }
        <Box
          component="main"
          sx={{
            gridArea: "signin",
            display: "flex",
            flexDirection: "column",
            alignItems: { xs: "center", md: "flex-start" },
            p: PANEL_PADDING,
          }}
        >
          { /* The top margin keeps the card level with the brand text */ }
          <Box sx={{ inlineSize: "100%", maxInlineSize: 380, marginBlockStart: { md: CONTENT_TOP_OFFSET } }}>
            { /* On a wide screen, the label above the heading is taken out of the flow, so the
                 heading is what sits level with the logo. When the panels are stacked, there
                 is no space above to hold it, and it is an ordinary line. */ }
            <Box sx={{ position: "relative" }}>
              <Typography
                variant="overline"
                component="p"
                sx={{ position: { md: "absolute" }, insetBlockEnd: { md: "100%" } }}
              >
                {signInLabel}
              </Typography>
              <Typography
                variant="h6"
                component="h2"
                sx={{ color: "primary.main", fontWeight: 700, marginBlockEnd: 3, textWrap: "balance" }}
              >
                {signInHeading}
              </Typography>
            </Box>
            <SignInMethods />
            <ParticipatingInstitutions />
          </Box>
        </Box>
        <Box
          sx={theme => ({
            gridArea: "footer",
            bgcolor: "background.muted",
            // The seam continues down this band's end edge. When the panels are stacked, a
            // top border separates the band instead.
            borderInlineEnd: { md: seamBorder(theme) },
            borderBlockStart: { xs: `1px solid ${theme.vars?.palette.divider ?? theme.palette.divider}`, md: 0 },
            px: PANEL_PADDING,
          })}
        >
          { /* The same width and margin as the brand text, so the two share their start edge */ }
          <FooterContent
            credits={false}
            sx={{
              inlineSize: "100%",
              maxInlineSize: BRAND_CONTENT_WIDTH,
              marginInlineStart: { md: "auto" },
              py: 2,
            }}
          />
        </Box>
        { /* On a wide screen, the credits share the footer band's grid row, and start where
             the sign-in card starts */ }
        <Box
          sx={{
            gridArea: "credits",
            display: "flex",
            alignItems: "center",
            justifyContent: "flex-start",
            px: PANEL_PADDING,
            py: 2,
          }}
        >
          <FooterCredits />
        </Box>
      </Box>
    </Box>
  );
}
