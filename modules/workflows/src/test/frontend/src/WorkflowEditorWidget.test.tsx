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

import { appTheme } from "@iap/frontend-commons/appTheme";
import WorkflowEditorWidget from "@iap/workflows/WorkflowEditorWidget";

describe("WorkflowEditorWidget", () => {
  it("links to the full workflow editor", () => {
    render(
      <ThemeProvider theme={appTheme} defaultMode="light">
        <WorkflowEditorWidget />
      </ThemeProvider>
    );

    expect(screen.getByText("Create and edit workflow definitions using the visual BPMN editor.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open Workflow Editor" })).toHaveAttribute("href", "/Workflows.html");
  });
});
