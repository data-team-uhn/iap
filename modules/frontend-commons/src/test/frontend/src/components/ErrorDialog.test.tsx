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
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { appTheme } from "@iap/frontend-commons/appTheme";
import ErrorDialog from "@iap/frontend-commons/components/ErrorDialog";

import type { ComponentProps } from "react";

const renderDialog = (props: Partial<ComponentProps<typeof ErrorDialog>> = {}) => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <ErrorDialog open {...props}>
      Saving failed due to an unknown error.
    </ErrorDialog>
  </ThemeProvider>
);

describe("ErrorDialog", () => {
  it("titles itself Error by default", () => {
    renderDialog();

    expect(screen.getByRole("heading", { name: /Error/ })).toBeInTheDocument();
    expect(screen.getByText("Saving failed due to an unknown error.")).toBeInTheDocument();
  });

  it("takes the title it is given", () => {
    renderDialog({ title: "Failed to save data" });

    expect(screen.getByRole("heading", { name: /Failed to save data/ })).toBeInTheDocument();
  });

  it("closes on its close button", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderDialog({ onClose });

    await user.click(screen.getByRole("button"));

    expect(onClose).toHaveBeenCalled();
  });

  it("closes on escape", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderDialog({ onClose });

    await user.keyboard("{Escape}");

    expect(onClose).toHaveBeenCalled();
  });

  it("tolerates being closed with nobody listening", async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByRole("button"));

    expect(screen.getByText("Saving failed due to an unknown error.")).toBeInTheDocument();
  });
});
