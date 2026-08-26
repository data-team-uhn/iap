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
import { act, render, screen } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import PropertiesPanel from "@iap/workflows/PropertiesPanel";

import type BaseViewer from "bpmn-js/lib/BaseViewer";
import type { Element } from "bpmn-js/lib/model/Types";

// The panel only uses the canvas as an event bus (plus `get`, which ElementProperties needs to
// rename an element), so a recording stand-in is enough to drive it.
const createViewer = () => {
  const handlers: Record<string, ((event: never) => void)[]> = {};
  const updateLabel = vi.fn();
  const viewer = {
    on: vi.fn((event: string, handler: (event: never) => void) => {
      (handlers[event] ??= []).push(handler);
    }),
    get: vi.fn(() => ({ updateLabel })),
  };
  // Every subscription for an event, since the panel re-subscribes whenever the selection changes
  // and the newest handler is the one holding the current state. The callback is `async` even
  // though the dispatch is not: that is what makes `act` hand back a promise for the call sites to
  // await, so the effects the re-subscription schedules are flushed before the assertions run.
  const fire = (event: string, payload: unknown) => act(async () => {
    handlers[event].forEach(handler => (handler as (e: unknown) => void)(payload));
  });
  return { viewer: viewer as unknown as BaseViewer, fire, updateLabel };
};

const element = (id: string, name?: string) => ({ id, businessObject: name ? { name } : {} }) as unknown as Element;

const renderPanel = (viewer: BaseViewer | null, readOnly = false) => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <PropertiesPanel viewer={viewer} readOnly={readOnly} />
  </ThemeProvider>
);

describe("PropertiesPanel", () => {
  it("prompts for a selection when nothing is selected", () => {
    renderPanel(createViewer().viewer);

    expect(screen.getByText("Please select an element.")).toBeInTheDocument();
  });

  it("does not subscribe until there is a canvas", () => {
    renderPanel(null);

    expect(screen.getByText("Please select an element.")).toBeInTheDocument();
  });

  it("subscribes to the canvas's selection and change events", () => {
    const { viewer } = createViewer();

    renderPanel(viewer);

    expect(viewer.on).toHaveBeenCalledWith("selection.changed", expect.any(Function));
    expect(viewer.on).toHaveBeenCalledWith("elements.changed", expect.any(Function));
  });

  it("shows the properties of a single selected element", async () => {
    const { viewer, fire } = createViewer();
    renderPanel(viewer);

    await fire("selection.changed", { newSelection: [element("StartEvent_1", "Kick off")] });

    expect(screen.getByText("Identifier: StartEvent_1")).toBeInTheDocument();
    expect(screen.getByRole("textbox")).toHaveValue("Kick off");
    expect(screen.queryByText("Please select an element.")).not.toBeInTheDocument();
  });

  it("refuses to edit more than one element at a time", async () => {
    const { viewer, fire } = createViewer();
    renderPanel(viewer);

    await fire("selection.changed", { newSelection: [element("A"), element("B")] });

    expect(screen.getByText("Please select a single element.")).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("goes back to prompting once the selection is cleared", async () => {
    const { viewer, fire } = createViewer();
    renderPanel(viewer);

    await fire("selection.changed", { newSelection: [element("A", "First")] });
    await fire("selection.changed", { newSelection: [] });

    expect(screen.getByText("Please select an element.")).toBeInTheDocument();
  });

  it("picks up changes made to the selected element elsewhere", async () => {
    const { viewer, fire } = createViewer();
    renderPanel(viewer);
    await fire("selection.changed", { newSelection: [element("Task_1", "Before")] });

    await fire("elements.changed", { elements: [element("Other_1", "Untouched"), element("Task_1", "After")] });

    expect(screen.getByRole("textbox")).toHaveValue("After");
  });

  it("ignores changes to other elements", async () => {
    const { viewer, fire } = createViewer();
    renderPanel(viewer);
    await fire("selection.changed", { newSelection: [element("Task_1", "Before")] });

    await fire("elements.changed", { elements: [element("Other_1", "Untouched")] });

    expect(screen.getByRole("textbox")).toHaveValue("Before");
  });

  it("shows a selected element's properties as text when they are read-only", async () => {
    // A read-only panel is not an editable one with its field disabled: there is no field
    const { viewer, fire } = createViewer();
    renderPanel(viewer, true);

    await fire("selection.changed", { newSelection: [element("StartEvent_1", "Kick off")] });

    expect(screen.getByText("Identifier: StartEvent_1")).toBeInTheDocument();
    expect(screen.getByText("Name: Kick off")).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("ignores changes when nothing is selected, or when nothing changed", async () => {
    const { viewer, fire } = createViewer();
    renderPanel(viewer);

    await fire("elements.changed", { elements: [element("Task_1", "After")] });
    expect(screen.getByText("Please select an element.")).toBeInTheDocument();

    await fire("selection.changed", { newSelection: [element("Task_1", "Before")] });
    await fire("elements.changed", { elements: [] });
    expect(screen.getByRole("textbox")).toHaveValue("Before");
  });
});
