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

import AutoAwesomeOutlined from "@mui/icons-material/AutoAwesomeOutlined";
import DeleteOutlined from "@mui/icons-material/DeleteOutlined";
import EditOutlined from "@mui/icons-material/EditOutlined";
import ReplyOutlined from "@mui/icons-material/ReplyOutlined";
import { Box, IconButton, InputBase, Link, Stack, Tooltip, Typography } from "@mui/material";
import {
  DataGridPro, GRID_DETAIL_PANEL_TOGGLE_FIELD, GridCellModes, type GridCellModesModel, type GridCellParams,
  type GridColDef, type GridRenderEditCellParams, type GridRowId, gridColumnPositionsSelector,
  gridVisibleColumnDefinitionsSelector, useGridApiContext, useGridSelector,
} from "@mui/x-data-grid-pro";

import EntityGridToolbar from "@iap/frontend-commons/entityGrid/EntityGridToolbar";
import TagChip from "@iap/tags/TagChip";

import Composer from "./Composer";
import { Thread } from "./NoteCard";
import { SEVERITIES, type ReviewNote } from "./reviewData";

interface IssueRow {
  id: string;
  // Whether it is one of the AI's open concerns rather than the reviewer's issue
  ai: boolean;
  severity: string;
  risk: string;
  about: string;
  text: string;
  replies: number;
  // When it was raised
  created: Date | undefined;
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
export default function IssuesGrid({ issues, concerns = [], risks, about, onChange, onShow, onMakeIssue, onDismiss,
  onRemove, onReply, onEditReply, onRemoveReply, compact = false }: {
  issues: ReviewNote[];
  concerns?: ReviewNote[];
  // The risks issues can be filed under, by path
  risks: { path: string; label: string }[];
  about: (note: ReviewNote) => string;
  onChange: (issue: ReviewNote, text: string, severity: string, topic: string) => Promise<void>;
  onShow?: (issue: ReviewNote) => void;
  onMakeIssue?: (concern: ReviewNote) => Promise<void>;
  onDismiss?: (concern: ReviewNote) => Promise<void>;
  onRemove?: (issue: ReviewNote) => Promise<void>;
  onReply?: (issue: ReviewNote, text: string) => Promise<void>;
  onEditReply?: (reply: string, text: string) => Promise<void>;
  onRemoveReply?: (reply: string) => Promise<void>;
  // Narrowed to one risk, under it: no toolbar nor risk column, and only as tall as its rows
  compact?: boolean;
}) {
  const rows: IssueRow[] = useMemo(() => [ ...issues, ...concerns ].map(issue => ({
    id: issue.id, ai: issue.kind === "aiIssue", severity: issue.severity ?? "medium",
    risk: risks.some(risk => risk.path === issue.topicPath) ? issue.topicPath ?? "" : "",
    about: about(issue), text: issue.text, replies: issue.replies.length,
    created: issue.created ? new Date(issue.created) : undefined, issue,
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
  // The issues whose thread is unfolded under them; one with no reply yet unfolds to write the first
  const [ expanded, setExpanded ] = useState<Set<GridRowId>>(new Set());
  const [ writingTo, setWritingTo ] = useState<GridRowId>();
  const toggleThread = (id: GridRowId, write = false) => {
    setExpanded(previous => {
      const next = new Set(previous);
      if (next.has(id) && !write) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
    setWritingTo(write ? id : undefined);
  };
  const startEditingText = (id: GridRowId) => setCellModes({ [id]: { text: { mode: GridCellModes.Edit } } });
  const thread = useCallback(({ row }: { row: IssueRow }) => (
    <ThreadPanel
      issue={row.issue} writing={writingTo === row.id} onWrite={() => setWritingTo(row.id)}
      onStop={() => setWritingTo(undefined)} onReply={onReply} onEditReply={onEditReply} onRemoveReply={onRemoveReply}
    />
  ), [ writingTo, onReply, onEditReply, onRemoveReply ]);
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
      field: "risk", headerName: "Risk", width: 160, editable: true, type: "singleSelect",
      valueOptions: [ { value: "", label: "No risk" }, ...risks.map(risk => ({ value: risk.path, label: risk.label })) ],
      valueFormatter: (value: string) => riskLabel(value),
    },
    ...concerns.length > 0 ? [ {
      field: "ai", headerName: "Raised by", width: 100, type: "singleSelect" as const,
      valueOptions: [ { value: false, label: "You" }, { value: true, label: "AI" } ],
      valueFormatter: (value: boolean) => (value ? "AI" : "You"),
      renderCell: ({ row }: { row: IssueRow }) => (row.ai ? (
        <Stack direction="row" sx={{ alignItems: "center", gap: 0.5, color: "assistant.main" }}>
          <AutoAwesomeOutlined sx={{ fontSize: "1rem" }} />AI
        </Stack>
      ) : "You"),
    } ] : [],
    { field: "about", headerName: "About", width: 170 },
    {
      field: "text", headerName: "Issue", flex: 1, minWidth: 220, editable: true,
      renderCell: ({ value }) => <Box sx={{ whiteSpace: "pre-wrap" }}>{String(value)}</Box>,
      renderEditCell: params => <TextEdit {...params} />,
    },
    {
      field: "created", headerName: "Raised", type: "date", width: 90,
      valueFormatter: (value: Date | undefined) =>
        value ? value.toLocaleDateString(undefined, { month: "short", day: "numeric" }) : "",
    },
    {
      field: "replies", headerName: "Replies", type: "number", width: 80,
      description: "How many replies it has, from the study team or other reviewers",
      // None said by leaving the cell empty; some unfold the thread under the issue, in the grid
      renderCell: ({ row }) => row.replies > 0 && (
        <Tooltip title={`${expanded.has(row.id) ? "Fold" : "Read"} the ${row.replies === 1 ? "reply" : `${row.replies} replies`}`}>
          <Link component="button" variant="body2" aria-expanded={expanded.has(row.id)}
            onClick={() => toggleThread(row.id)}>
            {row.replies}
          </Link>
        </Tooltip>
      ),
    },
    {
      field: "where", headerName: "", width: concerns.length > 0 ? 150 : 130, sortable: false, filterable: false,
      // Right-aligned, reply first, so change and remove line up with those of the replies unfolded under it
      renderCell: ({ row }) => (
        <Stack sx={{ alignItems: "flex-end", width: "100%" }}>
          {!row.ai && (
            <Stack direction="row">
              {onReply && (
                <Tooltip title="Reply">
                  <IconButton size="small" aria-label="Reply" onClick={() => toggleThread(row.id, true)}>
                    <ReplyOutlined fontSize="small" />
                  </IconButton>
                </Tooltip>
              )}
              <Tooltip title="Change">
                <IconButton size="small" aria-label="Change the issue" onClick={() => startEditingText(row.id)}>
                  <EditOutlined fontSize="small" />
                </IconButton>
              </Tooltip>
              <Tooltip title="Remove">
                <IconButton size="small" aria-label="Remove the issue" onClick={() => void onRemove?.(row.issue)}>
                  <DeleteOutlined fontSize="small" />
                </IconButton>
              </Tooltip>
            </Stack>
          )}
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

  // Nothing to list: a plain box saying so, rather than an empty table
  if (rows.length === 0) {
    return (
      <Box sx={{ borderRadius: 1, px: 2, py: 2, bgcolor: "background.muted", textAlign: "center" }}>
        <Typography variant="body2" sx={{ color: "text.secondary" }}>No issues raised yet</Typography>
      </Box>
    );
  }
  return (
    <DataGridPro
      rows={rows} columns={columns} density="compact" disableRowSelectionOnClick hideFooter
      showToolbar={!compact} slots={{ toolbar: EntityGridToolbar }}
      slotProps={{ toolbar: { searchLabel: "Search your issues" } }} autoHeight={compact}
      getRowHeight={() => "auto"}
      // The AI's concerns are greyed, and are not changed in place: they become issues first
      isCellEditable={({ row }: { row: IssueRow }) => !row.ai}
      getDetailPanelContent={thread} getDetailPanelHeight={() => "auto"}
      detailPanelExpandedRowIds={expanded} onDetailPanelExpandedRowIdsChange={setExpanded}
      // The replies count unfolds a thread, so the grid's own toggle column is left out
      columnVisibilityModel={{ [GRID_DETAIL_PANEL_TOGGLE_FIELD]: false, ...compact ? { risk: false } : {} }}
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

// An issue's thread, unfolded under it in the grid, lined up under the issue's text wherever that column is: its
// replies, and a reply being written or a way to write one
function ThreadPanel({ issue, writing, onWrite, onStop, onReply, onEditReply, onRemoveReply }: {
  issue: ReviewNote;
  writing: boolean;
  onWrite: () => void;
  onStop: () => void;
  onReply?: (issue: ReviewNote, text: string) => Promise<void>;
  onEditReply?: (reply: string, text: string) => Promise<void>;
  onRemoveReply?: (reply: string) => Promise<void>;
}) {
  const api = useGridApiContext();
  const positions = useGridSelector(api, gridColumnPositionsSelector);
  const shown = useGridSelector(api, gridVisibleColumnDefinitionsSelector);
  const left = positions[shown.findIndex(column => column.field === "text")] ?? 0;
  return (
    // The cell's own padding, less the thread's line and its gap, so the replies' words start where the issue's do
    <Box sx={{ pr: 2, pb: 1.5, bgcolor: "background.muted", pl: `${left + 10 - 12}px` }}>
      <Thread replies={issue.replies} onEdit={onEditReply} onRemove={onRemoveReply} />
      {writing && onReply ? (
        <Box sx={{ mt: 1, maxWidth: 720 }}>
          <Composer
            kinds={[]} saveLabel="Reply" onCancel={onStop}
            onSave={async (_kind, text) => {
              await onReply(issue, text);
              onStop();
            }}
          />
        </Box>
      ) : onReply && (
        <Link component="button" variant="caption" sx={{ mt: 0.5, ml: "14px" }} onClick={onWrite}>Reply</Link>
      )}
    </Box>
  );
}
