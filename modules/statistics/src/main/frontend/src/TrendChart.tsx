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

import { formatMonth, formatSample, formatValue, type Slice } from "./statisticsModel";

// A metric month by month. One series, so no legend: the heading above already says what is plotted,
// and a legend box with a single swatch would only repeat it.
//
// Each month holds the requests that STARTED in it, which is what makes a month a cohort rather than a
// running total - and what makes the most recent months provisional, since some of what began in them
// has not finished. The caption says so rather than leaving a reader to infer it from a dipping tail.

const WIDTH = 560;

const HEIGHT = 180;

const PADDING = { top: 16, right: 16, bottom: 28, left: 44 };

const MARKER_RADIUS = 4;

interface TrendChartProps {
  series: Slice[];
  unit?: string;
}

/** Clean round numbers for the value axis, so the ticks carry the values the marks do not label. */
function ticksFor(largest: number): number[] {
  if (largest <= 0) {
    return [0];
  }
  const rough = largest / 3;
  const magnitude = Math.pow(10, Math.floor(Math.log10(rough)));
  const step = [1, 2, 5, 10].map(multiple => multiple * magnitude)
    .find(candidate => candidate >= rough) ?? magnitude * 10;
  const ticks: number[] = [];
  for (let value = 0; value <= largest + step / 2; value += step) {
    ticks.push(value);
  }
  return ticks;
}

function TrendChart(props: TrendChartProps) {
  const { series, unit } = props;
  const [ hovered, setHovered ] = useState<number>();

  const measured = series.filter(slice => slice.value !== null);
  if (measured.length < 2) {
    return (
      <Typography variant="body2" color="text.secondary">
        Not enough months yet to show a trend.
      </Typography>
    );
  }

  const largest = Math.max(...measured.map(slice => slice.value ?? 0));
  const ticks = ticksFor(largest);
  const top = ticks[ticks.length - 1];
  const plotWidth = WIDTH - PADDING.left - PADDING.right;
  const plotHeight = HEIGHT - PADDING.top - PADDING.bottom;
  const x = (index: number) =>
    PADDING.left + (measured.length === 1 ? plotWidth / 2 : (index / (measured.length - 1)) * plotWidth);
  const y = (value: number) => PADDING.top + plotHeight - (top === 0 ? 0 : (value / top) * plotHeight);

  const line = measured
    .map((slice, index) => `${index === 0 ? "M" : "L"} ${x(index)} ${y(slice.value ?? 0)}`)
    .join(" ");
  const last = measured.length - 1;

  return (
    <Box sx={{ position: "relative" }}>
      <svg
        width="100%"
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        role="img"
        aria-label="Month by month, oldest first. The figures are in the table below."
        style={{ maxWidth: "100%", display: "block" }}
        onMouseLeave={() => setHovered(undefined)}
      >
        {ticks.map(tick => (
          <g key={tick}>
            <line
              x1={PADDING.left} x2={WIDTH - PADDING.right} y1={y(tick)} y2={y(tick)}
              stroke="var(--mui-palette-divider)" strokeWidth={1}
            />
            <text
              x={PADDING.left - 8} y={y(tick)} textAnchor="end" dominantBaseline="central"
              fill="var(--mui-palette-text-secondary)" fontSize={11}
            >
              {tick.toLocaleString()}
            </text>
          </g>
        ))}
        <path d={line} fill="none" stroke="var(--mui-palette-primary-main)" strokeWidth={2}
          strokeLinejoin="round" strokeLinecap="round" />
        {hovered !== undefined && (
          <line
            x1={x(hovered)} x2={x(hovered)} y1={PADDING.top} y2={PADDING.top + plotHeight}
            stroke="var(--mui-palette-divider)" strokeWidth={1}
          />
        )}
        {measured.map((slice, index) => (
          <g key={slice.key}>
            {/* A column per month as the hit target: an 8px dot is too small to aim at */}
            <rect
              x={x(index) - plotWidth / (2 * Math.max(1, measured.length - 1))}
              y={PADDING.top}
              width={plotWidth / Math.max(1, measured.length - 1)}
              height={plotHeight}
              fill="transparent"
              onMouseEnter={() => setHovered(index)}
            />
            {(index === last || index === hovered) && (
              <circle
                cx={x(index)} cy={y(slice.value ?? 0)} r={MARKER_RADIUS}
                fill="var(--mui-palette-primary-main)"
                stroke="var(--mui-palette-background-paper)" strokeWidth={2}
              />
            )}
          </g>
        ))}
        {/* The end of the line is the one point worth labelling; the rest are in the tooltip */}
        <text
          x={x(last)} y={y(measured[last].value ?? 0) - 12} textAnchor="end"
          fill="var(--mui-palette-text-primary)" fontSize={12}
        >
          {formatValue(measured[last].value, unit)}
        </text>
        {measured.map((slice, index) => (
          index === 0 || index === last ? (
            <text
              key={slice.key} x={x(index)} y={HEIGHT - 8}
              textAnchor={index === 0 ? "start" : "end"}
              fill="var(--mui-palette-text-secondary)" fontSize={11}
            >
              {formatMonth(slice.key)}
            </text>
          ) : null
        ))}
      </svg>
      {hovered !== undefined && (
        <Paper elevation={3}
          sx={{ position: "absolute", top: 0, right: 0, px: 1.5, py: 1, pointerEvents: "none" }}>
          <Typography variant="body2">{formatMonth(measured[hovered].key)}</Typography>
          <Typography variant="body2" color="text.secondary">
            {formatValue(measured[hovered].value, unit)} over {formatSample(measured[hovered].sampleSize)}
          </Typography>
        </Paper>
      )}
      <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 0.5 }}>
        By the month each request started in, so the most recent months are still filling up.
      </Typography>
      <Box component="table" sx={{ position: "absolute", width: 1, height: 1, overflow: "hidden",
        clip: "rect(0 0 0 0)", whiteSpace: "nowrap" }}>
        <caption>Month by month</caption>
        <tbody>
          {measured.map(slice => (
            <tr key={slice.key}>
              <th scope="row">{formatMonth(slice.key)}</th>
              <td>{formatValue(slice.value, unit)}</td>
              <td>{formatSample(slice.sampleSize)}</td>
            </tr>
          ))}
        </tbody>
      </Box>
    </Box>
  );
}

export default TrendChart;
