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
// PRETOTYPE. The REB's issue library as a table: every standard question of the reviewer's risks, with what became of
// it in this review, raised by the AI, by the reviewer, or not yet; one not raised by the reviewer is raised from here
// in the board's wording, which they can change first; one the AI raised is made an issue from its finding.

import { useState } from "react";

import AddOutlined from "@mui/icons-material/AddOutlined";
import { Box, DialogContent, Link, Stack, Typography } from "@mui/material";
import { DataGridPro, type GridSortModel } from "@mui/x-data-grid-pro";

import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";
import EntityGridToolbar from "@iap/frontend-commons/entityGrid/EntityGridToolbar";
import useColumnVisibility from "@iap/frontend-commons/entityGrid/useColumnVisibility";
import { useNarrowerThan } from "@iap/frontend-commons/useNarrowerThan";
import TagChip from "@iap/tags/TagChip";

import Composer from "./Composer";
import { CARDS_BELOW, type CardColumn, cardOf } from "./IssuesGrid";
import NoteKindIcon from "./noteIcons";
import PolicyCitation from "./PolicyCitation";
import { SEVERITIES, type StandardQuestion } from "./reviewData";

import type { ReviewDoing, RiskReview } from "./ReviewPanel";

type Status = "mine" | "ai" | "open";

interface LibraryRow {
  id: string;
  risk: string;
  riskPath: string;
  order: number;
  rank: number;
  text: string;
  citation: string;
  status: Status;
  // What raised it, the reviewer's issue or the AI's finding, by path
  raisedAs?: string;
  entry: StandardQuestion;
}

const STATUS_NAMES: Record<Status, string> = { mine: "Raised by you", ai: "Raised by the AI", open: "Not raised" };
// What is left to consider first
const STATUSES: Status[] = [ "open", "ai", "mine" ];

// The entries, in the risks' order, each with what became of it: raised by the reviewer, whose issue keeps it, or by the
// AI, whose finding names it
export function libraryRows(risks: RiskReview[], mine: { id: string; standard?: string }[]): LibraryRow[] {
  return risks.flatMap((risk, riskIndex) => (risk.topic.standards ?? []).map((entry, index) => {
    const byMe = mine.find(note => note.standard === entry.path);
    const byAi = [ ...risk.aiOpen, ...risk.aiSettled ].find(note => note.standard === entry.path);
    const severity = entry.nature === "required-change" ? SEVERITIES.indexOf(entry.severity as typeof SEVERITIES[number])
      : -1;
    return { id: entry.path, risk: risk.topic.label, riskPath: risk.topic.path, order: riskIndex * 100 + index,
      rank: severity < 0 ? SEVERITIES.length : severity, text: entry.text, citation: entry.citation ?? "",
      status: byMe ? "mine" : byAi ? "ai" : "open", raisedAs: (byMe ?? byAi)?.id, entry };
  }));
}

function Kind({ entry }: { entry: StandardQuestion }) {
  return entry.nature === "required-change" && entry.severity
    ? <TagChip tags={[ entry.severity ]} category="severity" />
    : <TagChip tags={[ entry.nature ]} category="issue-kind" />;
}

