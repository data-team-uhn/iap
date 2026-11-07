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

import { Box, Link, Stack, Typography } from "@mui/material";
import {
  DataGridPro, GRID_TREE_DATA_GROUPING_FIELD, GridTreeDataGroupingCell, type GridColDef, type GridGroupNode,
  type GridRenderCellParams,
} from "@mui/x-data-grid-pro";

import EntityGridToolbar from "@iap/frontend-commons/entityGrid/EntityGridToolbar";
import TagChip from "@iap/tags/TagChip";

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
  risk: "", contradiction: "Contradiction", concern: "Concern", dismissed: "Dismissed", adopted: "Made an issue",
};

// PRETOTYPE. Everything the AI pre-review found, as a table: grouped by risk, each risk's row carrying the AI's read
// of it and folding over what it found under it, the answers the protocol contradicts and its concerns; or, not
// grouped, one list of findings across risks, to sort most serious first. Settled concerns show at the reviewer's
// asking, greyed. Concerns are made issues of or dismissed from their row, a dismissal taken back.
export default function AiGrid({ risks, grouped, settled, about, onShow, onMakeIssue, onDismiss, onUndismiss }: {
  risks: RiskReview[];
  grouped: boolean;
  settled: boolean;
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
  }), [ risks, grouped, settled, about ]);

  const columns: GridColDef<AiRow>[] = [
    ...grouped ? [] : [ { field: "risk", headerName: "Risk", width: 150 } ],
    {
      field: "severity", headerName: "Severity", width: 120,
      sortComparator: (a: string, b: string) => rank(a) - rank(b),
      renderCell: ({ row }) => (row.severity ? <TagChip tags={[ row.severity ]} category="severity" /> : null),
    },
    ...grouped ? [] : [ {
      field: "kind", headerName: "Finding", width: 110,
      valueFormatter: (value: Kind) => KIND_NAMES[value],
    } ],
    { field: "about", headerName: "About", width: 170 },
    {
      field: "finding", headerName: "What the AI says", flex: 1, minWidth: 240,
      renderCell: ({ row }) => (
        <Box sx={{ whiteSpace: "pre-wrap", ...row.kind === "risk" ? { color: "text.secondary" } : {} }}>{row.finding}</Box>
      ),
    },
    {
      field: "actions", headerName: "", width: 140, sortable: false, filterable: false,
      renderCell: ({ row }) => row.note && (
        <FindingActions kind={row.kind} note={row.note} onShow={onShow} onMakeIssue={onMakeIssue} onDismiss={onDismiss}
          onUndismiss={onUndismiss} />
      ),
    },
  ];


  return (
    <DataGridPro
      rows={rows} columns={columns} density="compact" disableRowSelectionOnClick hideFooter
      treeData={grouped} getTreeDataPath={row => row.path} defaultGroupingExpansionDepth={-1}
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
      slotProps={{ toolbar: { searchLabel: "Search what the AI found" } }}
      getRowHeight={() => "auto"}
      getRowClassName={({ row }) => [ row.kind === "risk" ? "ai-risk" : "",
        row.kind === "dismissed" || row.kind === "adopted" ? "ai-settled" : "" ].join(" ")}
      initialState={{ sorting: { sortModel: [ { field: "severity", sort: "asc" } ] },
        pinnedColumns: { right: [ "actions" ] } }}
      localeText={{ noRowsLabel: "The AI found nothing to raise", noResultsOverlayLabel: "Nothing found matches" }}
      sx={theme => ({ bgcolor: "background.paper", "& .MuiDataGrid-cell": { py: 0.75, alignItems: "center" },
        "& .ai-risk": { bgcolor: theme.alpha(theme.palette.assistant.light, 0.12) },
        "& .ai-settled": { color: "text.secondary", opacity: 0.75 },
        [`& [data-field="${GRID_TREE_DATA_GROUPING_FIELD}"]`]: { fontWeight: "fontWeightMedium" } })}
    />
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

// What can be done with a finding, from its row: a concern made an issue of or dismissed, a dismissal taken back,
// and where it was raised shown
function FindingActions({ kind, note, onShow, onMakeIssue, onDismiss, onUndismiss }: {
  kind: Kind;
  note: ReviewNote;
  onShow: (note: ReviewNote) => void;
  onMakeIssue: (concern: ReviewNote) => Promise<void>;
  onDismiss: (concern: ReviewNote) => Promise<void>;
  onUndismiss: (concern: ReviewNote) => Promise<void>;
}) {
  return (
    <Stack sx={{ alignItems: "flex-end", width: "100%" }}>
      {kind === "concern" && (
        <>
          <Link component="button" variant="body2" onClick={() => void onMakeIssue(note)}>Make it an issue</Link>
          <Link component="button" variant="body2" onClick={() => void onDismiss(note)}>Dismiss</Link>
        </>
      )}
      {kind === "dismissed" && (
        <Link component="button" variant="body2" onClick={() => void onUndismiss(note)}>Undo</Link>
      )}
      {(note.subjects.length > 0 || note.passage) && (
        <Link component="button" variant="body2" onClick={() => onShow(note)}>Show where</Link>
      )}
    </Stack>
  );
}
