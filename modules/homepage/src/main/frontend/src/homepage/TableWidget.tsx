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

import { Table, TableBody, TableCell, TableHead, TableRow } from "@mui/material";

// A dashboard widget with dense, wide, tabular content — a small table of proposals — used to
// exercise how the dashboard tiles content-heavy widgets. The titled frame comes from the dashboard.
function TableWidget() {
  const rows = [
    { id: "PRO-1042", title: "Genomic markers in early-onset diabetes", status: "Approved", updated: "2026-07-16" },
    { id: "PRO-1041", title: "AI-assisted triage in the emergency department", status: "In review", updated: "2026-07-15" },
    { id: "PRO-1038", title: "Longitudinal cohort of post-transplant outcomes", status: "In review", updated: "2026-07-14" },
    { id: "PRO-1035", title: "Wearable data for cardiac rehabilitation", status: "Draft", updated: "2026-07-12" },
    { id: "PRO-1031", title: "Health equity in remote oncology follow-up", status: "Approved", updated: "2026-07-09" },
  ];

  return (
    <Table size="small">
      <TableHead>
        <TableRow>
          <TableCell>ID</TableCell>
          <TableCell>Title</TableCell>
          <TableCell>Status</TableCell>
          <TableCell align="right">Updated</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {rows.map(row => (
          <TableRow key={row.id}>
            <TableCell>{row.id}</TableCell>
            <TableCell>{row.title}</TableCell>
            <TableCell>{row.status}</TableCell>
            <TableCell align="right">{row.updated}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

export default TableWidget;
