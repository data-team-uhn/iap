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

import LoginForm from "./LoginForm";
import { loginRedirectPath } from "./loginRedirect";
import ParticipatingInstitutions from "./ParticipatingInstitutions";
import PreLoginExtensions from "./PreLoginExtensions";

// The panels' inset, and the maximum width of the brand panel's content column: capped at a
// reading measure rather than a fixed pixel width, so it follows the font size. The cap is
// measured in the block's base font (16px), and is sized so that the intro's first sentence
// (~71 characters at its enlarged lede size) fits on one line on wide screens — see the hard
// line break in the introText content, which starts the "faster" sentence on its own line.
const PANEL_PADDING = { xs: 3, md: 6 } as const;
const BRAND_CONTENT_WIDTH = "80ch";
// The distance of the two content blocks (brand text, sign-in card) from their panels' top
// edge on wide screens: sharing one value is what guarantees their tops stay aligned,
// whatever either block's height
const CONTENT_TOP_OFFSET = "14vh";
// The marker connecting the two panels — "tab": a muted triangle protruding from the brand
// panel (the conservative default); "node": the wordmark's crimson dot-and-ring riding the
// seam, kept implemented pending a team decision (it draws the eye strongly — a candidate
// for an attention marker elsewhere rather than for this page).
const SEAM_MARKER = "tab" as "tab" | "node";
// The seam where the two panels meet (the brand panel's end border, continued by the footer
// band's, with the pointer's outline matching): the standard divider colour, adapting to both
// colour schemes. Only drawn in the side-by-side layout. The border is spelled out as a full
// CSS value: logical border properties are not part of the sx border system, so a bare width
// number would pass through as invalid CSS and silently draw nothing.
const SEAM_COLOR = "divider";
const seamBorder = (theme: Theme) => `1px solid ${theme.vars?.palette.divider ?? theme.palette.divider}`;

