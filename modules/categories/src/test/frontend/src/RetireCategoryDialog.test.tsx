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

import type { CategoryNode } from "@iap/categories/categoryModel";
import RetireCategoryDialog from "@iap/categories/RetireCategoryDialog";

const node: CategoryNode = {
  name: "Paper",
  path: "/Categories/Paper",
  label: "Paper submissions",
  retired: false,
  children: [],
};

const renderDialog = ({ onRetire = vi.fn().mockResolvedValue(undefined), onClose = vi.fn() } = {}) => {
  render(<RetireCategoryDialog node={node} onClose={onClose} onRetire={onRetire} />);
  return { onRetire, onClose };
};

describe("RetireCategoryDialog", () => {
  it("names the category and explains what retiring it does", () => {
    renderDialog();

    expect(screen.getByRole("heading", { name: "Retire Paper submissions?" })).toBeInTheDocument();
    expect(screen.getByText(/no longer be available for new submissions/)).toBeInTheDocument();
    // The reassuring half: what retiring does not do
    expect(screen.getByText(/Existing submissions stay in this category and keep working/))
      .toBeInTheDocument();
    expect(screen.getByText(/can be undone at any time/)).toBeInTheDocument();
  });

  it("retires the category and closes", async () => {
    const { onRetire, onClose } = renderDialog();

    fireEvent.click(screen.getByRole("button", { name: "Retire" }));

    await waitFor(() => { expect(onRetire).toHaveBeenCalledWith(node); });
    await waitFor(() => { expect(onClose).toHaveBeenCalled(); });
  });

  it("closes without retiring anything when cancelled", () => {
    const { onRetire, onClose } = renderDialog();

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(onClose).toHaveBeenCalled();
    expect(onRetire).not.toHaveBeenCalled();
  });

  it("reports a refused retirement and stays open", async () => {
    const onRetire = vi.fn().mockRejectedValue(new Error("HTTP 403"));
    const { onClose } = renderDialog({ onRetire });

    fireEvent.click(screen.getByRole("button", { name: "Retire" }));

    expect(await screen.findByText("HTTP 403")).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Retire" })).toBeEnabled();
  });

  it("takes no second instruction while the retirement is in flight", async () => {
    let finish: () => void = () => { /* replaced below */ };
    const onRetire = vi.fn(() => new Promise<void>(resolve => { finish = resolve; }));
    const { onClose } = renderDialog({ onRetire });

    fireEvent.click(screen.getByRole("button", { name: "Retire" }));

    await waitFor(() => { expect(screen.getByRole("button", { name: "Retire" })).toBeDisabled(); });
    expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();

    finish();
    await waitFor(() => { expect(onClose).toHaveBeenCalled(); });
  });
});
