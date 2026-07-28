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

import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";

import CategoryDialog, { type CategorySubmission } from "@iap/categories/CategoryDialog";
import { parseCategoryTree } from "@iap/categories/categoryModel";

// Rendering the MUI dialog tree is slow on a loaded machine, e.g. during the Maven build where
// every suite runs in parallel; the default 5s per-test budget is too tight there.
vi.setConfig({ testTimeout: 15000 });

// A typed onSave mock, so the assertions on its recorded calls stay type-safe.
const onSaveMock = () => vi.fn<(submission: CategorySubmission) => Promise<void>>()
  .mockResolvedValue(undefined);

const tree = parseCategoryTree({
  "jcr:primaryType": "cat:CategoriesHomepage",
  "Retrospective": {
    "jcr:primaryType": "cat:Category",
    "label": "Retrospective studies",
    "retired": false,
    "RetrospectiveData": {
      "jcr:primaryType": "cat:Category",
      "label": "Retrospective Data Studies",
      "retired": false,
      "schemaVersion": {
        "jcr:primaryType": "sch:SchemaVersion",
        "jcr:uuid": "uuid-sv1",
        "version": "1.0",
        "@path": "/Schemas/basic/1.0",
      },
    },
  },
  "Prospective": {
    "jcr:primaryType": "cat:Category",
    "label": "Prospective studies",
    "retired": false,
  },
});

// Answers the schema listing that the schema version picker fetches.
const stubSchemasEndpoint = () =>
  vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({
    ok: true,
    json: () => Promise.resolve({
      "jcr:primaryType": "sch:SchemasHomepage",
      "basic": {
        "jcr:primaryType": "sch:Schema",
        "title": "Basic schema",
        "1.0": {
          "jcr:primaryType": "sch:SchemaVersion",
          "jcr:uuid": "uuid-sv1",
          "version": "1.0",
          "active": true,
        },
      },
    }),
  } as unknown as Response)));

afterEach(() => vi.unstubAllGlobals());

describe("CategoryDialog", () => {
  it("rejects a label that duplicates a sibling's", async () => {
    stubSchemasEndpoint();
    render(
      <CategoryDialog
        mode="create"
        parentPath="/Categories"
        tree={tree}
        onClose={vi.fn()}
        onSave={vi.fn()}
      />
    );

    fireEvent.change(screen.getByLabelText(/Label/), { target: { value: "prospective STUDIES" } });

    expect(await screen.findByText(/already exists at the same level/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Create" })).toBeDisabled();
  });

  it("saves a new category with its fields and the preset parent", async () => {
    stubSchemasEndpoint();
    const onSave = onSaveMock();
    const onClose = vi.fn();
    render(
      <CategoryDialog
        mode="create"
        parentPath="/Categories/Prospective"
        tree={tree}
        onClose={onClose}
        onSave={onSave}
      />
    );

    fireEvent.change(screen.getByLabelText(/Label/), { target: { value: "Device trials" } });
    fireEvent.change(screen.getByLabelText(/Description/), { target: { value: "Trials of devices." } });
    fireEvent.click(screen.getByRole("button", { name: "Create" }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(onSave).toHaveBeenCalledWith({
      fields: { label: "Device trials", description: "Trials of devices.", schemaVersion: undefined },
      parentPath: "/Categories/Prospective",
    });
    // Creating from a specific parent: the parent is not editable
    expect(screen.queryByLabelText(/Parent category/)).not.toBeInTheDocument();
  });

  it("reports a parent change so the category gets moved", async () => {
    stubSchemasEndpoint();
    const onSave = onSaveMock();
    render(
      <CategoryDialog
        mode="edit"
        node={tree[1]}
        parentPath="/Categories"
        tree={tree}
        onClose={vi.fn()}
        onSave={onSave}
      />
    );

    fireEvent.mouseDown(screen.getByLabelText(/Parent category/));
    fireEvent.click(within(await screen.findByRole("listbox"))
      .getByText("Retrospective studies"));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(onSave).toHaveBeenCalled());
    expect(onSave.mock.calls[0][0].parentPath).toBe("/Categories/Retrospective");
  });

  it("explicitly unbinds the schema version when None is selected on a bound category", async () => {
    stubSchemasEndpoint();
    const onSave = onSaveMock();
    const node = tree[0].children[0];
    render(
      <CategoryDialog
        mode="edit"
        node={node}
        parentPath="/Categories/Retrospective"
        tree={tree}
        onClose={vi.fn()}
        onSave={onSave}
      />
    );

    // Wait for the schema options to load, then pick "None"
    const select = await screen.findByLabelText(/Schema version/);
    await waitFor(() => expect(select).not.toHaveAttribute("aria-disabled"));
    fireEvent.mouseDown(select);
    fireEvent.click(within(await screen.findByRole("listbox")).getByText(/None/));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(onSave).toHaveBeenCalled());
    expect(onSave.mock.calls[0][0].fields.schemaVersion).toBeNull();
  });

  it("keeps the dialog open and shows the problem when saving fails", async () => {
    stubSchemasEndpoint();
    const onSave = vi.fn().mockRejectedValue(new Error("The repository rejected the change"));
    const onClose = vi.fn();
    render(
      <CategoryDialog
        mode="create"
        parentPath="/Categories"
        tree={tree}
        onClose={onClose}
        onSave={onSave}
      />
    );

    fireEvent.change(screen.getByLabelText(/Label/), { target: { value: "Something new" } });
    fireEvent.click(screen.getByRole("button", { name: "Create" }));

    expect(await screen.findByText("The repository rejected the change")).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });
});