export default function LibraryGrid({ rows, fitRows = false, doing, onOpen }: {
  rows: LibraryRow[];
  fitRows?: boolean;
  doing: ReviewDoing;
  // Where what raised a question is listed in the review, by path
  onOpen: (id: string) => void;
}) {
  const [ element, setElement ] = useState<HTMLDivElement | null>(null);
  const cards = useNarrowerThan(element, CARDS_BELOW);
  const [ visibility, setVisibility ] = useColumnVisibility("review/library");
  const [ sortModel, setSortModel ] = useState<GridSortModel>([ { field: "order", sort: "asc" } ]);
  const [ raising, setRaising ] = useState<LibraryRow>();

  // What the AI raised is made an issue from its finding, so it keeps what the finding is about
  // Where it stands, in one place: not raised, the way to raise it; raised, what raised it, marked and coloured as that
  // is everywhere, the AI pre-review's icon in its ink or the reviewer's issue flag in theirs, leading to it
  const standing = (row: LibraryRow) => {
    if (row.status === "open" || !row.raisedAs) {
      return (
        <Link component="button" variant="body2" onClick={() => setRaising(row)}
          sx={{ display: "inline-flex", alignItems: "center", gap: 0.5, fontWeight: "fontWeightMedium" }}>
          <AddOutlined fontSize="small" />Raise as an issue
        </Link>
      );
    }
    const ink = row.status === "ai" ? "assistant.main" : "issue.main";
    const raisedAs = row.raisedAs;
    return (
      <Stack direction="row" sx={{ alignItems: "center", gap: 0.5 }}>
        <NoteKindIcon kind={row.status === "ai" ? "aiIssue" : "issue"} fontSize="small" sx={{ color: ink }} />
        <Link component="button" variant="body2" onClick={() => onOpen(raisedAs)}
          sx={{ color: ink, textDecorationColor: "currentColor" }}>
          {STATUS_NAMES[row.status]}
        </Link>
      </Stack>
    );
  };
  const columns: CardColumn<LibraryRow>[] = [
    { field: "order", headerName: "Order", width: 80, cardSlot: "omit" as const },
    { field: "risk", headerName: "Risk", width: 170, cardSlot: "caption" as const,
      sortComparator: (_a: string, _b: string, one, other) => (rows.find(row => row.id === one.id)?.order ?? 0)
        - (rows.find(row => row.id === other.id)?.order ?? 0) },
    { field: "rank", headerName: "Type", width: 130, cardSlot: "title" as const,
      renderCell: ({ row }) => <Kind entry={row.entry} />, cardValue: row => <Kind entry={row.entry} /> },
    { field: "text", headerName: "Question", flex: 1, minWidth: 240, cardSlot: "body" as const,
      renderCell: ({ row }) => <Box sx={{ whiteSpace: "pre-wrap" }}>{row.text}</Box> },
    { field: "citation", headerName: "Policy", width: 160, cardSlot: "caption" as const,
      renderCell: ({ row }) => <PolicyCitation citation={row.citation} />,
      cardValue: row => <PolicyCitation citation={row.citation} /> },
    { field: "status", headerName: "In this review", width: 180, cardSlot: "actions" as const,
      valueFormatter: (value: Status) => STATUS_NAMES[value],
      sortComparator: (one: Status, other: Status) => STATUSES.indexOf(one) - STATUSES.indexOf(other),
      renderCell: ({ row }) => standing(row), cardValue: row => standing(row) },
  ];
  const shownColumns = columns.filter(column => (visibility[column.field] as boolean | undefined) !== false);

  return (
    <Box ref={setElement} sx={{ width: "100%", height: fitRows ? undefined : "100%" }}>
      <DataGridPro
        rows={rows} columns={columns} density="compact" disableRowSelectionOnClick hideFooter autoHeight={fitRows}
        listView={cards} listViewColumn={{ field: "__card__", renderCell: ({ row }) => cardOf(row, shownColumns) }}
        columnVisibilityModel={{ order: false, ...visibility }} onColumnVisibilityModelChange={setVisibility}
        sortModel={sortModel} onSortModelChange={setSortModel}
        showToolbar slots={{ toolbar: EntityGridToolbar }}
        slotProps={{ toolbar: { searchLabel: "Search the issue library", showSortMenu: cards, sortModel,
          onSortModelChange: setSortModel, sortableColumns: shownColumns.filter(column => column.sortable !== false)
            .map(column => ({ field: column.field, headerName: column.headerName })) } }}
        getRowHeight={() => "auto"}
        getRowClassName={({ row }) => (row.status === "open" ? "" : "library-raised")}
        initialState={{ pinnedColumns: { right: [ "status" ] } }}
        localeText={{ noRowsLabel: "Every standard question has been raised", noResultsOverlayLabel: "Nothing found matches" }}
        // Raised, quieter: what is left to consider stands out, while what raised it can still be followed
        sx={{ bgcolor: "background.paper", "& .MuiDataGrid-cell": { py: 0.75, alignItems: "center" },
          "& .library-raised": { bgcolor: "action.hover" },
          "& .library-raised .MuiDataGrid-cell:not([data-field='status'])": { color: "text.secondary" },
          "& .library-raised .MuiChip-root": { opacity: 0.6 } }}
      />
      {raising && (
        <ResponsiveDialog open width="sm" title="Raise as an issue" withCloseButton onClose={() => setRaising(undefined)}>
          <DialogContent>
            <Typography variant="body2" sx={{ color: "text.secondary", mb: 1.5 }}>
              In the board&apos;s wording, under {raising.risk}. Change anything before adding it.
            </Typography>
            <Composer
              kinds={[ "issue" ]} initialText={raising.entry.text} initialNature={raising.entry.nature}
              initialSeverity={raising.entry.severity} initialTopic={raising.riskPath}
              initialCitation={raising.entry.citation} saveLabel="Add issue" onCancel={() => setRaising(undefined)}
              onSave={async (_kind, text, severity, topic, nature, citation) => {
                await doing.addIssue(topic === "" ? undefined : topic, text, severity, nature,
                  { citation, standard: raising.entry.path });
                setRaising(undefined);
              }}
            />
          </DialogContent>
        </ResponsiveDialog>
      )}
    </Box>
  );
}
