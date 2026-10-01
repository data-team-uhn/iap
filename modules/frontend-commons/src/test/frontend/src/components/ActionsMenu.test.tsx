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

import { type ReactNode } from "react";

import { ThemeProvider } from "@mui/material/styles";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import { ActionsMenu } from "@iap/frontend-commons/components/ActionsMenu";
import { ActionIcon } from "@iap/frontend-commons/components/EventAction";
import { stubPhone } from "@iap/frontend-commons/phone.fixture";

afterEach(() => vi.unstubAllGlobals());

const renderMenu = (children: ReactNode) => render(
  <ThemeProvider theme={appTheme}>
    <ActionsMenu label="Actions for “Consent”">{children}</ActionsMenu>
  </ThemeProvider>,
);

describe("ActionsMenu", () => {
  it("leaves the actions as buttons on a wider screen", () => {
    stubPhone(false);
    renderMenu(<ActionIcon label="Edit" icon={null} onClick={vi.fn()} />);

    expect(screen.getByRole("button", { name: "Edit" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Actions for “Consent”" })).not.toBeInTheDocument();
  });

  it("does what a line asks given what held the menu, once the menu is closed", async () => {
    stubPhone(true);
    const edit = vi.fn();
    renderMenu(<ActionIcon label="Edit" icon={null} onClick={edit} />);
    const button = screen.getByRole("button", { name: "Actions for “Consent”" });

    fireEvent.click(button);
    fireEvent.click(await screen.findByRole("menuitem", { name: "Edit" }));

    expect(edit).toHaveBeenCalledWith(button.parentElement);
    await waitFor(() => expect(screen.queryByRole("menu")).not.toBeInTheDocument());
  });

  it("shows no button with nothing to offer", async () => {
    stubPhone(true);
    renderMenu(null);

    await waitFor(() => {
      expect(screen.queryByRole("button", { name: "Actions for “Consent”" })).not.toBeInTheDocument();
    });
  });
});
