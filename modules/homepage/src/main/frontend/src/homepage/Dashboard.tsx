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

import { Masonry } from "@mui/lab";

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

// Retrieves all the widgets registered on the dashboard extension point, in display order.
async function getDashboardWidgets(): Promise<WidgetExtension[]> {
  return loadExtensions("DashboardWidget")
    .then(extensions => extensions.slice()
      .sort((a, b) => Number(a["iap:defaultOrder"]) - Number(b["iap:defaultOrder"]))
    );
}

// The dashboard view: a responsive masonry of widgets contributed by other modules through the
// `iap/dashboard/widget` extension point. Widgets flow into responsive columns, each placed in the
// shortest column, so tiles of different heights pack tightly instead of leaving the ragged gaps a
// fixed row-based grid would. The dashboard wraps every widget in a titled Widget frame — the title
// from `iap:extensionName`, an optional subtitle from `iap:hint`. Registered as a view on the
// `iap/coreUI/view` extension point.
function Dashboard() {
  const [ widgets, setWidgets ] = useState<WidgetExtension[]>([]);
  const [ loading, setLoading ] = useState(true);

  useEffect(() => {
    getDashboardWidgets()
      .then(extensions => setWidgets(extensions))
      .catch(err => console.error("Something went wrong loading the dashboard", err))
      .finally(() => setLoading(false));
  }, []);

  // Cap the column count at the number of widgets so the layout adapts to how much there is to
  // show: a lone widget spans the full width instead of sitting in a narrow column, two widgets
  // fill at most two columns, and three or more get the full responsive spread.
  const columns = {
    xs: 1,
    sm: Math.min(widgets.length, 2) || 1,
    lg: Math.min(widgets.length, 3) || 1,
  };

  return (
    <>
      <LoadingOverlay open={loading} />
      <Masonry columns={columns} spacing={2}>
        {
          widgets.map((widget, index) => {
            const WidgetContent = widget["iap:extensionRender"] as ComponentType<WidgetProps>;
            return (
              <Widget
                key={"widget-" + index}
                title={String(widget["iap:extensionName"] ?? "")}
                subtitle={widget["iap:hint"] ? String(widget["iap:hint"]) : undefined}
              >
                <WidgetContent extension={widget} />
              </Widget>
            );
          })
        }
      </Masonry>
    </>
  );
}

export default Dashboard;
