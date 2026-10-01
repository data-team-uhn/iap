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

import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import CodePill from "@iap/schemas/CodePill";

// Which the test environment does not lay out: how wide the pill's text is, and how wide it may be
const widths = (scroll: number, client: number) => {
  vi.spyOn(HTMLElement.prototype, "scrollWidth", "get").mockReturnValue(scroll);
  vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockReturnValue(client);
};

afterEach(() => vi.restoreAllMocks());

describe("CodePill", () => {
  it("shows a name cut short whole when hovered", async () => {
    widths(200, 120);
    render(<CodePill name="participatingSites" />);

    const pill = screen.getByText("participatingSites");
    fireEvent.mouseOver(pill);

    expect(await screen.findByRole("tooltip")).toHaveTextContent("participatingSites");
    fireEvent.mouseLeave(pill);
    await waitFor(() => expect(screen.queryByRole("tooltip")).not.toBeInTheDocument());
  });

  it("adds nothing to a name shown whole", () => {
    widths(80, 120);
    render(<CodePill name="sites" />);

    fireEvent.mouseOver(screen.getByText("sites"));

    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
  });
});
