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

import { formatDate } from "@iap/frontend-commons/dates";

import { type ApprovalRequirement } from "./submissionForm";

// Where an approval stands: who it needs, or what they decided.
function ApprovalState({ requirement }: { requirement: ApprovalRequirement }) {
  const { approverGroup, decision } = requirement;
  if (decision === undefined) {
    return (
      <Typography variant="description">
        {approverGroup ? `Needs approval from ${approverGroup}` : "Needs approval"}
      </Typography>
    );
  }
  const when = decision.at ? ` on ${formatDate(decision.at)}` : "";
  return decision.approved
    ? <Typography variant="body2" color="success">{`Approved by ${decision.by}${when}`}</Typography>
    : <Typography variant="description">{`Rejected by ${decision.by}${when}`}</Typography>;
}

export default ApprovalState;
