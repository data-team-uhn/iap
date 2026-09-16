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

import { Fragment } from "react";

import { Box, Chip, Link as MuiLink, Typography } from "@mui/material";
import { Link as RouterLink } from "react-router";

// What every stat carries, whichever way its value is displayed.
interface CommonStat {
  // What the value is a figure for, e.g. "Archived in total". Doubles as the React key.
  label: string;
  // An in-app path the label leads to, for a figure that has somewhere more detailed behind it.
  // Plain text when absent.
  href?: string;
}

// A counted figure: how many of something there are.
interface CountStat extends CommonStat {
  mode?: "count";
  // Absent when the count could not be read: different than a count of 0.
  value?: number;
  // If the count is a lower bound rather than a complete count
  approximate?: boolean;
  // Colours a non-zero count as a problem
  emphasis?: boolean;
  // What to say about a count that could not be read, when the generic wording is too vague to be
  // of help, e.g. "The workflows here could not be counted".
  unknownTitle?: string;
}

// A state rather than a figure: something is on, or it is off.
interface BooleanStat extends CommonStat {
  mode: "boolean";
  value: boolean;
  // What the two states are called, when "On"/"Off" is not how this particular thing is spoken of.
  trueLabel?: string;
  falseLabel?: string;
}

export type WidgetStat = CountStat | BooleanStat;

interface WidgetStatListProps {
  stats: WidgetStat[];
}

// How a count reads: the number, marked with a "+" for a lower bound, or a "?" if unknown.
function countLabel(stat: CountStat): string {
  if (stat.value == undefined) {
    return "?";
  }
  return stat.approximate === true ? `${String(stat.value)}+` : String(stat.value);
}

function StatValue({ stat }: { stat: WidgetStat }) {
  if (stat.mode === "boolean") {
    return (
      <Chip
        size="small"
        label={stat.value ? stat.trueLabel ?? "On" : stat.falseLabel ?? "Off"}
        color={stat.value ? "success" : "default"}
      />
    );
  }
  return (
    <Typography
      variant="h6"
      component="span"
      title={stat.value == undefined ? stat.unknownTitle ?? "This could not be counted" : undefined}
      sx={{
        color: stat.emphasis === true && stat.value !== undefined && stat.value > 0
          ? "error.main"
          : "text.primary",
        // Digits of equal width, so the figures line up with each other down the column and a number
        // does not jump sideways when it grows by a digit
        fontVariantNumeric: "tabular-nums",
      }}
    >
      {countLabel(stat)}
    </Typography>
  );
}

function StatLabel({ stat }: { stat: WidgetStat }) {
  if (stat.href != undefined) {
    return (
      <MuiLink component={RouterLink} to={stat.href} variant="body2" underline="hover">
        {stat.label}
      </MuiLink>
    );
  }
  return <Typography variant="body2" sx={{ color: "text.secondary" }}>{stat.label}</Typography>;
}

// The one way a dashboard widget lists what it found: a value, then what the value is a figure for.
//
// A single grid rather than a row of independent stacks to ensure values are lined up based on the longest entry.
//
// Sample usage:
// <WidgetStatList stats={[
//   { label: "Needing attention", value: counts.needingAttention, emphasis: true, href: "/admin/errors" },
//   { label: "Recorded in total", value: counts.total },
// ]} />
//
function WidgetStatList({ stats }: WidgetStatListProps) {
  return (
    <Box
      sx={{
        display: "grid",
        gridTemplateColumns: "auto 1fr",
        columnGap: 1,
        rowGap: 0.5,
        alignItems: "baseline",
      }}
    >
      {
        stats.map(stat => (
          <Fragment key={stat.label}>
            <Box sx={{ justifySelf: "end" }}><StatValue stat={stat} /></Box>
            <StatLabel stat={stat} />
          </Fragment>
        ))
      }
    </Box>
  );
}

export default WidgetStatList;
