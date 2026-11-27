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

import { useMemo, useState } from "react";

import { Box, Link, Stack, Typography } from "@mui/material";
import {
  DataGridPro, GRID_TREE_DATA_GROUPING_FIELD, GridTreeDataGroupingCell, type GridGroupNode,
  type GridRenderCellParams, type GridSortModel,
} from "@mui/x-data-grid-pro";

import EntityGridToolbar from "@iap/frontend-commons/entityGrid/EntityGridToolbar";
import useColumnVisibility from "@iap/frontend-commons/entityGrid/useColumnVisibility";
import { useNarrowerThan } from "@iap/frontend-commons/useNarrowerThan";
import TagChip from "@iap/tags/TagChip";

import { CARDS_BELOW, type CardColumn, WhereLink, cardOf } from "./IssuesGrid";
import { SEVERITIES, type ReviewNote } from "./reviewData";

import type { RiskReview } from "./ReviewPanel";

type Kind = "risk" | "contradiction" | "concern" | "dismissed" | "adopted";

interface AiRow {
  id: string;
  path: string[];
  kind: Kind;
  risk: string;
  severity: string;
  about: string;
  // The AI's read of a risk, or what one of its findings says
  finding: string;
  note?: ReviewNote;
}

const KIND_NAMES: Record<Kind, string> = {
  risk: "", contradiction: "Mismatch", concern: "Concern", dismissed: "Dismissed", adopted: "Made an issue",
};

