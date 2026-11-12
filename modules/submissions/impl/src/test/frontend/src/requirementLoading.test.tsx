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

import { act, render, screen } from "@testing-library/react";

import {
  registerRequirementComponent,
  type RequirementComponent,
} from "@iap/submissions/requirementComponents";
import SubmissionEditor from "@iap/submissions/SubmissionEditor";
import type { SubmissionForm } from "@iap/submissions/submissionForm";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

vi.mock("@iap/ui-extension/extensionManager", () => ({
  loadExtensions: vi.fn(),
}));

const mockedLoadExtensions = vi.mocked(loadExtensions);

const PATH = "/Submissions/ab/cd/ef/0a1b2c3d-0000-0000-0000-000000000000";

const FORM: SubmissionForm = {
  path: PATH,
  title: "A long weekend",
  editable: true,
  requirements: [ { name: "thing", path: "/Schemas/demo/v1/thing", type: "test/Thing", label: "Thing" } ],
};

const Thing: RequirementComponent = () => <p>Drawn by another module</p>;

// What the contributed asset does as it is evaluated
function contribute(): void {
  registerRequirementComponent(requirement => (requirement.type === "test/Thing" ? [ Thing, 50 ] : null));
}

function servingTheForm() {
  return vi.fn(() => Promise.resolve({
    ok: true,
    status: 200,
    url: "",
    json: () => Promise.resolve(FORM),
  } as unknown as Response));
}

// The loader keeps its result for the life of the module, so this file holds one case.
describe("drawing a requirement whose component another module contributes", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("waits for the components declared on the RequirementComponent point", async () => {
    let land: () => void = () => undefined;
    mockedLoadExtensions.mockImplementation(() => new Promise(resolve => {
      land = () => {
        contribute();
        resolve([]);
      };
    }));
    const fetchMock = servingTheForm();
    vi.stubGlobal("fetch", fetchMock);

    render(<SubmissionEditor path={PATH} />);
    // Every pending read settles, leaving only the components on their way
    await act(() => new Promise(resolve => setTimeout(resolve, 0)));

    expect(fetchMock).toHaveBeenCalled();
    expect(mockedLoadExtensions).toHaveBeenCalledWith("RequirementComponent");
    expect(screen.getByRole("progressbar", { name: "Loading the request" })).toBeInTheDocument();
    expect(screen.queryByText(/cannot be completed here/)).toBeNull();

    act(land);

    expect(await screen.findByText("Drawn by another module")).toBeInTheDocument();
  });
});
