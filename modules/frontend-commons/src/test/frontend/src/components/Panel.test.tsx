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

import Panel from "@iap/frontend-commons/components/Panel";

describe("Panel", () => {
  it("renders its content on a borderless paper surface", () => {
    render(<Panel><span>content</span></Panel>);

    const surface = screen.getByText("content").closest(".MuiPaper-root");
    expect(surface).not.toBeNull();
    expect(getComputedStyle(surface!).borderStyle).toBe("none");
  });

  it("renders no header without a title, subtitle or action", () => {
    render(<Panel><span>content</span></Panel>);

    expect(screen.queryByRole("heading")).toBeNull();
  });

  it("renders the title, subtitle and action in its header", () => {
    render(
      <Panel title="Details" subtitle="What was recorded" action={<button>Edit</button>}>
        <span>content</span>
      </Panel>
    );

    expect(screen.getByRole("heading", { name: "Details" })).toBeInTheDocument();
    expect(screen.getByText("What was recorded")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit" })).toBeInTheDocument();
  });

  it("renders a header for an action alone", () => {
    render(<Panel action={<button>Edit</button>}><span>content</span></Panel>);

    expect(screen.getByRole("button", { name: "Edit" })).toBeInTheDocument();
    expect(screen.queryByRole("heading")).toBeNull();
  });
});
