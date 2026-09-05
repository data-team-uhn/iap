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

import { Stack, Tooltip, Typography } from "@mui/material";

import { formatSample, formatValue, type Metric } from "./statisticsModel";

// One metric as a figure rather than a chart: a single current number is a stat tile, and a one-bar
// bar chart would be a worse way of saying the same thing.
//
// The sample size is part of the tile, not a detail behind a hover. A median over four requests and a
// median over four hundred are different claims, and a reader who cannot see which one they have is
// being invited to over-read the number.

interface MetricTileProps {
  metric: Metric;
  /** The hero figure on a dashboard is bigger than the ones beside it. */
  prominent?: boolean;
}

function MetricTile(props: MetricTileProps) {
  const { metric, prominent } = props;
  return (
    <Stack spacing={0.5} sx={{ minWidth: 0 }}>
      <Tooltip title={metric.description ?? ""} placement="top-start">
        <Typography variant="body2" color="text.secondary" sx={{ cursor: "default" }}>
          {metric.label}
        </Typography>
      </Tooltip>
      <Typography
        component="p"
        sx={{ fontSize: prominent === true ? 40 : 28, fontWeight: 600, lineHeight: 1.1 }}
      >
        {formatValue(metric.value, metric.unit)}
      </Typography>
      <Typography variant="caption" color="text.secondary">
        {metric.value === null ? "Nothing measured yet" : formatSample(metric.sampleSize)}
      </Typography>
    </Stack>
  );
}

export default MetricTile;
