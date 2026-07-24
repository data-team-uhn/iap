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

import { Chip } from "@mui/material";

// How each lifecycle state (of a submission, or of a review) is tinted; unknown states fall
// back to a plain chip.
const STATUS_COLORS: Record<string, "default" | "info" | "warning" | "success" | "error"> = {
  "draft": "default",
  "submitted": "info",
  "in-progress": "info",
  "in-review": "warning",
  "changes-requested": "warning",
  "approved": "success",
  "rejected": "error",
};

// A small colored chip displaying a submission or review lifecycle state, used both in the
// submission grids and in the submission view. Renders nothing when there is no state to show.
function StatusChip({ value }: { value?: unknown }) {
  return value
    ? <Chip size="small" label={String(value)} color={STATUS_COLORS[String(value)] ?? "default"} />
    : null;
}

export default StatusChip;
