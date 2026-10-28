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

import { APPROVAL_REQUIREMENT_TYPE, type ApprovalRequirement } from "@iap/submissions/submissionForm";

// What an undecided approval says
export const NEEDS_APPROVAL = "Needs approval from time-off-approvers";

// An approval requirement as the form projection serves it, undecided unless a test says otherwise
export function approval(state: Partial<ApprovalRequirement> = {}): ApprovalRequirement {
  return {
    name: "approval", path: "/Schemas/timeOffRequest/v1/approval", type: APPROVAL_REQUIREMENT_TYPE,
    label: "Approval", approverGroup: "time-off-approvers", ...state,
  };
}
