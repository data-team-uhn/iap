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

import { useState } from "react";

import { Box, Paper, Typography } from "@mui/material";

import { formatSample, formatValue, type Slice } from "./statisticsModel";

// A metric split by reviewer or by study type, as horizontal bars. Horizontal because the categories
// are people's names and study titles, which do not fit under a vertical column without turning the
// axis sideways.
//
// One measure, so one colour: the brand primary, taken through its CSS variable so that the dark
// scheme's lightened primary applies itself. A palette of hues here would be decorative - the bars are
// not distinct series, they are one series read against a common scale - and a categorical palette
// would also have to survive colour-vision checks it has no reason to be taking.

const BAR_THICKNESS = 20;

const BAND = 34;

const LABEL_WIDTH = 148;

const VALUE_WIDTH = 92;

const RADIUS = 4;

interface BreakdownChartProps {
  slices: Slice[];
  unit?: string;
  /** What the bars are, for the assistive description: "reviewer", "study type". */
  dimension: string;
}

/** A rounded data-end, square at the baseline: the bar grows from the axis and is capped at its tip. */
function barPath(x: number, y: number, width: number, height: number): string {
  const radius = Math.min(RADIUS, width);
  return `M ${x} ${y}`
    + ` H ${x + width - radius}`
    + ` A ${radius} ${radius} 0 0 1 ${x + width} ${y + radius}`
    + ` V ${y + height - radius}`
    + ` A ${radius} ${radius} 0 0 1 ${x + width - radius} ${y + height}`
    + ` H ${x} Z`;
}

function BreakdownChart(props: BreakdownChartProps) {
  const { slices, unit, dimension } = props;
  const [ hovered, setHovered ] = useState<number>();

  const measured = slices.filter(slice => slice.value !== null);
  if (measured.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary">
        Nothing measurable in any {dimension} yet.
      </Typography>
    );
  }
  // Magnitude is the job, so the longest bar leads; the sample size rides in the tooltip instead of
  // deciding the order
  const ordered = [...measured].sort((one, other) => (other.value ?? 0) - (one.value ?? 0));
  const largest = Math.max(...ordered.map(slice => slice.value ?? 0), 0);
  const plotWidth = 320;
  const height = ordered.length * BAND;

  return (
    <Box sx={{ position: "relative" }}>
      <svg
        width="100%"
        viewBox={`0 0 ${LABEL_WIDTH + plotWidth + VALUE_WIDTH} ${height}`}
        role="img"
        aria-label={`By ${dimension}, longest first. The figures are in the table below.`}
        style={{ maxWidth: "100%", display: "block" }}
      >
        {ordered.map((slice, index) => {
          const y = index * BAND;
          const width = largest === 0 ? 0 : ((slice.value ?? 0) / largest) * plotWidth;
          return (
            <g
              key={slice.key}
              onMouseEnter={() => setHovered(index)}
              onMouseLeave={() => setHovered(undefined)}
            >
              {/* The hit target is the whole band, not the bar: a short bar is otherwise
                  almost impossible to hover */}
              <rect
                x={0}
                y={y}
                width={LABEL_WIDTH + plotWidth + VALUE_WIDTH}
                height={BAND}
                fill={hovered === index ? "var(--mui-palette-action-hover)" : "transparent"}
              />
              <text
                x={LABEL_WIDTH - 10}
                y={y + BAND / 2}
                textAnchor="end"
                dominantBaseline="central"
                fill="var(--mui-palette-text-secondary)"
                fontSize={13}
              >
                {slice.key}
              </text>
              <path
                d={barPath(LABEL_WIDTH, y + (BAND - BAR_THICKNESS) / 2, Math.max(width, 1),
                  BAR_THICKNESS)}
                fill="var(--mui-palette-primary-main)"
              />
              <text
                x={LABEL_WIDTH + width + 10}
                y={y + BAND / 2}
                dominantBaseline="central"
                fill="var(--mui-palette-text-primary)"
                fontSize={13}
              >
                {formatValue(slice.value, unit)}
              </text>
            </g>
          );
        })}
      </svg>
      {hovered !== undefined && (
        <Paper
          elevation={3}
          sx={{ position: "absolute", top: 0, right: 0, px: 1.5, py: 1, pointerEvents: "none" }}
        >
          <Typography variant="body2">{ordered[hovered].key}</Typography>
          <Typography variant="body2" color="text.secondary">
            {formatValue(ordered[hovered].value, unit)} over {formatSample(ordered[hovered].sampleSize)}
          </Typography>
        </Paper>
      )}
      {/* The same numbers as text, so the chart is never the only way to read them */}
      <Box component="table" sx={{ position: "absolute", width: 1, height: 1, overflow: "hidden",
        clip: "rect(0 0 0 0)", whiteSpace: "nowrap" }}>
        <caption>By {dimension}</caption>
        <tbody>
          {ordered.map(slice => (
            <tr key={slice.key}>
              <th scope="row">{slice.key}</th>
              <td>{formatValue(slice.value, unit)}</td>
              <td>{formatSample(slice.sampleSize)}</td>
            </tr>
          ))}
        </tbody>
      </Box>
    </Box>
  );
}

export default BreakdownChart;
