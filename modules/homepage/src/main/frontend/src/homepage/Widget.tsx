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

import { Box, Paper, Typography } from "@mui/material";
import { alpha, styled } from "@mui/material/styles";

// The surface every dashboard widget sits on. Styling lives here (driven by theme tokens) rather
// than in the dashboard layout, so the widget frame can grow more elaborate without cluttering the
// tiling logic, and so all widgets stay visually consistent. An emphasised widget gets a primary-
// tinted surface (relative to the palette, so it reads correctly in both light and dark schemes).
const WidgetSurface = styled(Paper, {
  shouldForwardProp: prop => prop !== "emphasis",
})<{ emphasis?: boolean }>(({ theme, emphasis }) => ({
  padding: theme.spacing(2),
  // Fill the grid cell so widgets sharing a row are the same height (the grid stretches the cells;
  // this makes the surface stretch to match).
  height: "100%",
  ...(emphasis && {
    backgroundColor: alpha(theme.palette.primary.main, 0.08),
    borderColor: alpha(theme.palette.primary.main, 0.4),
  }),
}));

type WidgetProps = {
  // Optional heading rendered at the top of the widget. When omitted, the widget's own content is
  // responsible for any heading.
  title?: string;
  // Optional secondary line rendered under the title (e.g. a short description of the content).
  subtitle?: string;
  // When true, the widget is rendered on a tinted surface to draw attention to it.
  emphasis?: boolean;
  // The widget's content.
  children: ReactNode;
};

// The frame wrapping one dashboard widget's content: a styled surface with an optional title and
// subtitle. Title and subtitle form one header block, kept tight together and separated from the
// content below.
function Widget({ title, subtitle, emphasis, children }: WidgetProps) {
  return (
    <WidgetSurface emphasis={emphasis}>
      { (title || subtitle) && (
        <Box sx={{ mb: 2 }}>
          { title && <Typography variant="h6">{title}</Typography> }
          { subtitle && <Typography variant="body2" color="text.secondary">{subtitle}</Typography> }
        </Box>
      ) }
      {children}
    </WidgetSurface>
  );
}

export default Widget;
