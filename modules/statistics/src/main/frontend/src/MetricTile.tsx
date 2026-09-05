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

import { Stack, Typography } from "@mui/material";

import { formatSample, formatValue, type Metric } from "./statisticsModel";

// One metric as a figure rather than a chart: a single current number is a stat tile, and a one-bar
// bar chart would be a worse way of saying the same thing.
//
// The figure introduces itself. "1.2 issues / request" needs no heading above it, so there is none -
// a label repeating what the number already says is a line of text every reader has to skip. The
// qualifier is set in ordinary weight beside the value, same size and colour, because it is part of
// the same statement and not a footnote to it.
//
// Two metrics cannot do this: "30.4 days" and "56.9%" mean nothing on their own, and no qualifier
// short enough to sit beside them would rescue it. Those keep a heading, and it is set to be read
// rather than skipped.
//
// The sample size stays under every figure. A median over four requests and a median over four
// hundred are different claims, and a reader who cannot see which one they have is being invited to
// over-read the number.

interface MetricTileProps {
  metric: Metric;
  /** The hero figure on a dashboard is bigger than the ones beside it. */
  prominent?: boolean;
}

function MetricTile(props: MetricTileProps) {
  const { metric, prominent } = props;
  const size = prominent === true ? 40 : 28;
  return (
    <Stack spacing={0.5} sx={{ minWidth: 0 }}>
      {metric.prominentLabel && (
        <Typography
          variant="overline"
          sx={{ color: "primary.main", fontWeight: 700, lineHeight: 1.4 }}
        >
          {metric.label}
        </Typography>
      )}
      {/* Each half holds together, so a figure too wide for its card breaks between the number and
          what qualifies it rather than through the middle of either */}
      <Typography component="p" sx={{ fontSize: size, fontWeight: 600, lineHeight: 1.15 }}>
        <Typography component="span" sx={{ fontSize: size, fontWeight: 600, whiteSpace: "nowrap" }}>
          {formatValue(metric.value, metric.unit)}
        </Typography>
        {metric.qualifier !== undefined && (
          <Typography
            component="span"
            sx={{ fontSize: size, fontWeight: 400, whiteSpace: "nowrap" }}
          >
            {` ${metric.qualifier}`}
          </Typography>
        )}
      </Typography>
      <Typography variant="caption" color="text.secondary">
        {metric.value === null ? "Nothing measured yet" : formatSample(metric.sampleSize)}
      </Typography>
    </Stack>
  );
}

export default MetricTile;
