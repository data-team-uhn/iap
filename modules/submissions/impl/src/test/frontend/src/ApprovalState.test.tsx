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

import { render, screen } from "@testing-library/react";

import ApprovalState from "@iap/submissions/ApprovalState";
import { type Requirement } from "@iap/submissions/submissionForm";

function show(overrides: Omit<Partial<Requirement>, "label">) {
  render(<ApprovalState requirement={{
    name: "approval", type: "sch/ApprovalRequirement", label: "Approval", ...overrides, }} />);
}

describe("ApprovalState", () => {
  it("says who approved it, and when", () => {
    show({ decidedBy: "a-reviewer", approved: true, decidedAt: "2026-10-01T10:00:00.000Z" });

    expect(screen.getByText(/^Approved by a-reviewer on /)).toBeInTheDocument();
  });

  // A review that did not approve is still a decision
  it("says who reviewed it without approving", () => {
    show({ decidedBy: "a-reviewer", approved: false });

    expect(screen.getByText("Reviewed by a-reviewer, and not approved")).toBeInTheDocument();
  });

  it("says whose approval it waits for", () => {
    show({ approverGroup: "ethics-board" });

    expect(screen.getByText("Waiting for approval from ethics-board")).toBeInTheDocument();
  });

  it("says it waits even when nobody is named", () => {
    show({});

    expect(screen.getByText("Waiting for approval")).toBeInTheDocument();
  });
});
