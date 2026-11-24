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

import type { ComponentProps } from "react";

import { ThemeProvider } from "@mui/material/styles";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import FormDialog from "@iap/frontend-commons/components/FormDialog";

const renderDialog = (props: Partial<ComponentProps<typeof FormDialog>> = {}) => {
  const onSubmit = props.onSubmit ?? vi.fn().mockResolvedValue(undefined);
  const onClose = props.onClose ?? vi.fn();
  render(
    <ThemeProvider theme={appTheme} defaultMode="light">
      <FormDialog title="New thing" submitLabel="Create" valid {...props} onSubmit={onSubmit} onClose={onClose}>
        <input aria-label="Name" />
      </FormDialog>
    </ThemeProvider>
  );
  return { onSubmit, onClose };
};

describe("FormDialog", () => {
  it("shows its title, its fields and the two buttons", () => {
    renderDialog();

    expect(screen.getByRole("heading", { name: "New thing" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Name" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Create" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Cancel" })).toBeEnabled();
  });

  it("offers no submit while the fields are not valid", () => {
    renderDialog({ valid: false });

    expect(screen.getByRole("button", { name: "Create" })).toBeDisabled();
  });

  it("closes without submitting when cancelled", () => {
    const { onSubmit, onClose } = renderDialog();

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(onClose).toHaveBeenCalled();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("submits and closes once the request resolves", async () => {
    const { onSubmit, onClose } = renderDialog();

    fireEvent.click(screen.getByRole("button", { name: "Create" }));

    await waitFor(() => { expect(onSubmit).toHaveBeenCalled(); });
    await waitFor(() => { expect(onClose).toHaveBeenCalled(); });
  });

  it("reports a failed request under the fields and stays open to be tried again", async () => {
    const onSubmit = vi.fn().mockRejectedValue(new Error("HTTP 403"));
    const { onClose } = renderDialog({ onSubmit });

    fireEvent.click(screen.getByRole("button", { name: "Create" }));

    expect(await screen.findByText("HTTP 403")).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Create" })).toBeEnabled();
    expect(screen.getByRole("textbox", { name: "Name" })).toBeInTheDocument();
  });

  it("takes no further instruction while the request is in flight", async () => {
    let finish: () => void = () => { /* replaced below */ };
    const onSubmit = vi.fn(() => new Promise<void>(resolve => { finish = resolve; }));
    const { onClose } = renderDialog({ onSubmit });

    fireEvent.click(screen.getByRole("button", { name: "Create" }));

    await waitFor(() => { expect(screen.getByRole("button", { name: "Create" })).toBeDisabled(); });
    expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();
    await waitFor(() => { expect(screen.getByRole("button", { name: "close" })).toBeDisabled(); });
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    expect(onClose).not.toHaveBeenCalled();

    finish();
    await waitFor(() => { expect(onClose).toHaveBeenCalled(); });
  });

  it("says what is happening while the request is in flight, when it has a word for it", async () => {
    let finish: () => void = () => { /* replaced below */ };
    renderDialog({ workingLabel: "Creating…", onSubmit: () => new Promise<void>(resolve => { finish = resolve; }) });

    fireEvent.click(screen.getByRole("button", { name: "Create" }));

    expect(await screen.findByRole("button", { name: "Creating…" })).toBeDisabled();
    finish();
  });
});
