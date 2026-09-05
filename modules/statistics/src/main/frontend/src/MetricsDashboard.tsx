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

import { Alert, Box, Card, CardContent, CircularProgress, Divider, Stack, Typography } from "@mui/material";

import BreakdownChart from "./BreakdownChart";
import MetricTile from "./MetricTile";
import { byCategory, type Metric } from "./statisticsModel";
import TrendChart from "./TrendChart";
import { useStatistics } from "./useStatistics";

// Every metric, with the two views of it that a definition can produce: how it has moved month by
// month, and how it differs across whatever the definition asked it to be split by.
//
// A metric carries its charts only when it has them. A trend needs at least two months and a breakdown
// needs a split to have been asked for, and drawing an empty frame for the ones that have neither
// would make the page look like it was failing rather than like it was answering a simpler question.

/**
 * What a metric's split is called, and the same split with its keys shortened for an axis.
 *
 * Both at once, because the two are not independent: a schema slice is recognised BY its path, and
 * shortening the keys first would leave nothing to recognise it by.
 */
function readable(metric: Metric): { dimension: string; metric: Metric } {
  const bySchema = metric.breakdown.some(slice => slice.key.startsWith("/Schemas/"));
  return {
    dimension: bySchema ? "study type" : "reviewer",
    metric: {
      ...metric,
      breakdown: metric.breakdown.map(slice => ({
        ...slice,
        key: slice.key.startsWith("/") ? slice.key.slice(slice.key.lastIndexOf("/") + 1) : slice.key,
      })),
    },
  };
}

function MetricsDashboard() {
  const { metrics, failed } = useStatistics();

  if (failed) {
    return <Alert severity="warning">The metrics could not be read.</Alert>;
  }
  if (metrics === undefined) {
    return <CircularProgress size={28} sx={{ display: "block", mx: "auto", my: 4 }} />;
  }
  if (metrics.length === 0) {
    return <Alert severity="info">No metrics are defined yet.</Alert>;
  }

  return (
    <Stack spacing={4} sx={{ mt: 2 }}>
      <Typography variant="pageTitle">Metrics</Typography>
      {byCategory(metrics).map(group => (
        <Stack key={group.category} spacing={2}>
          <Typography variant="h6">{group.category}</Typography>
          {/* auto-FILL, not auto-fit: a category with one metric in it should keep a card's width
              rather than stretching one plot across the whole page */}
          <Box sx={{ display: "grid", gap: 3,
            gridTemplateColumns: { xs: "1fr", md: "repeat(auto-fill, minmax(420px, 1fr))" } }}>
            {group.metrics.map(readable).map(({ dimension, metric }) => (
              <Card key={metric.name} variant="outlined">
                <CardContent>
                  <Stack spacing={2}>
                    <MetricTile metric={metric} prominent />
                    {metric.description !== undefined && (
                      <Typography variant="body2" color="text.secondary">{metric.description}</Typography>
                    )}
                    {metric.series.length >= 2 && (
                      <>
                        <Divider />
                        <TrendChart series={metric.series} unit={metric.unit} />
                      </>
                    )}
                    {metric.breakdown.length > 0 && (
                      <>
                        <Divider />
                        <Typography variant="subtitle2" component="h3">
                          By {dimension}
                        </Typography>
                        <BreakdownChart slices={metric.breakdown} unit={metric.unit}
                          dimension={dimension} />
                      </>
                    )}
                  </Stack>
                </CardContent>
              </Card>
            ))}
          </Box>
        </Stack>
      ))}
    </Stack>
  );
}

export default MetricsDashboard;
