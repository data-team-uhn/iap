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

import CategoryTree, { type CategoryActions } from "@iap/categories/CategoryTree";

import type { CategoryNode } from "@iap/categories/categoryModel";

const node = (name: string, overrides: Partial<CategoryNode> = {}): CategoryNode => ({
  name,
  path: `/Categories/${name}`,
  label: name,
  retired: false,
  children: [],
  ...overrides,
});

const createActions = (): CategoryActions => ({
  onEdit: vi.fn(),
  onAddChild: vi.fn(),
  onDelete: vi.fn(),
  onToggleRetired: vi.fn(),
  onReorder: vi.fn(),
});

const renderTree = (nodes: CategoryNode[]) => {
  const actions = createActions();
  return { actions, ...render(<CategoryTree nodes={nodes} actions={actions} />) };
};

describe("CategoryTree", () => {
  it("lists the categories it is given", () => {
    renderTree([node("First"), node("Second")]);

    expect(screen.getByText("First")).toBeInTheDocument();
    expect(screen.getByText("Second")).toBeInTheDocument();
  });

  it("renders nothing for an empty tree", () => {
    const { container } = renderTree([]);

    expect(container.querySelectorAll("li")).toHaveLength(0);
  });

  it("shows a category's description", () => {
    renderTree([node("Retrospective", { description: "Existing data only." })]);

    expect(screen.getByText("Existing data only.")).toBeInTheDocument();
  });

  it("marks a retired category, and offers to unretire it", () => {
    const { actions } = renderTree([node("Paper", { retired: true })]);

    expect(screen.getByText("Retired")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Unretire Paper" }));

    expect(actions.onToggleRetired).toHaveBeenCalledWith(expect.objectContaining({ name: "Paper" }));
  });

  it("offers to retire a category that is still in use", () => {
    const { actions } = renderTree([node("Paper")]);

    fireEvent.click(screen.getByRole("button", { name: "Retire Paper" }));

    expect(actions.onToggleRetired).toHaveBeenCalled();
    expect(screen.queryByText("Retired")).not.toBeInTheDocument();
  });

  it("shows the schema a category is bound to", () => {
    renderTree([node("Bound", { schemaVersion: { uuid: "u1", schemaName: "basic", version: "1.0" } })]);

    expect(screen.getByText("Schema: basic v1.0")).toBeInTheDocument();
  });

  it("stands in for the parts of an incomplete schema binding", () => {
    renderTree([node("Bound", { schemaVersion: { uuid: "u1" } })]);

    expect(screen.getByText("Schema: ? v?")).toBeInTheDocument();
  });

  it("reports the edit and add-subcategory actions", () => {
    const { actions } = renderTree([node("First")]);

    fireEvent.click(screen.getByRole("button", { name: "Edit First" }));
    fireEvent.click(screen.getByRole("button", { name: "Add subcategory to First" }));

    expect(actions.onEdit).toHaveBeenCalledWith(expect.objectContaining({ name: "First" }));
    expect(actions.onAddChild).toHaveBeenCalledWith(expect.objectContaining({ name: "First" }));
  });

  it("reports deleting a leaf, but refuses one that still has subcategories", () => {
    const { actions } = renderTree([node("Leaf"), node("Parent", { children: [node("Child")] })]);

    expect(screen.getByRole("button", { name: "Delete Parent" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Delete Leaf" }));

    expect(actions.onDelete).toHaveBeenCalledWith(expect.objectContaining({ name: "Leaf" }));
  });

  describe("reordering", () => {
    it("cannot move the only category either way", () => {
      renderTree([node("Only")]);

      expect(screen.getByRole("button", { name: "Move Only up" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "Move Only down" })).toBeDisabled();
    });

    it("moves a middle category before its previous sibling, or after its next one", () => {
      const { actions } = renderTree([node("First"), node("Middle"), node("Last")]);

      fireEvent.click(screen.getByRole("button", { name: "Move Middle up" }));
      expect(actions.onReorder).toHaveBeenCalledWith(node("Middle"), "before First", "up");

      fireEvent.click(screen.getByRole("button", { name: "Move Middle down" }));
      expect(actions.onReorder).toHaveBeenCalledWith(node("Middle"), "after Last", "down");
    });

    it("keeps the categories at each end from moving past it", () => {
      renderTree([node("First"), node("Last")]);

      expect(screen.getByRole("button", { name: "Move First up" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "Move First down" })).toBeEnabled();
      expect(screen.getByRole("button", { name: "Move Last up" })).toBeEnabled();
      expect(screen.getByRole("button", { name: "Move Last down" })).toBeDisabled();
    });

    it("reorders within a subtree against that subtree's own siblings", () => {
      const { actions } = renderTree([
        node("Parent", { children: [node("ChildA"), node("ChildB")] }),
        node("Other"),
      ]);

      fireEvent.click(screen.getByRole("button", { name: "Move ChildB up" }));

      expect(actions.onReorder).toHaveBeenCalledWith(node("ChildB"), "before ChildA", "up");
    });
  });

  describe("subcategories", () => {
    it("shows them expanded, and collapses them on request", async () => {
      renderTree([node("Parent", { children: [node("Child")] })]);
      expect(screen.getByText("Child")).toBeInTheDocument();

      fireEvent.click(screen.getByRole("button", { name: "Collapse Parent" }));

      // The subtree is unmounted only once the collapse transition has run
      await waitFor(() => { expect(screen.queryByText("Child")).not.toBeInTheDocument(); });
      expect(screen.getByRole("button", { name: "Expand Parent" })).toBeInTheDocument();
    });

    it("brings them back", async () => {
      renderTree([node("Parent", { children: [node("Child")] })]);
      fireEvent.click(screen.getByRole("button", { name: "Collapse Parent" }));
      await waitFor(() => { expect(screen.queryByText("Child")).not.toBeInTheDocument(); });

      fireEvent.click(screen.getByRole("button", { name: "Expand Parent" }));

      expect(screen.getByText("Child")).toBeInTheDocument();
    });

    it("shares one set of actions with every depth", () => {
      const { actions } = renderTree([
        node("Parent", { children: [node("Child", { children: [node("Grandchild")] })] }),
      ]);

      expect(screen.getByText("Grandchild")).toBeInTheDocument();
      fireEvent.click(screen.getByRole("button", { name: "Edit Grandchild" }));

      expect(actions.onEdit).toHaveBeenCalledWith(expect.objectContaining({ name: "Grandchild" }));
    });
  });
});
