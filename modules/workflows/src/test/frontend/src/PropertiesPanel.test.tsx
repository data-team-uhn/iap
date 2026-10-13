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

import { act, render, screen } from "@testing-library/react";

import PropertiesPanel from "@iap/workflows/PropertiesPanel";

import { Themed } from "./parameterField.fixture";
import { type } from "./vocabulary.fixture";

import type BaseViewer from "bpmn-js/lib/BaseViewer";
import type { Element } from "bpmn-js/lib/model/Types";

// The panel uses the canvas as an event bus, plus the `get` and `getDefinitions` ElementProperties
// needs, so a recording stand-in is enough to drive it.
const createViewer = () => {
  const handlers: Record<string, ((event: never) => void)[] | undefined> = {};
  const updateLabel = vi.fn();
  const viewer = {
    on: vi.fn((event: string, handler: (event: never) => void) => {
      (handlers[event] ??= []).push(handler);
    }),
    off: vi.fn((event: string, handler: (event: never) => void) => {
      handlers[event] = (handlers[event] ?? []).filter(subscribed => subscribed !== handler);
    }),
    get: vi.fn(() => ({ updateLabel })),
    getDefinitions: vi.fn(() => ({ $attrs: {} })),
  };
  // Whatever is subscribed at the time, so that a handler the panel has since dropped cannot answer.
  // The callback is `async` even though the dispatch is not: that is what makes `act` hand back a
  // promise for the call sites to await, so the effects the dispatch schedules are flushed before the
  // assertions run.
  const fire = (event: string, payload: unknown) => act(async () => {
    (handlers[event] ?? []).forEach(handler => (handler as (e: unknown) => void)(payload));
  });
  return { viewer: viewer as unknown as BaseViewer, fire, updateLabel, handlers };
};

const element = (id: string, name?: string) => ({ id, businessObject: name ? { name } : {} }) as unknown as Element;

const renderPanel = (viewer: BaseViewer | null, readOnly = false) =>
  render(<PropertiesPanel viewer={viewer} readOnly={readOnly} />, { wrapper: Themed });

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
    // Read-only renders plain text. There is no field at all.
    const { viewer, fire } = createViewer();
    renderPanel(viewer, true);

    await fire("selection.changed", { newSelection: [element("StartEvent_1", "Kick off")] });

    expect(screen.getByText("Identifier: StartEvent_1")).toBeInTheDocument();
    expect(screen.getByText("Name: Kick off")).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("subscribes to a canvas once, however often the selection changes", async () => {
    const { viewer, fire, handlers } = createViewer();
    renderPanel(viewer);

    await fire("selection.changed", { newSelection: [element("A", "First")] });
    await fire("selection.changed", { newSelection: [element("B", "Second")] });

    expect(handlers["selection.changed"]).toHaveLength(1);
    expect(handlers["elements.changed"]).toHaveLength(1);
    expect(screen.getByRole("textbox")).toHaveValue("Second");
  });

  it("stops listening to a canvas it is no longer showing", async () => {
    const { viewer, fire, handlers } = createViewer();
    const { unmount } = renderPanel(viewer);
    await fire("selection.changed", { newSelection: [element("A", "First")] });

    unmount();

    expect(viewer.off).toHaveBeenCalledWith("selection.changed", expect.any(Function));
    expect(viewer.off).toHaveBeenCalledWith("elements.changed", expect.any(Function));
    expect(handlers["selection.changed"]).toHaveLength(0);
    expect(handlers["elements.changed"]).toHaveLength(0);
  });

  it("follows the canvas it is given when that changes, and forgets the previous one's selection", async () => {
    // As switching between editing and viewing a version does
    const first = createViewer();
    const second = createViewer();
    const { rerender } = renderPanel(first.viewer);
    await first.fire("selection.changed", { newSelection: [element("A", "First")] });

    rerender(<PropertiesPanel viewer={second.viewer} />);

    expect(first.handlers["selection.changed"]).toHaveLength(0);
    expect(second.handlers["selection.changed"]).toHaveLength(1);
    expect(screen.getByText("Please select an element.")).toBeInTheDocument();
  });

  it("reads the selected element through the vocabulary it is handed", async () => {
    const { viewer, fire } = createViewer();
    const userTask = type("UserTask", { label: "User task", priority: 0, xmlElement: "bpmn:userTask" });
    render(<PropertiesPanel viewer={viewer} types={[ userTask ]} />, { wrapper: Themed });

    await fire("selection.changed", { newSelection: [ {
      id: "Activity_1",
      businessObject: { $type: "bpmn:userTask", name: "Review" },
    } as unknown as Element ] });

    expect(screen.getByText("Type: User task")).toBeInTheDocument();
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
