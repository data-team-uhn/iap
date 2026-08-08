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
import { MemoryRouter } from "react-router";

import { appTheme } from "@iap/frontend-commons/appTheme";
import WorkflowsManager from "@iap/workflows/WorkflowsManager";

// The editor itself is covered by its own suite, and it talks to the repository on mount; the tool
// only has to place it in the administration chrome.
vi.mock("@iap/workflows/BpmnEditor", () => ({
  default: () => <div data-testid="bpmn-editor" />,
}));

describe("WorkflowsManager", () => {
  it("hosts the BPMN editor in the administration chrome", () => {
    render(
      <ThemeProvider theme={appTheme}>
        <MemoryRouter initialEntries={["/admin/workflows"]}><WorkflowsManager /></MemoryRouter>
      </ThemeProvider>
    );

    expect(screen.getByRole("heading", { name: "Workflows" })).toBeInTheDocument();
    expect(screen.getByTestId("bpmn-editor")).toBeInTheDocument();
  });
});
