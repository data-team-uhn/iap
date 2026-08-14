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

import { useEffect, useMemo, useState, type ComponentType, type ReactNode } from "react";

import ArrowForwardIcon from "@mui/icons-material/ArrowForward";
import { Box, Button } from "@mui/material";
import { Link as RouterLink } from "react-router";

import { loadExtensions, visibleInPersona } from "@iap/ui-extension/extensionManager";
import { usePersona } from "@iap/ui-extension/personas";

import LoadingOverlay from "./LoadingOverlay";
import Widget from "./Widget";

// A widget extension is the parsed JSON of one `iap:Extension` registered on the dashboard's
// extension point, with its `asset:` properties already resolved.
type WidgetExtension = Record<string, unknown>;

// The props that the dashboard passes to each rendered widget.
interface WidgetContentProps {
  extension: WidgetExtension;
}

// How many columns each `iap:widgetWidth` value asks for. The actual span is clamped (in JS) to the
// number of columns available at each breakpoint, so `full` fills the row and a span never exceeds
// the grid — a span larger than the column count would otherwise make CSS Grid spawn extra columns.
const WIDTH_SPAN: Record<string, number> = { normal: 1, wide: 2, full: 3 };

// A React key that identifies a widget rather than its position, since the persona filter changes
// which widgets are displayed while the page is up. Keying by index would hand one widget's key to
// another as the list shrinks, remounting a widget that only moved and throwing away its state.
function widgetKey(widget: WidgetExtension, index: number): string {
  return (widget["jcr:path"] as string | undefined)
    ?? (widget["iap:extensionName"] as string | undefined)
    ?? `widget-${index}`;
}

interface WidgetDashboardProps {
  // The extension point node name whose extensions are laid out as widgets,
  // e.g. "DashboardWidget" for the user dashboard or "AdminDashboard" for the admin console.
  point: string;
  // Optional content rendered in place of the grid when no widgets are available (e.g. because
  // the user cannot read any). When omitted, an empty dashboard just renders nothing.
  empty?: ReactNode;
}

// The shared widget-dashboard layout: widgets contributed through the given extension point, laid
// out in a responsive CSS grid (1/2/3 columns). Every widget is wrapped in a titled Widget frame —
// the title from `iap:extensionName`, an optional subtitle from `iap:subtitle` — and each widget
// can tune its frame through optional properties:
//   - `iap:widgetWidth` (normal/wide/full) — how many columns it spans (e.g. a `full` table
//     stretches across the row);
//   - `iap:widgetEmphasis` — render on a tinted surface;
//   - `iap:widgetBorderless` — drop the border/fill and blend into the page;
//   - `iap:widgetHideHeader` — skip the title/subtitle header (the widget provides its own);
//   - `iap:actionLabel` — render a header action in line with the title: a quiet text button
//     with this label and a forward arrow (navigation, not an inline operation), leading to the
//     widget's `iap:targetURL` (an in-app path); both must be set. Prefer labels naming the
//     destination ("Manage categories") over a generic "Configure" — the widgets show live
//     content, and the label is what tells users there is a whole tool behind the summary.
//   - `iap:personas` — the personas the widget belongs to (absent means all of them), see personas.ts.
function WidgetDashboard({ point, empty }: WidgetDashboardProps) {
  const [ allWidgets, setAllWidgets ] = useState<WidgetExtension[]>([]);
  const [ loading, setLoading ] = useState(true);
  const persona = usePersona();

  useEffect(() => {
    loadExtensions(point)
      .then(extensions => setAllWidgets(extensions))
      .catch((err: unknown) => console.error(`Something went wrong loading the ${point} widgets`, err))
      .finally(() => setLoading(false));
  }, [point]);

  // Only the widgets belonging to the persona currently being worn. Filtered here rather than at
  // load time so that switching persona re-lays out the dashboard without fetching anything again.
  const widgets = useMemo(
    () => allWidgets.filter(widget => visibleInPersona(widget, persona)),
    [ allWidgets, persona ]
  );

  if (!loading && widgets.length === 0) {
    return <>{empty}</>;
  }

  // Collapse the grid to the number of widgets when there are only one or two, so a lone widget
  // fills the row and two widgets sit side by side rather than leaving empty columns. Three or more
  // get the full responsive spread. Spans are clamped to this column count, so an explicit `full`
  // widget still takes the whole row (and, e.g., forces a second widget onto the next row).
  const smColumns = Math.min(widgets.length, 2) || 1;
  const lgColumns = Math.min(widgets.length, 3) || 1;

  return (
    <>
      <LoadingOverlay open={loading} />
      <Box
        sx={{
          display: "grid",
          gap: 2,
          // Cells stretch (the grid default), so widgets sharing a row are the same height; each
          // Widget surface fills its cell (see Widget.tsx).
          gridTemplateColumns: {
            xs: "1fr",
            sm: `repeat(${smColumns}, 1fr)`,
            lg: `repeat(${lgColumns}, 1fr)`,
          },
        }}
      >
        {
          widgets.map((widget, index) => {
            const WidgetContent = widget["iap:extensionRender"] as ComponentType<WidgetContentProps>;
            const span = WIDTH_SPAN[(widget["iap:widgetWidth"] as string | undefined) ?? "normal"] ?? 1;
            const actionLabel = widget["iap:actionLabel"] as string | undefined;
            const targetURL = widget["iap:targetURL"] as string | undefined;
            const action = actionLabel && targetURL
              ? (
                <Button
                  size="small"
                  variant="text"
                  endIcon={<ArrowForwardIcon />}
                  component={RouterLink}
                  to={targetURL}
                >
                  {actionLabel}
                </Button>
              )
              : undefined;
            return (
              <Box
                key={widgetKey(widget, index)}
                sx={{
                  gridColumn: {
                    xs: "span 1",
                    sm: `span ${Math.min(span, smColumns)}`,
                    lg: `span ${Math.min(span, lgColumns)}`,
                  },
                }}
              >
                <Widget
                  title={(widget["iap:extensionName"] as string | undefined) ?? ""}
                  subtitle={widget["iap:subtitle"] ? (widget["iap:subtitle"] as string) : undefined}
                  action={action}
                  emphasis={Boolean(widget["iap:widgetEmphasis"])}
                  borderless={Boolean(widget["iap:widgetBorderless"])}
                  hideHeader={Boolean(widget["iap:widgetHideHeader"])}
                >
                  <WidgetContent extension={widget} />
                </Widget>
              </Box>
            );
          })
        }
      </Box>
    </>
  );
}

export default WidgetDashboard;
