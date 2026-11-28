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

import {
  createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type MouseEvent, type ReactNode,
} from "react";

import AutoAwesomeOutlined from "@mui/icons-material/AutoAwesomeOutlined";
import EditOutlined from "@mui/icons-material/EditOutlined";
import { Box, Button, IconButton, InputBase, Link, MenuItem, Stack, TextField, Tooltip, Typography } from "@mui/material";
import {
  DataGridPro, GRID_DETAIL_PANEL_TOGGLE_FIELD, GridCellModes, GridEditSingleSelectCell, type GridCellModesModel,
  type GridCellParams, type GridColDef, type GridColumnVisibilityModel, type GridRenderCellParams,
  type GridRenderEditCellParams, type GridRowId, type GridSortModel, type GridValidRowModel,
  gridColumnPositionsSelector, gridVisibleColumnDefinitionsSelector, useGridApiContext, useGridSelector,
} from "@mui/x-data-grid-pro";

import EntityGridToolbar from "@iap/frontend-commons/entityGrid/EntityGridToolbar";
import EntityListItem from "@iap/frontend-commons/entityGrid/EntityListItem";
import type { EntityGridColumn } from "@iap/frontend-commons/entityGrid/registry";
import useColumnVisibility from "@iap/frontend-commons/entityGrid/useColumnVisibility";
import { useNarrowerThan } from "@iap/frontend-commons/useNarrowerThan";
import TagChip from "@iap/tags/TagChip";

import Composer, { ISSUE_PROMPTS } from "./Composer";
import { SentLock, Thread } from "./NoteCard";
import PolicyLine from "./PolicyLine";
import { pulseWhenThere } from "./pulse";
import RemoveButton from "./RemoveButton";
import { NATURE_NAMES, NATURES, SEVERITIES, type Nature, type ReviewNote } from "./reviewData";


interface IssueRow {
  id: string;
  // Whether it is one of the AI's open concerns rather than the reviewer's issue
  ai: boolean;
  severity: string;
  nature: Nature;
  risk: string;
  about: string;
  text: string;
  replies: number;
  // When it was raised
  created: Date | undefined;
  issue: ReviewNote;
}

// A column, and where it goes on the card a row becomes when the grid is too narrow for its columns
export type CardColumn<Row extends GridValidRowModel> = GridColDef<Row> & Pick<EntityGridColumn, "cardSlot"> & {
  cardValue?: (row: Row) => ReactNode;
};

// Below this width, which the columns need, a grid lists its rows as cards
export const CARDS_BELOW = 720;

// A row's card, made by the entity grids' own from the columns shown
export function cardOf<Row extends GridValidRowModel>(row: Row, columns: CardColumn<Row>[]) {
  return <EntityListItem row={row} columns={columns as unknown as EntityGridColumn[]} />;
}

// How serious an issue is, for a required change, or as the AI said of a concern
const severityOf = (row: IssueRow) => (row.ai ? row.issue.severity
  : row.nature === "required-change" ? row.severity : undefined);
// What an issue is, said beside how serious; an AI concern only when the AI said it is a suggestion
const kindShown = (row: IssueRow) => (!row.ai || row.issue.nature === "suggestion" ? row.nature : undefined);
const rank = (severity: string) => {
  const at = SEVERITIES.indexOf(severity as typeof SEVERITIES[number]);
  return at < 0 ? SEVERITIES.length : at;
};

const SEVERITY_NAMES: Record<string, string> = {
  critical: "Critical", major: "Major", medium: "Medium", minor: "Minor",
};

