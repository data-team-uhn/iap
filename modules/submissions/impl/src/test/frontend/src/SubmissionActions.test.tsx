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
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";

import SubmissionActions from "@iap/submissions/SubmissionActions";

const PATH = "/Submissions/ab/cd/ef/0a1b2c3d-0000-0000-0000-000000000000";

// Reports where the router ended up, so navigation can be asserted on rather than mocked
function Whereabouts() {
  return <span data-testid="where">{useLocation().pathname}</span>;
}

function renderActions(props: { path?: string; title?: string; onDeleted?: () => void } = { path: PATH }) {
  return render(
    <MemoryRouter initialEntries={[ "/" ]}>
      <SubmissionActions {...props} />
      <Routes><Route path="*" element={<Whereabouts />} /></Routes>
    </MemoryRouter>,
  );
}

describe("SubmissionActions", () => {
  it("offers viewing, editing and deleting the submission", () => {
    renderActions();

    expect(screen.getByRole("button", { name: "View" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit" })).toBeInTheDocument();
    // DeleteItem's own trigger, which it labels for itself
    expect(screen.getByRole("button", { name: /Delete/i })).toBeInTheDocument();
  });

  it("opens the submission when viewing it", async () => {
    renderActions();

    await userEvent.click(screen.getByRole("button", { name: "View" }));

    expect(screen.getByTestId("where")).toHaveTextContent(PATH);
  });

  it("opens the submission for editing, at its own address", async () => {
    // The same page asked for by extension, the way every other view here is addressed. Whether it
    // can actually be edited is the server's answer, given by the form it serves.
    renderActions();

    await userEvent.click(screen.getByRole("button", { name: "Edit" }));

    expect(screen.getByTestId("where")).toHaveTextContent(`${PATH}.edit`);
  });

  it("renders nothing for a row with no path to act on", () => {
    renderActions({});

    expect(screen.queryByRole("button", { name: "View" })).not.toBeInTheDocument();
  });

  it("keeps its clicks away from the row around it", async () => {
    // Every row in the grid is a link to the submission, so a control inside one has to stop the click
    // from also being a click on the row
    const rowClick = vi.fn();
    render(
      <MemoryRouter>
        { /* eslint-disable-next-line jsx-a11y/no-static-element-interactions, jsx-a11y/click-events-have-key-events */ }
        <div onClick={rowClick}>
          <SubmissionActions path={PATH} />
        </div>
      </MemoryRouter>,
    );

    await userEvent.click(screen.getByRole("button", { name: "View" }));

    expect(rowClick).not.toHaveBeenCalled();
  });
});
