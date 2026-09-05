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

import { Alert, Box, CircularProgress, Link as MuiLink, Stack } from "@mui/material";
import { Link as RouterLink } from "react-router";

import MetricTile from "./MetricTile";
import { STATISTICS_ROUTE } from "./statisticsModel";
import { useStatistics } from "./useStatistics";

// The dashboard's short answer: the few headline numbers, and a way through to the charts. A dashboard
// widget has room for figures and not for plots, so it carries no chart at all rather than a cramped one.
//
// Which metrics appear is content, not code: the endpoint returns them in the order the definitions ask
// to be shown in, and this takes the first few. Adding a metric to the front page is then editing a
// node, which is the property the whole module exists to have.

const HEADLINES = 3;

function MetricsWidget() {
  const { metrics, failed } = useStatistics();

  if (failed) {
    return <Alert severity="warning" variant="outlined">The metrics could not be read.</Alert>;
  }
  if (metrics === undefined) {
    return <CircularProgress size={24} sx={{ display: "block", mx: "auto", my: 2 }} />;
  }
  if (metrics.length === 0) {
    return <Alert severity="info" variant="outlined">No metrics are defined yet.</Alert>;
  }

  return (
    <Stack spacing={2}>
      <Box sx={{ display: "grid", gap: 2,
        gridTemplateColumns: { xs: "1fr", sm: `repeat(${Math.min(metrics.length, HEADLINES)}, 1fr)` } }}>
        {metrics.slice(0, HEADLINES).map(metric => (
          <MetricTile key={metric.name} metric={metric} />
        ))}
      </Box>
      <MuiLink component={RouterLink} to={STATISTICS_ROUTE} variant="body2">
        All metrics and trends
      </MuiLink>
    </Stack>
  );
}

export default MetricsWidget;
