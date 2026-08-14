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

import { useEffect, useMemo, useState, type ComponentType } from "react";

import { Box } from "@mui/material";

import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import { loadExtensions, visibleInPersona } from "@iap/ui-extension/extensionManager";
import { usePersona } from "@iap/ui-extension/personas";

import Widget from "./Widget";

// A dashboard widget extension is the parsed JSON of one `iap:Extension` registered on the
// `iap/dashboard/widget` extension point, with its `asset:` properties already resolved.
type WidgetExtension = Record<string, unknown>;

// The props that the dashboard passes to each rendered widget.
interface WidgetProps {
  extension: WidgetExtension;
}

// How many columns each `iap:widgetWidth` value asks for. The actual span is clamped (in JS) to the
// number of columns available at each breakpoint, so `full` fills the row and a span never exceeds
// the grid — a span larger than the column count would otherwise make CSS Grid spawn extra columns.
const WIDTH_SPAN: Record<string, number> = { normal: 1, wide: 2, full: 3 };

// Retrieves all the widgets registered on the dashboard extension point, in display order.
async function getDashboardWidgets(): Promise<WidgetExtension[]> {
  return loadExtensions("DashboardWidget");
}

// A React key that identifies a widget rather than its position, since the persona filter changes
// which widgets are displayed while the page is up. Keying by index would hand one widget's key to
// another as the list shrinks, remounting a widget that only moved and throwing away its state.
function widgetKey(widget: WidgetExtension, index: number): string {
  return (widget["jcr:path"] as string | undefined)
    ?? (widget["iap:extensionName"] as string | undefined)
    ?? `widget-${index}`;
}

// The dashboard view: widgets contributed by other modules through the `iap/dashboard/widget`
// extension point, laid out in a responsive CSS grid (1/2/3 columns). The dashboard wraps every
// widget in a titled Widget frame — the title from `iap:extensionName`, an optional subtitle from
// `iap:subtitle` — and each widget can tune its frame through optional properties:
//   - `iap:widgetWidth` (normal/wide/full) — how many columns it spans (e.g. a `full` table
//     stretches across the row);
//   - `iap:widgetEmphasis` — render on a tinted surface;
//   - `iap:widgetBorderless` — drop the border/fill and blend into the page;
//   - `iap:widgetHideHeader` — skip the title/subtitle header (the widget provides its own);
//   - `iap:personas` — the personas the widget belongs to (absent means all of them), see personas.ts.
// Registered as a view on the `iap/coreUI/view` extension point.
function Dashboard() {
  const [ allWidgets, setAllWidgets ] = useState<WidgetExtension[]>([]);
  const [ loading, setLoading ] = useState(true);
  const persona = usePersona();

  useEffect(() => {
    getDashboardWidgets()
      .then(extensions => setAllWidgets(extensions))
      .catch((err: unknown) => console.error("Something went wrong loading the dashboard", err))
      .finally(() => setLoading(false));
  }, []);

  // Only the widgets belonging to the persona currently being worn. Filtered here rather than at
  // load time so that switching persona re-lays out the dashboard without fetching anything again.
  const widgets = useMemo(
    () => allWidgets.filter(widget => visibleInPersona(widget, persona)),
    [ allWidgets, persona ]
  );

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
            const WidgetContent = widget["iap:extensionRender"] as ComponentType<WidgetProps>;
            const span = WIDTH_SPAN[(widget["iap:widgetWidth"] as string | undefined) ?? "normal"] ?? 1;
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

export default Dashboard;
