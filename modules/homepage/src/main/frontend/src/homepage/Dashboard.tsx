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

import { CircularProgress, Grid, Paper } from "@mui/material";

import { loadExtensions } from "../uiextension/extensionManager";

// A dashboard widget is the parsed JSON of one `iap:Extension` registered on the
// `iap/dashboard/widget` extension point, with its `asset:` properties already resolved.
type Widget = Record<string, unknown>;

// The props that the dashboard passes to each rendered widget.
type WidgetProps = {
  extension: Widget;
};

// Retrieves all the widgets registered on the dashboard extension point, in display order.
async function getDashboardWidgets(): Promise<Widget[]> {
  return loadExtensions("DashboardWidget")
    .then(extensions => extensions.slice()
      .sort((a, b) => Number(a["iap:defaultOrder"]) - Number(b["iap:defaultOrder"]))
    );
}

// The dashboard view: a flexible 1-2 column grid of widgets contributed by other modules
// through the `iap/dashboard/widget` extension point. Each widget's rendered content is
// wrapped in a Paper element. Registered as a view on the `iap/coreUI/view` extension point.
function Dashboard() {
  const [ widgets, setWidgets ] = useState<Widget[]>([]);
  const [ loading, setLoading ] = useState(true);

  useEffect(() => {
    getDashboardWidgets()
      .then(extensions => setWidgets(extensions))
      .catch(err => console.error("Something went wrong loading the dashboard", err))
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return (
      <Grid container justifyContent="center"><Grid><CircularProgress/></Grid></Grid>
    );
  }

  return (
    <Grid container spacing={4}>
      {
        widgets.map((widget, index) => {
          const WidgetContent = widget["iap:extensionRender"] as ComponentType<WidgetProps>;
          return (
            <Grid size={{ xs: 12, md: widgets.length > 1 ? 6 : 12 }} key={"widget-" + index}>
              <Paper sx={{ p: 2 }}>
                <WidgetContent extension={widget} />
              </Paper>
            </Grid>
          );
        })
      }
    </Grid>
  );
}

export default Dashboard;
