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

import { useEffect, useState, type ComponentType } from "react";

import { Box } from "@mui/material";

import Widget from "./Widget";
import LoadingOverlay from "../components/LoadingOverlay";
import { loadExtensions } from "../uiextension/extensionManager";

// A dashboard widget extension is the parsed JSON of one `iap:Extension` registered on the
// `iap/dashboard/widget` extension point, with its `asset:` properties already resolved.
type WidgetExtension = Record<string, unknown>;

// The props that the dashboard passes to each rendered widget.
type WidgetProps = {
  extension: WidgetExtension;
};

// How many columns each `iap:widgetWidth` value asks for. The actual span is clamped (in JS) to the
// number of columns available at each breakpoint, so `full` fills the row and a span never exceeds
// the grid — a span larger than the column count would otherwise make CSS Grid spawn extra columns.
const WIDTH_SPAN: Record<string, number> = { normal: 1, wide: 2, full: 3 };

// Retrieves all the widgets registered on the dashboard extension point, in display order.
async function getDashboardWidgets(): Promise<WidgetExtension[]> {
  return loadExtensions("DashboardWidget")
    .then(extensions => extensions.slice()
      .sort((a, b) => Number(a["iap:defaultOrder"]) - Number(b["iap:defaultOrder"]))
    );
}

// The dashboard view: widgets contributed by other modules through the `iap/dashboard/widget`
// extension point, laid out in a responsive CSS grid (1/2/3 columns). The dashboard wraps every
// widget in a titled Widget frame — the title from `iap:extensionName`, an optional subtitle from
// `iap:subtitle` — and each widget can tune its frame through optional properties:
//   - `iap:widgetWidth` (normal/wide/full) — how many columns it spans (e.g. a `full` table
//     stretches across the row);
//   - `iap:widgetEmphasis` — render on a tinted surface;
//   - `iap:widgetBorderless` — drop the border/fill and blend into the page;
//   - `iap:widgetHideHeader` — skip the title/subtitle header (the widget provides its own).
// Registered as a view on the `iap/coreUI/view` extension point.
function Dashboard() {
  const [ widgets, setWidgets ] = useState<WidgetExtension[]>([]);
  const [ loading, setLoading ] = useState(true);

  useEffect(() => {
    getDashboardWidgets()
      .then(extensions => setWidgets(extensions))
      .catch(err => console.error("Something went wrong loading the dashboard", err))
      .finally(() => setLoading(false));
  }, []);

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
            const span = WIDTH_SPAN[String(widget["iap:widgetWidth"] ?? "normal")] ?? 1;
            return (
              <Box
                key={"widget-" + index}
                sx={{
                  gridColumn: {
                    xs: "span 1",
                    sm: `span ${Math.min(span, smColumns)}`,
                    lg: `span ${Math.min(span, lgColumns)}`,
                  },
                }}
              >
                <Widget
                  title={String(widget["iap:extensionName"] ?? "")}
                  subtitle={widget["iap:subtitle"] ? String(widget["iap:subtitle"]) : undefined}
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