// The landing page shown to unauthenticated visitors, and the only page they can reach.
//
// A split layout: the muted brand panel introduces the platform (logo, tagline, description,
// and the footer with links, version and credits), while the paper panel signs the user in.
// The whole page is content-driven: every text comes from /libs/iap/conf/LoginPage (plus the
// shared AppName/Media/Version configuration), so deployments rebrand without code changes.
// The footer is a grid-area sibling of the two panels rather than a child of the brand panel:
// on wide screens it renders as the bottom of the muted column, and when the panels stack on
// narrow screens it slides under the sign-in panel, to the bottom of the page.
//
// The sign-in card is the swappable "auth action" area — when authentication is delegated to
// an external identity provider, the credentials form gives way to a redirect, and nothing
// else on the page moves.
export default function LoginPage() {
  const meta = (name: string) => document.querySelector<HTMLMetaElement>(`meta[name="${name}"]`)?.content;
  const tagline = meta("tagline");
  const introText = meta("introText");
  const signInLabel = meta("signInLabel") ?? "Sign in";
  const signInHeading = meta("signInHeading") ?? "Continue with institutional credentials";

  return (
    <Box sx={{ minBlockSize: "100dvh", display: "flex", flexDirection: "column" }}>
      <PreLoginExtensions />
      <Box
        sx={{
          flex: 1,
          display: "grid",
          // minmax(0, 1fr) rather than plain 1fr, so the tracks may shrink below their
          // items' min-content width — with 1fr, the sign-in card's fixed width would force
          // a horizontal scrollbar on phone-sized screens
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
          { /* A small pointer from the brand panel toward the sign-in action, centered on the
               heading's alignment line. The outer triangle is filled with the seam colour and
               the two inner layers (the page background, then the panel's translucent muted
               tint, reproducing the panel's colour opaquely) are clipped slightly smaller, so
               the pointer wears the seam as its edge instead of interrupting it. */ }
          {SEAM_MARKER === "tab" && (
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
                // The inner fill is the outer triangle's edges offset inward by 1px (for the
                // 45-degree slants that is 1.4px along each axis), leaving a hairline outline
                // parallel to the shape; the base is not offset, since the panel border
                // already draws that line
                "&::before, &::after": {
                  content: '""',
                  position: "absolute",
                  inset: 0,
                  clipPath: "polygon(0 1.4px, 0 calc(100% - 1.4px), calc(100% - 1.4px) 50%)",
                },
                "&::before": {
                  bgcolor: "background.default",
                },
                "&::after": {
                  bgcolor: "background.muted",
                },
              })}
            />
          )}
          { /* The wordmark's terminal node — solid dot in a faint ring — riding the seam at
               the heading's alignment line */ }
          {SEAM_MARKER === "node" && (
            <Box
              aria-hidden="true"
              sx={theme => ({
                display: { xs: "none", md: "flex" },
                alignItems: "center",
                justifyContent: "center",
                position: "absolute",
                insetInlineEnd: -9,
                insetBlockStart: `calc(${theme.spacing(6)} + ${CONTENT_TOP_OFFSET} + 7px)`,
                inlineSize: 18,
                blockSize: 18,
                borderRadius: "50%",
                border: "1.5px solid",
                borderColor: `rgba(${theme.vars?.palette.secondary.mainChannel ?? "192 35 60"} / 0.45)`,
              })}
            >
              <Box sx={{ inlineSize: 10, blockSize: 10, borderRadius: "50%", bgcolor: "secondary.main" }} />
            </Box>
          )}
          { /* The auto start margin anchors the block to the center seam on wide screens,
               keeping the two panels' contents together however wide the page gets; the top
               padding is the shared offset that keeps the logo level with the sign-in card */ }
          <Box
            sx={{
              marginInlineStart: { md: "auto" },
              paddingBlockStart: { xs: 3, md: CONTENT_TOP_OFFSET },
              paddingBlockEnd: { xs: 3, md: 5 },
              maxInlineSize: BRAND_CONTENT_WIDTH,
            }}
          >
            { /* The wordmark is the page heading; its alt text carries the accessible name */ }
            <Typography component="h1" sx={{ margin: 0 }}>
              <Logo sx={{ display: "block", inlineSize: "100%", maxInlineSize: 232 }} />
            </Typography>
            {tagline && (
              <Typography
                variant="overline"
                component="p"
                color="text.secondary"
                sx={{ marginBlockStart: 1, lineHeight: 1.6 }}
              >
                {tagline}
              </Typography>
            )}
            { /* A "lead paragraph" treatment: body text enlarged a step (but keeping its
                 regular weight, so it doesn't read as a heading), with the emphasized parts
                 in the brand primary for contrast against the secondary-coloured prose */ }
            {introText && (
              <FormattedText
                variant="body1"
                color="text.secondary"
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
        { /* On wide screens the card anchors to the center seam, mirroring the brand text */ }
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
          { /* The top margin is the shared offset keeping the card level with the brand text */ }
          <Box sx={{ inlineSize: "100%", maxInlineSize: 380, marginBlockStart: { md: CONTENT_TOP_OFFSET } }}>
            { /* On wide screens the eyebrow hangs above the heading, outside the layout flow:
                 the heading is what optically pairs with the logo across the seam, so it is
                 what sits on the shared alignment line. In the stacked layout there is no
                 offset space to hang into, so it stays a normal line. */ }
            <Box sx={{ position: "relative" }}>
              <Typography
                variant="overline"
                component="p"
                color="text.secondary"
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
            <LoginForm onSuccess={() => window.location.assign(loginRedirectPath())} />
            <ParticipatingInstitutions />
          </Box>
        </Box>
        <Box
          sx={theme => ({
            gridArea: "footer",
            bgcolor: "background.muted",
            // The seam continues along this band's end edge; the top border is the ordinary
            // separator of the stacked layout
            borderInlineEnd: { md: seamBorder(theme) },
            borderBlockStart: { xs: `1px solid ${theme.vars?.palette.divider ?? theme.palette.divider}`, md: 0 },
            px: PANEL_PADDING,
          })}
        >
          { /* Constrained and anchored exactly like the brand text block above, so the two
               share their start edge */ }
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
        { /* Sharing the grid row with the muted band keeps the two footers aligned */ }
        { /* Start-aligned so the credits share their start edge with the sign-in form */ }
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
