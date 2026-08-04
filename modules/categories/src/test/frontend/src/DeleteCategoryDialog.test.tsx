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

import DeleteCategoryDialog from "@iap/categories/DeleteCategoryDialog";
import { CategoryReferencedError } from "@iap/categories/useCategoryTree";

import type { CategoryNode } from "@iap/categories/categoryModel";

const node: CategoryNode = {
  name: "Paper",
  path: "/Categories/Paper",
  label: "Paper submissions",
  retired: false,
  children: [],
};

const renderDialog = (
  { onDelete = vi.fn().mockResolvedValue(undefined), onRetire = vi.fn().mockResolvedValue(undefined), onClose = vi.fn() } = {},
) => {
  render(<DeleteCategoryDialog node={node} onClose={onClose} onDelete={onDelete} onRetire={onRetire} />);
  return { onDelete, onRetire, onClose };
};

describe("DeleteCategoryDialog", () => {
  it("names the category and warns that the deletion is final", () => {
    renderDialog();

    expect(screen.getByRole("heading", { name: "Delete Paper submissions?" })).toBeInTheDocument();
    expect(screen.getByText(/will be permanently deleted/)).toBeInTheDocument();
  });

  it("deletes the category and closes", async () => {
    const { onDelete, onClose } = renderDialog();

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));

    await waitFor(() => { expect(onDelete).toHaveBeenCalledWith(node); });
    await waitFor(() => { expect(onClose).toHaveBeenCalled(); });
  });

  it("closes without deleting anything when cancelled", () => {
    const { onDelete, onClose } = renderDialog();

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(onClose).toHaveBeenCalled();
    expect(onDelete).not.toHaveBeenCalled();
  });

  it("offers to retire instead when the category still has submissions", async () => {
    const onDelete = vi.fn().mockRejectedValue(new CategoryReferencedError());
    const { onRetire, onClose } = renderDialog({ onDelete });

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));

    expect(await screen.findByText(/has submissions and cannot be deleted/)).toBeInTheDocument();
    // Retiring is explained here in the same words the retirement dialog uses
    expect(screen.getByText(/Existing submissions stay in this category and keep working/))
      .toBeInTheDocument();
    // The deletion is off the table now, and the dialog stays up to offer the alternative
    expect(screen.queryByRole("button", { name: "Delete" })).not.toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Retire instead" }));

    await waitFor(() => { expect(onRetire).toHaveBeenCalledWith(node); });
    await waitFor(() => { expect(onClose).toHaveBeenCalled(); });
  });

  it("reports a deletion that failed for some other reason, and stays open", async () => {
    const onDelete = vi.fn().mockRejectedValue(new Error("HTTP 500"));
    const { onClose } = renderDialog({ onDelete });

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));

    expect(await screen.findByText("HTTP 500")).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    // Still a deletion dialog, and ready to try again
    expect(screen.getByRole("button", { name: "Delete" })).toBeEnabled();
  });

  it("reports a failure that was not an Error", async () => {
    const onDelete = vi.fn().mockRejectedValue("connection reset");
    renderDialog({ onDelete });

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));

    expect(await screen.findByText("connection reset")).toBeInTheDocument();
  });

  it("reports a failed retirement too", async () => {
    const onDelete = vi.fn().mockRejectedValue(new CategoryReferencedError());
    const onRetire = vi.fn().mockRejectedValue(new Error("HTTP 403"));
    renderDialog({ onDelete, onRetire });
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    fireEvent.click(await screen.findByRole("button", { name: "Retire instead" }));

    expect(await screen.findByText("HTTP 403")).toBeInTheDocument();
  });

  it("takes no second instruction while one is in flight", async () => {
    let finish: () => void = () => { /* replaced below */ };
    const onDelete = vi.fn(() => new Promise<void>(resolve => { finish = resolve; }));
    const { onClose } = renderDialog({ onDelete });

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));

    await waitFor(() => { expect(screen.getByRole("button", { name: "Delete" })).toBeDisabled(); });
    expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();

    finish();
    await waitFor(() => { expect(onClose).toHaveBeenCalled(); });
  });
});
