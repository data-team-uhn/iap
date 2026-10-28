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

import { NEEDS_APPROVAL, approval } from "./approvals.fixture";

const DECIDED_AT = "2026-08-27T09:15:30.500-05:00";

describe("ApprovalState", () => {
  it("says who an approval needs while nobody has decided", () => {
    render(<ApprovalState requirement={approval()} />);

    expect(screen.getByText(NEEDS_APPROVAL)).toBeInTheDocument();
  });

  it("says only that it needs approval when no group is named", () => {
    render(<ApprovalState requirement={approval({ approverGroup: "" })} />);

    expect(screen.getByText("Needs approval")).toBeInTheDocument();
  });

  it("says who approved it, and when", () => {
    render(<ApprovalState requirement={approval({ decision: { approved: true, by: "priya", at: DECIDED_AT } })} />);

    expect(screen.getByText(`Approved by priya on ${new Date(DECIDED_AT).toLocaleString()}`)).toBeInTheDocument();
  });

  it("says who rejected it", () => {
    render(<ApprovalState requirement={approval({ decision: { approved: false, by: "priya" } })} />);

    expect(screen.getByText("Rejected by priya")).toBeInTheDocument();
  });
});
