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

import { useCallback, useEffect, useMemo, useRef, useState, type MouseEvent } from "react";

import { Box, InputBase, Link, Stack } from "@mui/material";
import {
  DataGridPro, GridCellModes, type GridCellModesModel, type GridCellParams, type GridColDef,
  type GridRenderEditCellParams, useGridApiContext,
} from "@mui/x-data-grid-pro";

import EntityGridToolbar from "@iap/frontend-commons/entityGrid/EntityGridToolbar";
import TagChip from "@iap/tags/TagChip";

import { SEVERITIES, type ReviewNote } from "./reviewData";

interface IssueRow {
  id: string;
  // Whether it is one of the AI's open concerns rather than the reviewer's issue
  ai: boolean;
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
// changed in their cells, as is the text, saved as the issue is. The toolbar is the entity grids':
// a search over every column, and the columns and filters; shift and click sorts by several columns. The AI's open
// concerns can be listed among them, greyed, to triage everything in one list: made issues of as they are, or
// dismissed.
export default function IssuesGrid({ issues, concerns = [], risks, about, onChange, onShow, onMakeIssue, onDismiss }: {
  issues: ReviewNote[];
  concerns?: ReviewNote[];
  // The risks issues can be filed under, by path
  risks: { path: string; label: string }[];
  about: (note: ReviewNote) => string;
  onChange: (issue: ReviewNote, text: string, severity: string, topic: string) => Promise<void>;
  onShow?: (issue: ReviewNote) => void;
  onMakeIssue?: (concern: ReviewNote) => Promise<void>;
  onDismiss?: (concern: ReviewNote) => Promise<void>;
}) {
  const rows: IssueRow[] = useMemo(() => [ ...issues, ...concerns ].map(issue => ({
    id: issue.id, ai: issue.kind === "aiIssue", severity: issue.severity ?? "medium",
    risk: risks.some(risk => risk.path === issue.topicPath) ? issue.topicPath ?? "" : "",
    about: about(issue), text: issue.text, issue,
  })), [ issues, concerns, risks, about ]);
  // A single click starts changing a cell, as in a spreadsheet, and leaves any other cell being changed
  const [ cellModes, setCellModes ] = useState<GridCellModesModel>({});
  const startEditing = useCallback((params: GridCellParams, event: MouseEvent) => {
    // A click inside the cell's own editor, such as in a list it opened, is not a click on the cell
    if (!params.isEditable || !event.currentTarget.contains(event.target as Node)) {
      return;
    }
    setCellModes(previous => {
      const viewing: GridCellModesModel = {};
      for (const [ row, fields ] of Object.entries(previous)) {
        viewing[row] = Object.fromEntries(Object.keys(fields).map(field => [ field, { mode: GridCellModes.View } ]));
      }
      return { ...viewing, [params.id]: { ...viewing[params.id], [params.field]: { mode: GridCellModes.Edit } } };
    });
  }, []);
  const riskLabel = (path: string) => risks.find(risk => risk.path === path)?.label ?? "No risk";

  const columns: GridColDef<IssueRow>[] = [
    {
      field: "severity", headerName: "Severity", width: 130, editable: true, type: "singleSelect",
      valueOptions: SEVERITIES.map(value => ({ value, label: SEVERITY_NAMES[value] })),
      // By how serious, not by name
      sortComparator: (a: string, b: string) =>
        SEVERITIES.indexOf(a as typeof SEVERITIES[number]) - SEVERITIES.indexOf(b as typeof SEVERITIES[number]),
      renderCell: ({ value }) => <TagChip tags={[ String(value) ]} category="severity" />,
    },
    {
      field: "risk", headerName: "Risk", width: 180, editable: true, type: "singleSelect",
      valueOptions: [ { value: "", label: "No risk" }, ...risks.map(risk => ({ value: risk.path, label: risk.label })) ],
      valueFormatter: (value: string) => riskLabel(value),
    },
    ...concerns.length > 0 ? [ {
      field: "ai", headerName: "Raised by", width: 100, type: "singleSelect" as const,
      valueOptions: [ { value: false, label: "You" }, { value: true, label: "AI" } ],
      valueFormatter: (value: boolean) => (value ? "AI" : "You"),
    } ] : [],
    { field: "about", headerName: "About", width: 180 },
    {
      field: "text", headerName: "Issue", flex: 1, minWidth: 220, editable: true,
      renderCell: ({ value }) => <Box sx={{ whiteSpace: "pre-wrap" }}>{String(value)}</Box>,
      renderEditCell: params => <TextEdit {...params} />,
    },
    {
      field: "where", headerName: "", width: concerns.length > 0 ? 140 : 110, sortable: false, filterable: false,
      renderCell: ({ row }) => (
        <Stack sx={{ alignItems: "flex-start" }}>
          {row.ai && onMakeIssue && (
            <Link component="button" variant="body2" onClick={() => void onMakeIssue(row.issue)}>Make it an issue</Link>
          )}
          {row.ai && onDismiss && (
            <Link component="button" variant="body2" onClick={() => void onDismiss(row.issue)}>Dismiss</Link>
          )}
          {onShow && (row.issue.subjects.length > 0 || row.issue.passage) && (
            <Link component="button" variant="body2" onClick={() => onShow(row.issue)}>Show where</Link>
          )}
        </Stack>
      ),
    },
  ];

  return (
    <DataGridPro
      rows={rows} columns={columns} density="compact" disableRowSelectionOnClick hideFooter
      showToolbar slots={{ toolbar: EntityGridToolbar }} slotProps={{ toolbar: { searchLabel: "Search your issues" } }}
      getRowHeight={() => "auto"}
      // The AI's concerns are greyed, and are not changed in place: they become issues first
      isCellEditable={({ row }: { row: IssueRow }) => !row.ai}
      cellModesModel={cellModes} onCellModesModelChange={setCellModes} onCellClick={startEditing}
      getRowClassName={({ row }) => (row.ai ? "ai-concern" : "")}
      initialState={{ sorting: { sortModel: [ { field: "severity", sort: "asc" }, { field: "risk", sort: "asc" } ] },
        pinnedColumns: { right: [ "where" ] } }}
      processRowUpdate={async (next: IssueRow, before: IssueRow) => {
        if (!next.text.trim()) {
          return before;
        }
        if (next.text !== before.text || next.severity !== before.severity || next.risk !== before.risk) {
          await onChange(next.issue, next.text.trim(), next.severity, next.risk);
        }
        return next;
      }}
      onProcessRowUpdateError={() => undefined}
      localeText={{ noRowsLabel: "No issues raised yet", noResultsOverlayLabel: "No issue matches" }}
      sx={{ "& .MuiDataGrid-cell": { py: 0.75, alignItems: "center" }, bgcolor: "background.paper",
        "& .ai-concern": { color: "text.secondary", bgcolor: "background.muted" } }}
    />
  );
}

// An issue's text changed in its cell, over as many lines as it needs: Enter keeps it, Shift and Enter starts a new
// line, Escape leaves it as it was
function TextEdit({ id, field, value }: GridRenderEditCellParams<IssueRow, string>) {
  const api = useGridApiContext();
  const input = useRef<HTMLTextAreaElement>(null);
  // Into the text, at its end, as soon as the cell opens for it
  useEffect(() => {
    const field = input.current;
    field?.focus();
    field?.setSelectionRange(field.value.length, field.value.length);
  }, []);
  return (
    <InputBase
      multiline fullWidth inputRef={input} value={value ?? ""}
      onChange={event => void api.current.setEditCellValue({ id, field, value: event.target.value })}
      onKeyDown={event => {
        if (event.key === "Enter" && event.shiftKey) {
          event.stopPropagation();
        }
      }}
      inputProps={{ "aria-label": "The issue" }}
      sx={{ px: 1.25, py: 0.75, alignSelf: "stretch", typography: "body2" }}
    />
  );
}
