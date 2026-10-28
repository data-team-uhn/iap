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

import { type ApprovalRequirement, formatDate } from "./submissionForm";

// Where an approval stands: who it waits on, or what they decided.
function ApprovalState({ requirement }: { requirement: ApprovalRequirement }) {
  const { approverGroup, approved, decidedBy, decidedAt } = requirement;
  if (decidedBy === undefined) {
    return (
      <Typography variant="description">
        {approverGroup ? `Waiting for approval from ${approverGroup}` : "Waiting for approval"}
      </Typography>
    );
  }
  const when = decidedAt ? ` on ${formatDate(decidedAt)}` : "";
  return approved
    ? <Typography variant="body2" color="success">{`Approved by ${decidedBy}${when}`}</Typography>
    : <Typography variant="description">{`Reviewed by ${decidedBy}${when}, and not approved`}</Typography>;
}

export default ApprovalState;
