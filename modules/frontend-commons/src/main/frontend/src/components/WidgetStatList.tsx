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
import { readJson } from "../requestFailure";

// One figure, as a summary reports it: a count of something, or a state it is in.
interface WidgetStat {
  // The key the summary listed it under. Unique within one summary, and the row's identity.
  id: string;
  // What the value is a figure for, such as "Archived in total".
  label: string;
  // A count, a state that is on or off, or null for a count that could not be taken.
  value: number | boolean | null;
  // Whether the count is a lower bound rather than complete
  approximate?: boolean;
  // Which count calls for action, and is shown in the error colour: any count above zero, or zero.
  // Undefined when neither does.
  important?: "nonzero" | "zero";
  // The repository path the figure is about. The label links to its route when the list has `hrefFor`,
  // and is plain text otherwise.
  path?: string;
}

interface WidgetStatListProps {
  // The path of the resource whose summary to list, such as "/Workflows". The list reads
  // `<url>.adminSummary.json`.
  url: string;
  // What the figures are about, in words, such as "recorded errors". The loading and refusal messages name it.
  name: string;
  // Turns a figure's repository path into the route its label links to; labels are plain text without it.
  hrefFor?: (path: string) => string;
}

// How a count reads: the number, with a "+" when it is a lower bound, or "?" when there is no count.
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

// Where one figure's two cells sit: the value in the left column, its label to the right of it, both
// on the figure's own row. They are placed explicitly because the label is written first, and grid
// does not put a later item back in a column it has already moved past.
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
        // Digits of equal width, so the figures line up down the column
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

// A dashboard widget's list of figures, each a value and then what the value is a figure for. The
// widget names the resource to summarize and what to call it. The list fetches the summary, shows a
// placeholder while it loads, and says the summary is not available when it cannot be read.
//
// It is a description list: each label is a term, and its value the description. The label comes
// first in the markup and second on screen, so a screen reader says what a figure counts before the
// figure.
//
// Sample usage:
// <WidgetStatList url="/Workflows" name="workflows" hrefFor={adminUrl} />
//
function WidgetStatList({ url, name, hrefFor }: WidgetStatListProps) {
  const doFetch = useAuthenticatedFetch();
  // The summary, and the url it was read for; `stats` is null if it could not be read. A summary read for a
  // previous url means this one is still loading.
  const [ read, setRead ] = useState<{ url: string; stats: WidgetStat[] | null } | null>(null);

  useEffect(() => {
    let cancelled = false;
    doFetch(`${url}.adminSummary.json`, { headers: { Accept: "application/json" } })
      .then(response => {
        if (!response.ok) {
          throw new Error(String(response.status));
        }
        return readJson<unknown>(response);
      })
      .then(body => { if (!cancelled) { setRead({ url, stats: readSummary(body) }); } })
      .catch(() => { if (!cancelled) { setRead({ url, stats: null }); } });
    return () => { cancelled = true; };
  }, [ doFetch, url ]);

  if (read?.url !== url) {
    // Sized for two figures, so the page shifts less when they arrive
    return <Skeleton variant="rounded" height={96} aria-label={`Loading the ${name} summary`} />;
  }

  if (read.stats === null) {
    // Not zeros, which would claim the tool holds nothing
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
