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
import { Typography } from "@mui/material";

import { type FormRequirement, formatDate } from "./submissionForm";

// Where an approval stands. Nobody grants one from a form, so the useful thing to say is who it waits
// on and what they decided — a section that said only "not here" reads the same as a part of the form
// that is broken.
//
// A review that did not approve is still a decision, and is reported as one: silence about it would
// leave a refused request looking like one nobody had looked at yet.
//
// Shared by the editor and the read-only view because a request waiting on somebody else is exactly
// as worth saying in both, and two renderings of the same three states would drift.
export default function ApprovalState({ requirement }: { requirement: FormRequirement }) {
  const decided = requirement.decidedBy
    ? `${requirement.approved ? "Approved" : "Reviewed"} by ${requirement.decidedBy}`
      + (requirement.decidedAt ? ` on ${formatDate(requirement.decidedAt)}` : "")
      + (requirement.approved ? "" : ", and not approved")
    : undefined;
  return (
    <Typography variant="body2" color={requirement.approved ? "success.main" : "text.secondary"}>
      {decided ?? (requirement.approverGroup
        ? `Waiting for approval from ${requirement.approverGroup}`
        : "Waiting for approval")}
    </Typography>
  );
}
