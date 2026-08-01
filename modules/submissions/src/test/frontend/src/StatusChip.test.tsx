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

import StatusChip from "@iap/submissions/StatusChip";

describe("StatusChip", () => {
  it("tints known lifecycle states", () => {
    render(<StatusChip value="approved" />);
    expect(screen.getByText("approved").closest(".MuiChip-root")).toHaveClass("MuiChip-colorSuccess");
  });

  it("falls back to a plain chip for states it does not know", () => {
    render(<StatusChip value="on-hold" />);
    expect(screen.getByText("on-hold").closest(".MuiChip-root")).toHaveClass("MuiChip-colorDefault");
  });

  it("renders nothing when there is no state to show", () => {
    // The value comes straight out of a serialized node, so anything that is not a non-empty
    // string counts as absent
    const { container } = render(
      <>
        <StatusChip />
        <StatusChip value="" />
        <StatusChip value={42} />
        <StatusChip value={{ complex: "state" }} />
      </>
    );
    expect(container).toBeEmptyDOMElement();
  });
});
