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

import { Fragment, useEffect, useState } from "react";

import { Box, Chip, Link as MuiLink, Skeleton, Typography } from "@mui/material";
import { Link as RouterLink } from "react-router";

import { useAuthenticatedFetch } from "../reLogin";

// One figure, as a summary reports it: a count of something, or a state it is in.
interface WidgetStat {
  // The key the summary listed it under. Unique within one summary, and the row's identity.
  id: string;
  // What the value is a figure for, e.g. "Archived in total".
  label: string;
  // A count, a state that is on or off, or null for a count that could not be taken.
  value: number | boolean | null;
  // If the count is a lower bound rather than a complete count
  approximate?: boolean;
  // Which value is worth acting on, and so coloured as a problem. Left out when neither is: a widget
  // that is permanently red stops being read
  important?: "nonzero" | "zero";
  // The repository path this figure is about, for a label that leads somewhere. Plain text without one,
  // or without a hrefFor to turn it into a route.
  path?: string;
}

interface WidgetStatListProps {
  // The resource whose summary to list, e.g. "/Workflows". The summary itself is at `<url>.adminSummary.json`.
  url: string;
  // What this widget is about, in words, e.g. "recorded errors": what the waiting and refusal messages say.
  name: string;
  // Turns a figure's repository path into the route its label links to.
  hrefFor?: (path: string) => string;
}

// How a count reads: the number, marked with a "+" when the server only counted so far, or a "?" if unknown.
function countLabel(stat: WidgetStat): string {
  if (stat.value === null) {
    return "?";
  }
  return stat.approximate === true ? `${String(stat.value)}+` : String(stat.value);
}

// Whether a count is in a state its summary wanted noticed.
function isEmphasised(stat: WidgetStat): boolean {
  if (typeof stat.value !== "number") {
    return false;
  }
  return stat.important === "nonzero" ? stat.value > 0 : stat.important === "zero" && stat.value === 0;
}

// The figures a summary listed, in the order it listed them. Anything that is not a figure is skipped.
function readSummary(body: unknown): WidgetStat[] {
  if (typeof body !== "object" || body === null) {
    return [];
  }
  return Object.entries(body as Record<string, unknown>)
    .map(([ id, entry ]) => readStat(id, entry))
    .filter((stat): stat is WidgetStat => stat !== null);
}

function readStat(id: string, entry: unknown): WidgetStat | null {
  if (typeof entry !== "object" || entry === null) {
    return null;
  }
  const figure = entry as Record<string, unknown>;
  if (typeof figure.label !== "string"
    || (typeof figure.value !== "number" && typeof figure.value !== "boolean" && figure.value !== null)) {
    return null;
  }
  return {
    id,
    label: figure.label,
    value: figure.value,
    approximate: figure.approximate === true,
    important: figure.important === "nonzero" || figure.important === "zero" ? figure.important : undefined,
    path: typeof figure.path === "string" ? figure.path : undefined,
  };
}

// Where one stat's two cells sit: the value in the left column, its label to the right of it, both
// on the stat's own row. Placed explicitly because the label is written first: grid will not put a
// later item back in a column it has already moved past.
const valueCell = (row: number) => ({ gridColumn: 1, gridRow: row, justifySelf: "end", m: 0 });
const labelCell = (row: number) => ({ gridColumn: 2, gridRow: row });

function StatValue({ stat, row }: { stat: WidgetStat; row: number }) {
  if (typeof stat.value === "boolean") {
    return (
      <Box component="dd" sx={valueCell(row)}>
        <Chip
          size="small"
          label={stat.value ? "On" : "Off"}
          color={stat.value ? "success" : "default"}
        />
      </Box>
    );
  }
  return (
    <Typography
      variant="h6"
      component="dd"
      title={stat.value === null ? "Count unknown" : undefined}
      sx={{
        ...valueCell(row),
        color: isEmphasised(stat) ? "error.main" : "text.primary",
        // Digits of equal width, so the figures line up with each other down the column and a number
        // does not jump sideways when it grows by a digit
        fontVariantNumeric: "tabular-nums",
      }}
    >
      {countLabel(stat)}
    </Typography>
  );
}

function StatLabel({ stat, row, href }: { stat: WidgetStat; row: number; href?: string }) {
  if (href != undefined) {
    return (
      <Box component="dt" sx={labelCell(row)}>
        <MuiLink component={RouterLink} to={href} variant="body2" underline="hover">
          {stat.label}
        </MuiLink>
      </Box>
    );
  }
  return (
    <Typography variant="body2" component="dt" sx={{ ...labelCell(row) }}>
      {stat.label}
    </Typography>
  );
}

// The one way a dashboard widget lists what it found: a value, then what the value is a figure for.
// The widget names the resource to summarize and what to call it. This list fetches the summary,
// shows a placeholder while it loads, and says so when it is refused.
//
// A description list, because that is what these are: each label is a term and its value the
// description of it. The label is written first and displayed second, so a screen reader says what
// a figure counts before the figure. The value is then found from its label rather than from where
// it sits.
//
// Sample usage:
// <WidgetStatList url="/Workflows" name="workflows" hrefFor={adminUrl} />
//
function WidgetStatList({ url, name, hrefFor }: WidgetStatListProps) {
  const doFetch = useAuthenticatedFetch();
  // The summary, and the url it was read for. A summary read for a previous url means this one is still loading.
  const [ read, setRead ] = useState<{ url: string; stats: WidgetStat[] | null } | null>(null);

  useEffect(() => {
    let cancelled = false;
    doFetch(`${url}.adminSummary.json`, { headers: { Accept: "application/json" } })
      .then(response => {
        if (!response.ok) {
          throw new Error(String(response.status));
        }
        return response.json() as Promise<unknown>;
      })
      .then(body => { if (!cancelled) { setRead({ url, stats: readSummary(body) }); } })
      .catch(() => { if (!cancelled) { setRead({ url, stats: null }); } });
    return () => { cancelled = true; };
  }, [ doFetch, url ]);

  if (read?.url !== url) {
    // Placeholder height guessing that 2 figures will be present to reduce screens shifting as data loads
    return <Skeleton variant="rounded" height={96} aria-label={`Loading the ${name} summary`} />;
  }

  if (read.stats === null) {
    // Reaching the console does not mean the reader may see what a tool holds. Zeros would claim it is empty.
    return (
      <Typography variant="placeholder">
        The {name} summary is not available to you.
      </Typography>
    );
  }

  return (
    <Box
      component="dl"
      sx={{
        display: "grid",
        gridTemplateColumns: "auto 1fr",
        columnGap: 1,
        rowGap: 0.5,
        alignItems: "baseline",
        m: 0,
      }}
    >
      {
        read.stats.map((stat, index) => (
          <Fragment key={stat.id}>
            <StatLabel
              stat={stat}
              row={index + 1}
              href={stat.path != undefined && hrefFor != undefined ? hrefFor(stat.path) : undefined}
            />
            <StatValue stat={stat} row={index + 1} />
          </Fragment>
        ))
      }
    </Box>
  );
}

export default WidgetStatList;
