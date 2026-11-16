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
import { MemoryRouter } from "react-router";

import { LocationProbe, probedPath } from "@iap/frontend-commons/location.fixture";
import SubmissionActions from "@iap/submissions/SubmissionActions";

const PATH = "/Submissions/ab/cd/ef/0a1b2c3d-0000-0000-0000-000000000000";

function renderActions(props: { path?: string; title?: string; onDeleted?: () => void } = { path: PATH }) {
  return render(
    <MemoryRouter initialEntries={[ "/" ]}>
      <SubmissionActions {...props} />
      <LocationProbe />
    </MemoryRouter>,
  );
}

describe("SubmissionActions", () => {
  it("offers viewing, editing and deleting the submission", () => {
    renderActions();

    expect(screen.getByRole("button", { name: "View" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit" })).toBeInTheDocument();
    // DeleteItem's trigger, which it labels itself
    expect(screen.getByRole("button", { name: /Delete/i })).toBeInTheDocument();
  });

  it("opens the submission when viewing it", async () => {
    renderActions();

    await userEvent.click(screen.getByRole("button", { name: "View" }));

    expect(probedPath()).toBe(PATH);
  });

  it("opens the submission for editing, at its own address", async () => {
    renderActions();

    await userEvent.click(screen.getByRole("button", { name: "Edit" }));

    expect(probedPath()).toBe(`${PATH}.edit`);
  });

  it("renders nothing for a row with no path to act on", () => {
    renderActions({});

    expect(screen.queryByRole("button", { name: "View" })).not.toBeInTheDocument();
  });
});