// PRETOTYPE. Everything the AI pre-review found, as a table: grouped by risk, each risk's row carrying the AI's read
// of it and folding over what it found under it, the answers the protocol contradicts and its concerns; or, not
// grouped, one list of findings across risks, to sort most serious first. Settled concerns show at the reviewer's
// asking, greyed. Concerns are made issues of or dismissed from their row, a dismissal taken back.
export default function AiGrid({ risks, grouped, settled, fitRows = false, about, onShow, onMakeIssue, onDismiss,
  onUndismiss }: {
  risks: RiskReview[];
  grouped: boolean;
  settled: boolean;
  // As tall as its rows, rather than the height it is given
  fitRows?: boolean;
  about: (note: ReviewNote) => string;
  onShow: (note: ReviewNote) => void;
  onMakeIssue: (concern: ReviewNote) => Promise<void>;
  onDismiss: (concern: ReviewNote) => Promise<void>;
  onUndismiss: (concern: ReviewNote) => Promise<void>;
}) {
  const rows: AiRow[] = useMemo(() => risks.flatMap(risk => {
    const label = risk.topic.label;
    const findings: AiRow[] = [
      ...risk.mismatches.map(mismatch => ({
        id: `${risk.topic.path}|${mismatch.evidence.id}`, path: [ label, mismatch.evidence.id ],
        kind: "contradiction" as const, risk: label, severity: "", about: mismatch.question,
        finding: `The form says ${mismatch.answer}; the protocol says ${mismatch.protocol}.`, note: mismatch.evidence,
      })),
      ...[ ...risk.aiOpen, ...settled ? risk.aiSettled : [] ].map(note => ({
        id: note.id, path: [ label, note.id ],
        kind: kindOf(note),
        risk: label, severity: note.severity ?? "medium", about: about(note),
        finding: note.suggestion ? `${note.text}\n\n${note.suggestion}` : note.text, note,
      })),
    ];
    const group: AiRow = { id: risk.topic.path, path: [ label ], kind: "risk", risk: label, severity: "", about: "",
      finding: risk.assessment ?? "" };
    // A risk the AI said nothing of is left out
    return grouped && (group.finding || findings.length > 0) ? [ group, ...findings ] : findings;
  }).reduce<AiRow[]>((all, row) => {
    // Not grouped, a contradiction several risks draw on is one row, naming all of them
    const same = grouped || row.kind !== "contradiction" ? undefined
      : all.find(one => one.kind === "contradiction" && one.note?.id === row.note?.id);
    if (same) {
      same.risk = `${same.risk}, ${row.risk}`;
      return all;
    }
    return [ ...all, row ];
  }, []), [ risks, grouped, settled, about ]);

  // Where the grid is too narrow for its columns, each finding is a card; grouped, each risk heads its findings
  const [ element, setElement ] = useState<HTMLDivElement | null>(null);
  const cards = useNarrowerThan(element, CARDS_BELOW);
  const [ visibility, setVisibility ] = useColumnVisibility("review/aiFindings");
  const [ sortModel, setSortModel ] = useState<GridSortModel>([ { field: "severity", sort: "asc" } ]);

  const columns: CardColumn<AiRow>[] = [
    ...grouped ? [] : [ { field: "risk", headerName: "Risk", width: 150, cardSlot: "caption" as const } ],
    {
      field: "severity", headerName: "Severity", width: 120,
      // How serious, and what kind of finding, lead the card
      cardSlot: "title",
      cardValue: row => (
        <Stack direction="row" sx={{ alignItems: "center", gap: 1 }}>
          {row.severity && <TagChip tags={[ row.severity ]} category="severity" />}
          <Typography variant="caption" sx={{ color: "text.secondary" }}>{KIND_NAMES[row.kind]}</Typography>
        </Stack>
      ),
      sortComparator: (a: string, b: string) => rank(a) - rank(b),
      renderCell: ({ row }) => (row.severity ? <TagChip tags={[ row.severity ]} category="severity" /> : null),
    },
    ...grouped ? [] : [ {
      field: "kind", headerName: "Finding", width: 110,
      valueFormatter: (value: Kind) => KIND_NAMES[value],
      cardSlot: "omit" as const,
    } ],
    {
      field: "about", headerName: "About", width: 170,
      renderCell: ({ row }) => row.note && (row.note.subjects.length > 0 || row.note.passage)
        ? <WhereLink text={row.about} onClick={() => row.note && onShow(row.note)} />
        : row.about,
      cardSlot: "caption",
      cardValue: row => row.note && (row.note.subjects.length > 0 || row.note.passage)
        ? <WhereLink text={row.about} variant="inherit" onClick={() => row.note && onShow(row.note)} />
        : row.about,
    },
    {
      field: "finding", headerName: "What the AI says", flex: 1, minWidth: 240, cardSlot: "body",
      renderCell: ({ row }) => (
        <Box sx={{ whiteSpace: "pre-wrap", ...row.kind === "risk" ? { color: "text.secondary" } : {} }}>{row.finding}</Box>
      ),
    },
    {
      field: "actions", headerName: "", width: 140, sortable: false, filterable: false, cardSlot: "actions",
      renderCell: ({ row }) => row.note && (
        <FindingActions kind={row.kind} note={row.note} onMakeIssue={onMakeIssue} onDismiss={onDismiss}
          onUndismiss={onUndismiss} />
      ),
      cardValue: row => row.note && (
        <FindingActions onCard kind={row.kind} note={row.note} onMakeIssue={onMakeIssue} onDismiss={onDismiss}
          onUndismiss={onUndismiss} />
      ),
    },
  ];


  // A column absent from the model is shown; the model's index type hides the undefined
  const shownColumns = columns.filter(column => (visibility[column.field] as boolean | undefined) !== false);
  // A risk's own card is its name and the AI's read of it
  const card = (row: AiRow) => (row.kind === "risk" ? (
    <Stack spacing={0.5} sx={{ py: 1, width: "100%" }}>
      <Typography variant="subtitle2" component="div">{row.risk}</Typography>
      {row.finding && <Typography variant="body2" sx={{ color: "text.secondary" }}>{row.finding}</Typography>}
    </Stack>
  ) : cardOf(row, shownColumns));
  // Grouped on cards, the findings stay under their risk, in the order the risks come
  const inOrder = grouped && cards;

  return (
    <Box ref={setElement} sx={{ width: "100%", height: fitRows ? undefined : "100%" }}>
      <DataGridPro
        rows={rows} columns={columns} density="compact" disableRowSelectionOnClick hideFooter autoHeight={fitRows}
        listView={cards} listViewColumn={{ field: "__card__", renderCell: ({ row }) => card(row) }}
        columnVisibilityModel={visibility} onColumnVisibilityModelChange={setVisibility}
        sortModel={inOrder ? [] : sortModel} onSortModelChange={setSortModel}
        treeData={grouped && !cards} getTreeDataPath={row => row.path} defaultGroupingExpansionDepth={-1}
        groupingColDef={{
          headerName: "Risk", width: 260,
          // A risk's row folds over its findings; a finding's says what kind it is
          // a risk with nothing under it, only its read, still says its name
          renderCell: (params: GridRenderCellParams<AiRow>) => {
            if (params.rowNode.type === "group") {
              const group = params as GridRenderCellParams<AiRow, unknown, unknown, GridGroupNode>;
              return <GridTreeDataGroupingCell {...group} />;
            }
            return params.row.kind === "risk"
              ? <Typography variant="body2" sx={{ pl: 4, fontWeight: "fontWeightMedium" }}>{params.row.risk}</Typography>
              : <Typography variant="caption" sx={{ pl: 4, color: "text.secondary" }}>{KIND_NAMES[params.row.kind]}</Typography>;
          },
        }}
        showToolbar slots={{ toolbar: EntityGridToolbar }}
        // Cards have no headers to sort by, so the toolbar has a sort menu instead
        slotProps={{ toolbar: { searchLabel: "Search what the AI found", showSortMenu: cards && !inOrder, sortModel,
          onSortModelChange: setSortModel, sortableColumns: shownColumns.filter(column => column.sortable !== false)
            .map(column => ({ field: column.field, headerName: column.headerName })) } }}
        getRowHeight={() => "auto"}
        getRowClassName={({ row }) => [ row.kind === "risk" ? "ai-risk" : "",
          row.kind === "dismissed" || row.kind === "adopted" ? "ai-settled" : "" ].join(" ")}
        initialState={{ pinnedColumns: { right: [ "actions" ] } }}
        localeText={{ noRowsLabel: "The AI found nothing to raise", noResultsOverlayLabel: "Nothing found matches" }}
        // Plain, as the issues are: the heading and the column say it is the AI's. A risk's row is shaded solid, so its
        // pinned cell still hides what scrolls under it
        sx={theme => {
          const { background } = (theme.vars ?? theme).palette;
          const shade = `color-mix(in srgb, ${background.default}, ${background.paper})`;
          return { bgcolor: "background.paper",
            "& .MuiDataGrid-cell": { py: 0.75, alignItems: "center" },
            "& .ai-risk": { bgcolor: shade },
            "& .ai-risk .MuiDataGrid-cell--pinnedRight": { "--DataGrid-t-cell-background-pinned": shade },
            "& .ai-settled": { color: "text.secondary", opacity: 0.75 },
            [`& [data-field="${GRID_TREE_DATA_GROUPING_FIELD}"]`]: { fontWeight: "fontWeightMedium" } };
        }}
      />
    </Box>
  );
}

