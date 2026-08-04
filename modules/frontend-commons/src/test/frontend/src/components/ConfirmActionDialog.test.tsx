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
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import ConfirmActionDialog from "@iap/frontend-commons/components/ConfirmActionDialog";

import type { ComponentProps } from "react";

const renderDialog = (props: Partial<ComponentProps<typeof ConfirmActionDialog>> = {}) => {
  const onConfirm = props.onConfirm ?? vi.fn().mockResolvedValue(undefined);
  const onClose = props.onClose ?? vi.fn();
  render(
    <ThemeProvider theme={appTheme} defaultMode="light">
      <ConfirmActionDialog
        title="Delete this draft?"
        confirmLabel="Delete"
        {...props}
        onConfirm={onConfirm}
        onClose={onClose}
      >
        This cannot be undone.
      </ConfirmActionDialog>
    </ThemeProvider>
  );
  return { onConfirm, onClose };
};

describe("ConfirmActionDialog", () => {
  it("states what is about to happen", () => {
    renderDialog();

    expect(screen.getByRole("heading", { name: "Delete this draft?" })).toBeInTheDocument();
    expect(screen.getByText("This cannot be undone.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Delete" })).toBeEnabled();
  });

  it("runs the action and closes once it succeeds", async () => {
    const { onConfirm, onClose } = renderDialog();

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));

    await waitFor(() => { expect(onConfirm).toHaveBeenCalled(); });
    await waitFor(() => { expect(onClose).toHaveBeenCalled(); });
  });

  it("closes without acting when cancelled", () => {
    const { onConfirm, onClose } = renderDialog();

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(onClose).toHaveBeenCalled();
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it("reports a failed action in place and stays open to be tried again", async () => {
    const onConfirm = vi.fn().mockRejectedValue(new Error("HTTP 403"));
    const { onClose } = renderDialog({ onConfirm });

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));

    expect(await screen.findByText("HTTP 403")).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Delete" })).toBeEnabled();
    // The explanation of the action stays put above the report
    expect(screen.getByText("This cannot be undone.")).toBeInTheDocument();
  });

  it("reports a failure that was not an Error", async () => {
    const onConfirm = vi.fn().mockRejectedValue("connection reset");
    renderDialog({ onConfirm });

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));

    expect(await screen.findByText("connection reset")).toBeInTheDocument();
  });

  it("leaves a claimed failure to the caller, reporting nothing itself", async () => {
    const onConfirm = vi.fn().mockRejectedValue(new Error("HTTP 409"));
    const interceptFailure = vi.fn().mockReturnValue(true);
    const { onClose } = renderDialog({ onConfirm, interceptFailure });

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));

    await waitFor(() => { expect(interceptFailure).toHaveBeenCalledWith(new Error("HTTP 409")); });
    expect(screen.queryByText("HTTP 409")).not.toBeInTheDocument();
    // Neither a failure nor a success: the caller decides what the dialog becomes next
    expect(onClose).not.toHaveBeenCalled();
    await waitFor(() => { expect(screen.getByRole("button", { name: "Delete" })).toBeEnabled(); });
  });

  it("reports a failure the caller declines to claim", async () => {
    const onConfirm = vi.fn().mockRejectedValue(new Error("HTTP 500"));
    const interceptFailure = vi.fn().mockReturnValue(false);
    renderDialog({ onConfirm, interceptFailure });

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));

    expect(await screen.findByText("HTTP 500")).toBeInTheDocument();
  });

  it("clears an earlier report when the action is tried again", async () => {
    const onConfirm = vi.fn()
      .mockRejectedValueOnce(new Error("HTTP 503"))
      .mockResolvedValue(undefined);
    const { onClose } = renderDialog({ onConfirm });

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(await screen.findByText("HTTP 503")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));

    await waitFor(() => { expect(onClose).toHaveBeenCalled(); });
    expect(screen.queryByText("HTTP 503")).not.toBeInTheDocument();
  });

  it("takes no second instruction while the action is in flight", async () => {
    let finish: () => void = () => { /* replaced below */ };
    const onConfirm = vi.fn(() => new Promise<void>(resolve => { finish = resolve; }));
    const { onClose } = renderDialog({ onConfirm });

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));

    await waitFor(() => { expect(screen.getByRole("button", { name: "Delete" })).toBeDisabled(); });
    expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(onConfirm).toHaveBeenCalledTimes(1);

    finish();
    await waitFor(() => { expect(onClose).toHaveBeenCalled(); });
  });
});
