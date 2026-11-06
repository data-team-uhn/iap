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

import { useMemo } from "react";

import { Link } from "@mui/material";
import { DataGridPro, type GridColDef } from "@mui/x-data-grid-pro";

import EntityGridToolbar from "@iap/frontend-commons/entityGrid/EntityGridToolbar";
import TagChip from "@iap/tags/TagChip";

import { SEVERITIES, type ReviewNote } from "./reviewData";

interface IssueRow {
  id: string;
  severity: string;
  risk: string;
  about: string;
  text: string;
  issue: ReviewNote;
}

const SEVERITY_NAMES: Record<string, string> = {
  critical: "Critical", major: "Major", medium: "Medium", minor: "Minor", suggestion: "Suggestion",
};

// PRETOTYPE. All of the reviewer's issues in one sortable grid, as they would list them in a spreadsheet: how serious,
// under which risk, about what, and what it says, most serious first to start with. The severity and the risk are
// changed in their cells, saved as the issue is; the text in the issue's own editor. The toolbar is the entity grids':
// a search over every column, and the columns and filters; shift and click sorts by several columns.
export default function IssuesGrid({ issues, risks, about, onChange, onShow }: {
  issues: ReviewNote[];
  // The risks issues can be filed under, by path
  risks: { path: string; label: string }[];
  about: (note: ReviewNote) => string;
  onChange: (issue: ReviewNote, severity: string, topic: string) => Promise<void>;
  onShow?: (issue: ReviewNote) => void;
}) {
  const rows: IssueRow[] = useMemo(() => issues.map(issue => ({
    id: issue.id, severity: issue.severity ?? "medium",
    risk: risks.some(risk => risk.path === issue.topicPath) ? issue.topicPath ?? "" : "",
    about: about(issue), text: issue.text, issue,
  })), [ issues, risks, about ]);
  const riskLabel = (path: string) => risks.find(risk => risk.path === path)?.label ?? "No risk";

  const columns: GridColDef<IssueRow>[] = [
    {
      field: "severity", headerName: "Severity", width: 140, editable: true, type: "singleSelect",
      valueOptions: SEVERITIES.map(value => ({ value, label: SEVERITY_NAMES[value] })),
      // By how serious, not by name
      sortComparator: (a: string, b: string) =>
        SEVERITIES.indexOf(a as typeof SEVERITIES[number]) - SEVERITIES.indexOf(b as typeof SEVERITIES[number]),
      renderCell: ({ value }) => <TagChip tags={[ String(value) ]} category="severity" />,
    },
    {
      field: "risk", headerName: "Risk", width: 220, editable: true, type: "singleSelect",
      valueOptions: [ { value: "", label: "No risk" }, ...risks.map(risk => ({ value: risk.path, label: risk.label })) ],
      valueFormatter: (value: string) => riskLabel(value),
    },
    { field: "about", headerName: "About", width: 240 },
    { field: "text", headerName: "Issue", flex: 1, minWidth: 260 },
    {
      field: "where", headerName: "", width: 110, sortable: false, filterable: false,
      renderCell: ({ row }) => onShow && (row.issue.subjects.length > 0 || row.issue.passage) && (
        <Link component="button" variant="body2" onClick={() => onShow(row.issue)}>Show where</Link>
      ),
    },
  ];

  return (
    <DataGridPro
      rows={rows} columns={columns} density="compact" disableRowSelectionOnClick hideFooter
      showToolbar slots={{ toolbar: EntityGridToolbar }} slotProps={{ toolbar: { searchLabel: "Search your issues" } }}
      getRowHeight={() => "auto"}
      initialState={{ sorting: { sortModel: [ { field: "severity", sort: "asc" }, { field: "risk", sort: "asc" } ] } }}
      processRowUpdate={async (next: IssueRow, before: IssueRow) => {
        if (next.severity !== before.severity || next.risk !== before.risk) {
          await onChange(next.issue, next.severity, next.risk);
        }
        return next;
      }}
      onProcessRowUpdateError={() => undefined}
      localeText={{ noRowsLabel: "No issues raised yet", noResultsOverlayLabel: "No issue matches" }}
      sx={{ "& .MuiDataGrid-cell": { py: 0.75, alignItems: "center" }, bgcolor: "background.paper" }}
    />
  );
}