function rank(severity: string): number {
  const at = SEVERITIES.indexOf(severity as typeof SEVERITIES[number]);
  return at < 0 ? SEVERITIES.length : at;
}

function kindOf(note: ReviewNote): Kind {
  if (!note.settled) {
    return "concern";
  }
  return note.settled === "dismissed" ? "dismissed" : "adopted";
}

// What can be done with a finding, from its row: a concern made an issue of or dismissed, a dismissal taken back
function FindingActions({ kind, note, onCard = false, onMakeIssue, onDismiss, onUndismiss }: {
  kind: Kind;
  // On a card, side by side
  onCard?: boolean;
  note: ReviewNote;
  onMakeIssue: (concern: ReviewNote) => Promise<void>;
  onDismiss: (concern: ReviewNote) => Promise<void>;
  onUndismiss: (concern: ReviewNote) => Promise<void>;
}) {
  return (
    <Stack direction={onCard ? "row" : "column"} sx={onCard ? { gap: 1.5 } : { alignItems: "flex-end", width: "100%" }}>
      {kind === "concern" && (
        <>
          <Link component="button" variant="body2" onClick={() => void onMakeIssue(note)}>Make it an issue</Link>
          <Link component="button" variant="body2" onClick={() => void onDismiss(note)}>Dismiss</Link>
        </>
      )}
      {kind === "dismissed" && (
        <Link component="button" variant="body2" onClick={() => void onUndismiss(note)}>Undo</Link>
      )}
    </Stack>
  );
}
