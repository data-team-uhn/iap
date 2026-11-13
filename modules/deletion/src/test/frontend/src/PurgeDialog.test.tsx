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

import { ThemeProvider } from "@mui/material/styles";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import type { ArchiveEntry } from "@iap/deletion/archiveApi";
import { PurgeDialog } from "@iap/deletion/PurgeDialog";
import { appTheme } from "@iap/frontend-commons/appTheme";

const ENTRY: ArchiveEntry = {
  path: "/Archive/ab/cd/ef/one",
  shortPath: "/Archive/by-id/one",
  requestedPath: "/content/one",
  deletedBy: "alice",
  originalPaths: [ "/content/one", "/content/two" ],
  itemCount: 2,
};

const renderDialog = (onPurge: () => Promise<void> = () => Promise.resolve()) => {
  const purge = vi.fn(onPurge);
  const onClose = vi.fn();
  render(
    <ThemeProvider theme={appTheme} defaultMode="light">
      <PurgeDialog entry={ENTRY} onPurge={purge} onClose={onClose} />
    </ThemeProvider>
  );
  return { purge, onClose, dialog: screen.getByRole("dialog") };
};

describe("PurgeDialog", () => {
  it("says what the purge destroys, and that it cannot be undone", () => {
    const { dialog } = renderDialog();

    expect(within(dialog).getByText(/Everything archived under \/content\/one will be destroyed/)).toBeInTheDocument();
    expect(within(dialog).getByText(/cannot be undone/)).toBeInTheDocument();
    expect(within(dialog).getByText("2 archived item(s) will be permanently removed.")).toBeInTheDocument();
  });

  it("purges once confirmed, and closes when the purge is done", async () => {
    const { purge, onClose, dialog } = renderDialog();

    await userEvent.click(within(dialog).getByRole("button", { name: "Purge" }));

    expect(purge).toHaveBeenCalledTimes(1);
    await waitFor(() => { expect(onClose).toHaveBeenCalled(); });
  });

  it("stays open, and takes no second click, while the purge is out", async () => {
    let finish!: () => void;
    const { onClose, dialog } = renderDialog(() => new Promise(resolve => { finish = resolve; }));

    await userEvent.click(within(dialog).getByRole("button", { name: "Purge" }));

    expect(within(dialog).getByRole("button", { name: "Purging…" })).toBeDisabled();
    expect(onClose).not.toHaveBeenCalled();
    finish();
    await waitFor(() => { expect(onClose).toHaveBeenCalled(); });
  });

  it("closes without purging when called off", async () => {
    const { purge, onClose, dialog } = renderDialog();

    await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));

    expect(onClose).toHaveBeenCalled();
    expect(purge).not.toHaveBeenCalled();
  });
});