// PRETOTYPE. All of the reviewer's issues in one sortable grid, as they would list them in a spreadsheet: how serious,
// under which risk, about what, and what it says, most serious first to start with. The severity and the risk are
// changed in their cells, as is the text, saved as the issue is. The toolbar is the entity grids':
// a search over every column, and the columns and filters; shift and click sorts by several columns. The AI's open
// concerns can be listed among them, greyed, to triage everything in one list: made issues of as they are, or
// dismissed.
export default function IssuesGrid({ issues, concerns = [], risks, about, onChange, onShow, onMakeIssue, onDismiss,
  onRemove, onReply, onEditReply, onRemoveReply, compact = false, keepRisk = false, fitRows = compact, sayEmpty = true,
  adding = false, defaultRisk = "", defaultNature = "required-change", onAdd, onAddingDone }: {
  issues: ReviewNote[];
  concerns?: ReviewNote[];
  // The risks issues can be filed under, by path
  risks: { path: string; label: string }[];
  about: (note: ReviewNote) => string;
  onChange: (issue: ReviewNote, text: string, severity: string, topic: string, nature: Nature) => Promise<void>;
  onShow?: (issue: ReviewNote) => void;
  onMakeIssue?: (concern: ReviewNote) => Promise<void>;
  onDismiss?: (concern: ReviewNote) => Promise<void>;
  onRemove?: (issue: ReviewNote) => Promise<void>;
  onReply?: (issue: ReviewNote, text: string) => Promise<void>;
  onEditReply?: (reply: string, text: string) => Promise<void>;
  onRemoveReply?: (reply: string) => Promise<void>;
  // Narrowed to one risk, under it: no toolbar nor risk column, and only as tall as its rows; the risk column kept
  // where the issues are under none, to file them
  compact?: boolean;
  keepRisk?: boolean;
  // As tall as its rows, rather than the height it is given; so it is when compact
  fitRows?: boolean;
  // Whether a box says there are none, where nothing else would
  sayEmpty?: boolean;
  // A new issue being written, in a row of its own pinned at the top: saved under the risk chosen in it, or given up
  adding?: boolean;
  defaultRisk?: string;
  // PRETOTYPE: what a new issue is unless the reviewer says otherwise, as their group usually asks
  defaultNature?: Nature;
  onAdd?: (text: string, severity: string, topic: string, nature: Nature) => Promise<void>;
  onAddingDone?: () => void;
}) {
  const rows: IssueRow[] = useMemo(() => [ ...issues, ...concerns ].map(issue => ({
    id: issue.id, ai: issue.kind === "aiIssue", severity: issue.severity ?? "medium",
    nature: issue.nature ?? (issue.kind === "aiIssue" ? "required-change" : defaultNature),
    risk: risks.some(risk => risk.path === issue.topicPath) ? issue.topicPath ?? "" : "",
    about: about(issue), text: issue.text, replies: issue.replies.length,
    created: issue.created ? new Date(issue.created) : undefined, issue,
  })), [ issues, concerns, risks, about, defaultNature ]);
  // A single click starts changing a cell, as in a spreadsheet, and leaves any other cell being changed
  const [ cellModes, setCellModes ] = useState<GridCellModesModel>({});
  const startEditing = useCallback((params: GridCellParams, event: MouseEvent) => {
    // A click inside the cell's own editor, such as in a list it opened, is not a click on the cell, nor one on a link
    // or button it shows
    if (!params.isEditable || !event.currentTarget.contains(event.target as Node)
      || (event.target as HTMLElement).closest("a, button")) {
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
  const thread = useCallback(({ row }: { row: IssueRow }) => (
    <ThreadPanel
      issue={row.issue} writing={writingTo === row.id} onWrite={() => setWritingTo(row.id)}
      onStop={() => setWritingTo(undefined)} onReply={onReply} onEditReply={onEditReply} onRemoveReply={onRemoveReply}
    />
  ), [ writingTo, onReply, onEditReply, onRemoveReply ]);
  const riskLabel = (path: string) => risks.find(risk => risk.path === path)?.label ?? "Other";
  // Where the grid is too narrow for its columns, each issue is a card, changed on the card as the new one is written
  const [ element, setElement ] = useState<HTMLDivElement | null>(null);
  const cards = useNarrowerThan(element, CARDS_BELOW);
  const [ onCard, setOnCard ] = useState<{ id: string; draft: Draft }>();
  const [ savingCard, setSavingCard ] = useState(false);
  const canChange = (row: IssueRow) => !row.ai && row.id !== DRAFT && !row.issue.sent;
  const changeOnCard = (row: IssueRow) =>
    setOnCard({ id: row.id, draft: { severity: row.severity, nature: row.nature, risk: row.risk, text: row.text } });
  // The columns the reviewer chose, in this browser; the thread's toggle is never one, nor, under one risk, the risk
  const [ chosenColumns, chooseColumns ] = useColumnVisibility("review/issues");
  const forced: GridColumnVisibilityModel = { [GRID_DETAIL_PANEL_TOGGLE_FIELD]: false,
    ...compact && !keepRisk ? { risk: false } : {} };
  const visibility = { ...chosenColumns, ...forced };
  const choose = (model: GridColumnVisibilityModel) =>
    chooseColumns(Object.fromEntries(Object.entries(model).filter(([ field ]) => !(field in forced))));
  const [ sortModel, setSortModel ] = useState<GridSortModel>(
    [ { field: "severity", sort: "asc" }, { field: "risk", sort: "asc" } ]);
  // Under an issue's text, its replies, unfolding under the issue, and, while they are folded, a way to add one
  const replyLinks = (row: IssueRow) => !row.ai && (onReply !== undefined || row.replies > 0) && (
    <Stack direction="row" sx={{ gap: 1.5, mt: 0.25 }}>
      {row.replies > 0 && (
        <Link component="button" variant="caption" aria-expanded={expanded.has(row.id)}
          onClick={() => toggleThread(row.id)}>
          {`${expanded.has(row.id) ? "Hide" : "Show"} ${row.replies === 1 ? "1 reply" : `${row.replies} replies`}`}
        </Link>
      )}
      {/* Unfolded, the thread has its own, under the last reply */}
      {onReply && !expanded.has(row.id) && (
        <Link component="button" variant="caption" onClick={() => toggleThread(row.id, true)}>Reply</Link>
      )}
    </Stack>
  );
  // What can be done with an issue: removing it, or nothing once sent; with an AI concern, making it an issue or
  // dismissing it. On a card, also changing it, which the grid does in its cells
  const actions = (row: IssueRow, onCard: boolean) => (
    <Stack direction={onCard ? "row" : "column"} sx={{ alignItems: onCard ? "center" : "flex-end", gap: onCard ? 1 : 0,
      width: onCard ? undefined : "100%" }}>
      {onCard && canChange(row) && (
        <Tooltip disableInteractive title="Change the issue">
          <IconButton size="small" aria-label="Change the issue" onClick={() => changeOnCard(row)}>
            <EditOutlined fontSize="small" />
          </IconButton>
        </Tooltip>
      )}
      {!row.ai && (
        <Stack direction="row">
          {row.issue.sent ? <SentLock sent={row.issue.sent} />
            : onRemove && <RemoveButton what="issue" text={row.text} onRemove={() => onRemove(row.issue)} />}
        </Stack>
      )}
      {row.ai && onMakeIssue && (
        <Link component="button" variant="body2" onClick={() => void onMakeIssue(row.issue)}>Make it an issue</Link>
      )}
      {row.ai && onDismiss && (
        <Link component="button" variant="body2" onClick={() => void onDismiss(row.issue)}>Dismiss</Link>
      )}
    </Stack>
  );
  const day = (date: Date) => date.toLocaleDateString(undefined, { month: "short", day: "numeric" });

  const columns: CardColumn<IssueRow>[] = [
    {
      field: "severity", headerName: "Severity", width: 130, editable: true, type: "singleSelect",
      // How serious leads the card
      cardSlot: "title", cardValue: row => (
        <Stack direction="row" sx={{ gap: 0.75, flexWrap: "wrap" }}>
          {severityOf(row) && <TagChip tags={[ severityOf(row) ?? "" ]} category="severity" />}
          {kindShown(row) && <TagChip tags={[ row.nature ]} category="issue-kind" />}
        </Stack>
      ),
      valueOptions: SEVERITIES.map(value => ({ value, label: SEVERITY_NAMES[value] })),
      renderEditCell: params => <ChoiceEdit {...params} />,
      // By how serious, not by name; what has no severity, as a question or a suggestion, after
      valueGetter: (_value: string, row: IssueRow) => severityOf(row) ?? "",
      sortComparator: (a: string, b: string) => rank(a) - rank(b),
      renderCell: ({ row }) => {
        const severity = severityOf(row);
        return severity && <TagChip tags={[ severity ]} category="severity" />;
      },
    },
    {
      // PRETOTYPE: a question, or a change the study team must make; changed in its cell as the severity is
      field: "nature", headerName: "Type", width: 160, editable: true, type: "singleSelect",
      valueOptions: NATURES.map(value => ({ value, label: NATURE_NAMES[value] })),
      renderEditCell: params => <ChoiceEdit {...params} />,
      renderCell: ({ row }) => kindShown(row) && <TagChip tags={[ row.nature ]} category="issue-kind" />,
      // On a card, beside how serious
      cardSlot: "omit",
    },
    {
      field: "risk", headerName: "Risk", width: 160, editable: true, type: "singleSelect",
      cardSlot: "caption", cardValue: row => riskLabel(row.risk),
      valueOptions: [ { value: "", label: "Other" }, ...risks.map(risk => ({ value: risk.path, label: risk.label })) ],
      valueFormatter: (value: string) => riskLabel(value),
      renderEditCell: params => <ChoiceEdit {...params} />,
    },
    {
      field: "created", headerName: "Raised", type: "date", width: 90,
      valueFormatter: (value: Date | undefined) => (value ? day(value) : ""),
      cardSlot: "caption", cardValue: row => row.created && `Raised ${day(row.created)}`,
    },
    ...concerns.length > 0 ? [ {
      field: "ai", headerName: "Raised by", width: 100, type: "singleSelect" as const,
      valueOptions: [ { value: false, label: "You" }, { value: true, label: "AI" } ],
      valueFormatter: (value: boolean) => (value ? "AI" : "You"),
      renderCell: ({ row }: { row: IssueRow }) => (row.ai ? <AiMark /> : "You"),
      // On a card, only the AI's say who raised them
      cardSlot: "badge" as const, cardValue: (row: IssueRow) => row.ai && <AiMark />,
    } ] : [],
    {
      // What an issue is about leads to it, so it reads as a way there, not as something to change
      field: "about", headerName: "About", width: 170,
      renderCell: ({ row }) => onShow && (row.issue.subjects.length > 0 || row.issue.passage)
        ? <WhereLink text={row.about} onClick={() => onShow(row.issue)} />
        : row.about,
      cardSlot: "caption",
      cardValue: row => onShow && (row.issue.subjects.length > 0 || row.issue.passage)
        ? <WhereLink text={row.about} variant="inherit" onClick={() => onShow(row.issue)} />
        : row.about,
    },
    {
      field: "text", headerName: "Issue", flex: 1, minWidth: 220, editable: true,
      renderCell: ({ row, value }) => (
        <>
          <Box sx={{ whiteSpace: "pre-wrap" }}>{String(value)}</Box>
          <PolicyLine note={row.issue} dense />
          {replyLinks(row)}
        </>
      ),
      renderEditCell: params => <TextEdit {...params} />,
      // On a card, the thread unfolds in the card, under the text
      cardSlot: "body",
      cardValue: row => (
        <>
          <Box sx={{ whiteSpace: "pre-wrap" }}>{row.text}</Box>
          <PolicyLine note={row.issue} dense />
          {replyLinks(row)}
          {expanded.has(row.id) && (
            <Box sx={{ mt: 1 }}>
              <ThreadBody
                issue={row.issue} writing={writingTo === row.id} onWrite={() => setWritingTo(row.id)}
                onStop={() => setWritingTo(undefined)} onReply={onReply} onEditReply={onEditReply}
                onRemoveReply={onRemoveReply}
              />
            </Box>
          )}
        </>
      ),
    },
    {
      field: "where", headerName: "", width: concerns.length > 0 ? 150 : 64, sortable: false, filterable: false,
      // Right-aligned, so remove lines up with that of the replies unfolded under it
      renderCell: ({ row }) => actions(row, false),
      cardSlot: "actions", cardValue: row => actions(row, true),
    },
  ];

  // The new issue's row: what it is, about nothing on the page, and its text, with saving and giving up as its actions
  const [ draft, setDraft ] = useState<Draft>({ severity: "medium", nature: defaultNature, risk: defaultRisk,
    text: "" });
  const [ saving, setSaving ] = useState(false);
  const known = useRef<Set<string>>(undefined);
  const stopAdding = () => {
    setDraft({ severity: "medium", nature: defaultNature, risk: defaultRisk, text: "" });
    onAddingDone?.();
  };
  const save = async () => {
    if (!draft.text.trim() || !onAdd) {
      return;
    }
    setSaving(true);
    try {
      known.current = new Set(rows.map(row => row.id));
      await onAdd(draft.text.trim(), draft.severity, draft.risk, draft.nature);
      stopAdding();
    } finally {
      setSaving(false);
    }
  };
  // Once an issue is saved, new or changed, it is brought into view and pulses, so the reviewer sees where it went
  const changed = useRef<string>(undefined);
  useEffect(() => {
    const before = known.current;
    const saved = before ? rows.find(row => !before.has(row.id))?.id : changed.current;
    if (saved) {
      known.current = undefined;
      changed.current = undefined;
      pulseWhenThere(() => document.querySelector<HTMLElement>(`[role="row"][data-id="${CSS.escape(saved)}"]`),
        "nearest");
    }
  }, [ rows ]);
  const draftRow: IssueRow = { id: DRAFT, ai: false, severity: draft.severity, nature: draft.nature, risk: draft.risk,
    about: "",
    text: draft.text, replies: 0, created: undefined, issue: DRAFT_NOTE };
  // The new issue's cells read it from one place, since the grid redraws a cell only when its own value changes
  const drafting = { draft, setDraft, save, stop: stopAdding, saving,
    severities: SEVERITIES.map(value => ({ value, label: SEVERITY_NAMES[value] })),
    natures: NATURES.map(value => ({ value, label: NATURE_NAMES[value] })),
    risks: [ { value: "", label: "Other" }, ...risks.map(risk => ({ value: risk.path, label: risk.label })) ] };
  const draftCell = (field: string) => <DraftCell field={field} />;
  const saveCard = async () => {
    const row = onCard && rows.find(one => one.id === onCard.id);
    if (!onCard || !row || !onCard.draft.text.trim()) {
      return;
    }
    setSavingCard(true);
    try {
      const { text, severity, nature, risk } = onCard.draft;
      if (text.trim() !== row.text || severity !== row.severity || nature !== row.nature || risk !== row.risk) {
        await onChange(row.issue, text.trim(), severity, risk, nature);
        changed.current = row.id;
      }
      setOnCard(undefined);
    } finally {
      setSavingCard(false);
    }
  };
  // A column absent from the model is shown; the model's index type hides the undefined
  const shownColumns = columns.filter(column => (visibility[column.field] as boolean | undefined) !== false);
  const withRisk = shownColumns.some(column => column.field === "risk");
  const card = (row: IssueRow) => (onCard?.id === row.id ? (
    <CardEditor
      draft={onCard.draft} onChange={draft => setOnCard({ id: row.id, draft })} onSave={saveCard}
      onCancel={() => setOnCard(undefined)} saving={savingCard} withRisk={withRisk} label="The issue"
    />
  ) : cardOf(row, shownColumns));
  // Every column draws the new issue's row with its editors, the others as they would
  const shown = columns.map(column => ({
    ...column,
    renderCell: (params: GridRenderCellParams<IssueRow>) => {
      if (params.row.id === DRAFT) {
        return draftCell(column.field);
      }
      const content = column.renderCell ? column.renderCell(params) : params.formattedValue as ReactNode;
      // A cell that can be changed says so with a pencil over its corner under the pointer, so nothing rewraps,
      // or always where there is no pointer, beside the content
      return column.editable ? (
        <Box className="editable" sx={{ position: "relative", width: "100%" }}>
          {content}
          <Box className="edit-hint"><EditOutlined /></Box>
        </Box>
      ) : content;
    },
  }));

  // Nothing to list: a plain box saying so, rather than an empty table
  if (rows.length === 0 && !adding) {
    return sayEmpty && (
      <Box sx={{ borderRadius: 1, px: 2, py: 2, bgcolor: "background.muted", textAlign: "center" }}>
        <Typography variant="body2" sx={{ color: "text.secondary" }}>No issues raised yet</Typography>
      </Box>
    );
  }
  return (
    <Drafting.Provider value={drafting}>
      <Stack ref={setElement} sx={{ width: "100%", height: fitRows ? undefined : "100%" }}>
        {/* On cards, the new issue is written on one of its own, over the others */}
        {cards && adding && (
          <Box sx={{ mb: 1, px: 1.5, border: 2, borderColor: "primary.main", borderRadius: 1,
            bgcolor: "background.paper" }}>
            <CardEditor
              draft={draft} onChange={setDraft} onSave={save} onCancel={stopAdding} saving={saving} withRisk={withRisk}
              label="The new issue"
            />
          </Box>
        )}
        {!(cards && rows.length === 0) && (
          <DataGridPro
            rows={rows} columns={shown} density="compact" disableRowSelectionOnClick hideFooter
            listView={cards} listViewColumn={{ field: "__card__", renderCell: ({ row }) => card(row) }}
            pinnedRows={{ top: adding && !cards ? [ draftRow ] : [] }}
            showToolbar={!compact} slots={{ toolbar: EntityGridToolbar }}
            // Cards have no headers to sort by, so the toolbar has a sort menu instead
            slotProps={{ toolbar: { searchLabel: "Search your issues", showSortMenu: cards, sortModel,
              onSortModelChange: setSortModel, sortableColumns: shownColumns.filter(column => column.sortable !== false)
                .map(column => ({ field: column.field, headerName: column.headerName })) } }}
            autoHeight={fitRows} getRowHeight={() => "auto"}
            // The AI's concerns are greyed, and are not changed in place: they become issues first
            // The new issue's row has its own editors, which the grid's must not take over
            // Only a required change is as serious as its severity
            isCellEditable={({ row, field }: { row: IssueRow; field: string }) => canChange(row)
              && (field !== "severity" || row.nature === "required-change")}
            getDetailPanelContent={cards ? undefined : thread} getDetailPanelHeight={() => "auto"}
            detailPanelExpandedRowIds={expanded} onDetailPanelExpandedRowIdsChange={setExpanded}
            columnVisibilityModel={visibility} onColumnVisibilityModelChange={choose}
            sortModel={sortModel} onSortModelChange={setSortModel}
            cellModesModel={cellModes} onCellModesModelChange={setCellModes} onCellClick={startEditing}
            getRowClassName={({ row }) => (row.ai ? "ai-concern" : "")}
            initialState={{ pinnedColumns: { right: [ "where" ] } }}
            processRowUpdate={async (next: IssueRow, before: IssueRow) => {
              if (!next.text.trim()) {
                return before;
              }
              if (next.text !== before.text || next.severity !== before.severity || next.nature !== before.nature
                || next.risk !== before.risk) {
                await onChange(next.issue, next.text.trim(), next.severity, next.risk, next.nature);
                // Saved, it may have moved with the sorting: it pulses where it lands
                changed.current = next.id;
              }
              return next;
            }}
            onProcessRowUpdateError={() => undefined}
            localeText={{ noRowsLabel: "No issues raised yet", noResultsOverlayLabel: "No issue matches" }}
            sx={{ "& .MuiDataGrid-cell": { py: 0.75, alignItems: "center" }, bgcolor: "background.paper",
              "& .ai-concern": { color: "text.secondary", bgcolor: "background.muted" },
              // A cell that changes on a click says so under the pointer, as a spreadsheet's does
              "& .MuiDataGrid-cell--editable": { cursor: "pointer" },
              // The text being changed stays where it was read, at the top, however tall the row
              "& .MuiDataGrid-cell--editing": { alignItems: "flex-start" },
              "& .MuiDataGrid-cell--editable[data-field='text']": { cursor: "text" },
              "& .MuiDataGrid-cell--editable:hover": { outline: "1px solid", outlineColor: "primary.light", outlineOffset: "-2px",
                borderRadius: 0.5 },
              "& .edit-hint": { display: "none", position: "absolute", top: -2, right: -4, width: 24, height: 24,
                borderRadius: "50%", bgcolor: "background.default", color: "text.secondary", boxShadow: 1, cursor: "pointer",
                alignItems: "center", justifyContent: "center", "& svg": { fontSize: "0.95rem" } },
              "& .MuiDataGrid-cell--editable:hover .edit-hint": { display: "flex" },
              "@media (hover: none)": { "& .MuiDataGrid-cell--editable": { "& .editable": { pr: 3.5 },
                "& .edit-hint": { display: "flex" } } }, flex: fitRows ? undefined : 1, minHeight: 0 }}
          />
        )}
      </Stack>
    </Drafting.Provider>
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
      sx={{ px: 1.25, py: 0.75, alignSelf: "stretch", alignItems: "flex-start", typography: "body2" }}
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
      <ThreadBody issue={issue} writing={writing} onWrite={onWrite} onStop={onStop} onReply={onReply}
        onEditReply={onEditReply} onRemoveReply={onRemoveReply} />
    </Box>
  );
}

// An issue's replies, and a reply being written or a way to write one
function ThreadBody({ issue, writing, onWrite, onStop, onReply, onEditReply, onRemoveReply }: {
  issue: ReviewNote;
  writing: boolean;
  onWrite: () => void;
  onStop: () => void;
  onReply?: (issue: ReviewNote, text: string) => Promise<void>;
  onEditReply?: (reply: string, text: string) => Promise<void>;
  onRemoveReply?: (reply: string) => Promise<void>;
}) {
  return (
    <>
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
    </>
  );
}

// A way to where something was raised, in the words saying what it is about
export function WhereLink({ text, variant = "body2", onClick }: {
  text: string;
  variant?: "body2" | "inherit";
  onClick: () => void;
}) {
  return (
    <Link component="button" variant={variant} onClick={onClick} sx={{ textAlign: "start", whiteSpace: "normal" }}>
      {text}
    </Link>
  );
}

const DRAFT = "new-issue";
const DRAFT_NOTE: ReviewNote = { id: DRAFT, kind: "issue", text: "", author: "", created: "", mine: true, subjects: [],
  replies: [] };

// A choice in the new issue's row, as a cell's own list would be
function DraftSelect({ label, value, options, onChange }: {
  label: string;
  value: string;
  options: { value: string; label: string }[];
  onChange: (value: string) => void;
}) {
  return (
    <TextField
      select fullWidth size="small" variant="standard" value={value} onChange={event => onChange(event.target.value)}
      onKeyDown={event => event.stopPropagation()}
      slotProps={{ htmlInput: { "aria-label": label }, select: { displayEmpty: true } }}
    >
      {options.map(option => <MenuItem key={option.value} value={option.value}>{option.label}</MenuItem>)}
    </TextField>
  );
}

// The new issue's text, written in its cell as a changed one is: Enter keeps it, Shift and Enter starts a new line,
// Escape gives it up
function DraftText({ value, label = "The new issue", placeholder, onChange, onSave, onCancel }: {
  value: string;
  label?: string;
  placeholder: string;
  onChange: (value: string) => void;
  onSave: () => Promise<void>;
  onCancel: () => void;
}) {
  const input = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    input.current?.focus();
  }, []);
  return (
    <InputBase
      multiline fullWidth inputRef={input} value={value} placeholder={placeholder}
      onChange={event => onChange(event.target.value)}
      onKeyDown={event => {
        event.stopPropagation();
        if (event.key === "Enter" && !event.shiftKey) {
          event.preventDefault();
          void onSave();
        } else if (event.key === "Escape") {
          onCancel();
        }
      }}
      inputProps={{ "aria-label": label }}
      sx={{ typography: "body2", py: 0.25 }}
    />
  );
}

interface Draft {
  severity: string;
  nature: Nature;
  risk: string;
  text: string;
}

const Drafting = createContext<{
  draft: Draft;
  setDraft: (draft: Draft) => void;
  save: () => Promise<void>;
  stop: () => void;
  saving: boolean;
  severities: { value: string; label: string }[];
  natures: { value: string; label: string }[];
  risks: { value: string; label: string }[];
} | undefined>(undefined);

// One cell of the new issue's row: its severity and risk lists, its text, or saving it and giving it up
function DraftCell({ field }: { field: string }) {
  const drafting = useContext(Drafting);
  if (!drafting) {
    return null;
  }
  const { draft, setDraft, save, stop, saving } = drafting;
  switch (field) {
    case "severity":
      return draft.nature === "required-change" && <DraftSelect label="Severity" value={draft.severity}
        options={drafting.severities} onChange={severity => setDraft({ ...draft, severity })} />;
    case "nature":
      return <DraftSelect label="Type" value={draft.nature} options={drafting.natures}
        onChange={nature => setDraft({ ...draft, nature: nature as Nature })} />;
    case "risk":
      return <DraftSelect label="Risk" value={draft.risk} options={drafting.risks}
        onChange={risk => setDraft({ ...draft, risk })} />;
    case "text":
      return (
        <DraftText value={draft.text} placeholder={ISSUE_PROMPTS[draft.nature]}
          onChange={text => setDraft({ ...draft, text })} onSave={save} onCancel={stop} />
      );
    case "created":
      return new Date().toLocaleDateString(undefined, { month: "short", day: "numeric" });
    case "where":
      return (
        <Stack sx={{ gap: 0.5, alignItems: "flex-end", width: "100%" }}>
          <Button size="small" variant="contained" disabled={saving || !draft.text.trim()} onClick={() => void save()}>
            Save
          </Button>
          <Button size="small" onClick={stop}>Cancel</Button>
        </Stack>
      );
    default:
      return null;
  }
}

// An issue changed or written on its card, as the grid's cells and its new issue's row do: how serious, under which
// risk where the grid shows risks, what it says, and saving it or giving up
function CardEditor({ draft, onChange, onSave, onCancel, saving, withRisk, label }: {
  draft: Draft;
  onChange: (draft: Draft) => void;
  onSave: () => Promise<void>;
  onCancel: () => void;
  saving: boolean;
  withRisk: boolean;
  label: string;
}) {
  const drafting = useContext(Drafting);
  if (!drafting) {
    return null;
  }
  return (
    <Stack spacing={1} sx={{ py: 1, width: "100%" }}>
      <Stack direction="row" sx={{ gap: 1.5 }}>
        {draft.nature === "required-change" && (
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <DraftSelect label="Severity" value={draft.severity} options={drafting.severities}
              onChange={severity => onChange({ ...draft, severity })} />
          </Box>
        )}
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <DraftSelect label="Type" value={draft.nature} options={drafting.natures}
            onChange={nature => onChange({ ...draft, nature: nature as Nature })} />
        </Box>
        {withRisk && (
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <DraftSelect label="Risk" value={draft.risk} options={drafting.risks}
              onChange={risk => onChange({ ...draft, risk })} />
          </Box>
        )}
      </Stack>
      <Box sx={{ border: 1, borderColor: "divider", borderRadius: 1, px: 1, py: 0.5 }}>
        <DraftText value={draft.text} label={label} placeholder={ISSUE_PROMPTS[draft.nature]}
          onChange={text => onChange({ ...draft, text })} onSave={onSave} onCancel={onCancel} />
      </Box>
      <Stack direction="row" sx={{ gap: 1, justifyContent: "flex-end" }}>
        <Button size="small" onClick={onCancel}>Cancel</Button>
        <Button size="small" variant="contained" disabled={saving || !draft.text.trim()} onClick={() => void onSave()}>
          Save
        </Button>
      </Stack>
    </Stack>
  );
}

// Raised by the AI, in its colour
function AiMark() {
  return (
    <Stack direction="row" sx={{ alignItems: "center", gap: 0.5, color: "assistant.main" }}>
      <AutoAwesomeOutlined sx={{ fontSize: "1rem" }} />AI
    </Stack>
  );
}

// A list in a cell, kept as soon as a choice is made in it rather than when the cell is left
function ChoiceEdit(props: GridRenderEditCellParams<IssueRow>) {
  const api = useGridApiContext();
  return (
    <GridEditSingleSelectCell
      {...props}
      onValueChange={async (_event, value: unknown) => {
        await api.current.setEditCellValue({ id: props.id, field: props.field, value });
        api.current.stopCellEditMode({ id: props.id, field: props.field });
      }}
    />
  );
}
