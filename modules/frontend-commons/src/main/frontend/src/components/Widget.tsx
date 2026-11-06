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

import { type ReactNode } from "react";

import {
  Box,
  Link as MuiLink,
  Paper,
  Stack,
  Typography,
} from "@mui/material";
import { styled } from "@mui/material/styles";
import { Link as RouterLink } from "react-router";

// The surface a dashboard widget sits on. An emphasised widget sits on the theme's tinted surface,
// and a borderless one has no fill.
const WidgetSurface = styled(Paper, {
  shouldForwardProp: prop => prop !== "emphasis" && prop !== "borderless",
})<{ emphasis?: boolean; borderless?: boolean }>(({ theme, emphasis, borderless }) => ({
  padding: theme.spacing(2),
  // Fills its grid cell, so widgets sharing a row are the same height
  height: "100%",
  // The fill sets a widget apart from the page, so it needs no border
  border: "none",
  // Content wider than the widget, such as a table on a phone, scrolls sideways inside it rather than
  // widening the dashboard
  overflowX: "auto",
  ...(emphasis && { backgroundColor: (theme.vars ?? theme).palette.background.tinted }),
  ...(borderless && { backgroundColor: "transparent" }),
}));

interface WidgetProps {
  // The heading at the top of the widget, which also names the widget's region. Without it, the
  // content draws any heading.
  title?: string;
  // The in-app path the title links to; the title is plain text without it.
  titleHref?: string;
  // A line under the title, such as a short description of the content.
  subtitle?: string;
  // Shown at the end of the header row, such as a button.
  action?: ReactNode;
  // Puts the widget on a tinted surface.
  emphasis?: boolean;
  // Drops the surface's fill, so the widget sits on the page.
  borderless?: boolean;
  // Leaves out the title, subtitle and action, for content that draws its own header.
  hideHeader?: boolean;
  children: ReactNode;
}

// The frame around one dashboard widget's content: a surface, with a header of a title, a subtitle
// and an action, each optional.
function Widget({ title, titleHref, subtitle, action, emphasis, borderless, hideHeader, children }: WidgetProps) {
  // A titled widget is a region named by its title, even when its header is hidden
  const named = Boolean(title);
  return (
    <WidgetSurface
      emphasis={emphasis}
      borderless={borderless}
      role={named ? "region" : undefined}
      aria-label={named ? title : undefined}
    >
      {/* An empty title or subtitle counts as absent, hence `||` rather than `??` */}
      {/* eslint-disable-next-line @typescript-eslint/prefer-nullish-coalescing */}
      { !hideHeader && (title || subtitle || action) && (
        <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "flex-start", gap: 1, mb: 2 }}>
          <Box>
            { title && (
              <Typography variant="h6">
                { titleHref
                  ? <MuiLink component={RouterLink} to={titleHref} color="inherit" underline="hover">{title}</MuiLink>
                  : title }
              </Typography>
            ) }
            { subtitle && <Typography variant="caption">{subtitle}</Typography> }
          </Box>
          {action}
        </Stack>
      ) }
      {children}
    </WidgetSurface>
  );
}

export default Widget;
