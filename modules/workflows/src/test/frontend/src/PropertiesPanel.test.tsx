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

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import PropertiesPanel from "@iap/workflows/PropertiesPanel";

import { createCanvas } from "./canvas.fixture";
import { Themed } from "./parameterField.fixture";
import { type } from "./vocabulary.fixture";

import type BaseViewer from "bpmn-js/lib/BaseViewer";
import type { Element } from "bpmn-js/lib/model/Types";

// How many of the panel's subscriptions to an event are still in place, from what it asked of the canvas
const subscriptions = (on: { mock: { calls: unknown[][] } }, off: { mock: { calls: unknown[][] } }, event: string) => {
  const naming = (calls: unknown[][]) => calls.filter(([ events ]) => [ events ].flat().includes(event)).length;
  return naming(on.mock.calls) - naming(off.mock.calls);
};

// The shared canvas, watched for what the panel subscribes to. Its registry holds whatever was selected or
// reported changed, as the canvas's would.
const createViewer = () => {
  const canvas = createCanvas();
  const on = vi.spyOn(canvas.viewer, "on");
  const off = vi.spyOn(canvas.viewer, "off");
  const fire = (event: string, payload: object) => {
    const { newSelection = [], elements = [] } = payload as { newSelection?: Element[]; elements?: Element[] };
    [ ...newSelection, ...elements ].forEach(element => canvas.registered.set(element.id, element));
    return canvas.fire(event, payload);
  };
  return {
    viewer: canvas.viewer,
    fire,
    updateLabel: canvas.updateLabel,
    listening: (event: string) => subscriptions(on, off, event),
  };
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

    expect(screen.getByText("Identifier")).toBeInTheDocument();
    expect(screen.getByText("StartEvent_1")).toBeInTheDocument();
    expect(screen.getByRole("textbox")).toHaveValue("Kick off");
    expect(screen.queryByText("Please select an element.")).not.toBeInTheDocument();
  });

  it("shows the properties of the element a selected label belongs to", async () => {
    const { viewer, fire } = createViewer();
    renderPanel(viewer);

    await fire("selection.changed", { newSelection: [ { id: "StartEvent_1_label",
      labelTarget: element("StartEvent_1", "Kick off") } as unknown as Element ] });

    expect(screen.getByText("StartEvent_1")).toBeInTheDocument();
    expect(screen.getByRole("textbox")).toHaveValue("Kick off");
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

    expect(screen.getByText("Identifier")).toBeInTheDocument();
    expect(screen.getByText("StartEvent_1")).toBeInTheDocument();
    expect(screen.getByText("Name")).toBeInTheDocument();
    expect(screen.getByText("Kick off")).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("subscribes to a canvas once, however often the selection changes", async () => {
    const { viewer, fire, listening } = createViewer();
    renderPanel(viewer);

    await fire("selection.changed", { newSelection: [element("A", "First")] });
    await fire("selection.changed", { newSelection: [element("B", "Second")] });

    // The panel's own, and the one the element shown writes what is pending with
    expect(listening("selection.changed")).toBe(2);
    expect(listening("elements.changed")).toBe(1);
    expect(screen.getByRole("textbox")).toHaveValue("Second");
  });

  it("stops listening to a canvas it is no longer showing", async () => {
    const { viewer, fire, listening } = createViewer();
    const { unmount } = renderPanel(viewer);
    await fire("selection.changed", { newSelection: [element("A", "First")] });

    unmount();

    expect(viewer.off).toHaveBeenCalledWith("selection.changed", expect.any(Function));
    expect(viewer.off).toHaveBeenCalledWith("elements.changed", expect.any(Function));
    expect(listening("selection.changed")).toBe(0);
    expect(listening("elements.changed")).toBe(0);
  });

  it("follows the canvas it is given when that changes, and forgets the previous one's selection", async () => {
    // As switching between editing and viewing a version does
    const first = createViewer();
    const second = createViewer();
    const { rerender } = renderPanel(first.viewer);
    await first.fire("selection.changed", { newSelection: [element("A", "First")] });

    rerender(<PropertiesPanel viewer={second.viewer} />);

    expect(first.listening("selection.changed")).toBe(0);
    expect(second.listening("selection.changed")).toBe(1);
    expect(screen.getByText("Please select an element.")).toBeInTheDocument();
  });

  it("reads the selected element through the vocabulary it is handed", async () => {
    const { viewer, fire } = createViewer();
    const userTask = type("UserTask", { label: "User task", priority: 0, xmlElement: "bpmn:userTask",
      category: [ "User Task" ] });
    render(<PropertiesPanel viewer={viewer} types={[ userTask ]} />, { wrapper: Themed });

    await fire("selection.changed", { newSelection: [ {
      id: "Activity_1",
      businessObject: { $type: "bpmn:userTask", name: "Review" },
    } as unknown as Element ] });

    expect(screen.getByText("Element Type")).toBeInTheDocument();
    expect(screen.getByText("User Task")).toBeInTheDocument();
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

  it("moves on to the element selected next once what was typed into the last one is written", async () => {
    // The write is reported as a change to the element being left, before the panel has moved on
    const user = userEvent.setup();
    const canvas = createCanvas();
    canvas.writeThrough();
    const [ first, second ] = [ element("A", "First"), element("B", "Second") ];
    const add = canvas.viewer.get<{ add: (element: Element) => void }>("elementRegistry").add;
    [ first, second ].forEach(add);
    renderPanel(canvas.viewer);
    await canvas.fire("selection.changed", { newSelection: [ first ] });

    await user.type(screen.getByRole("textbox"), "ly");
    await canvas.fire("selection.changed", { newSelection: [ second ] });

    expect(canvas.updateLabel).toHaveBeenCalledWith(first, "Firstly");
    expect((first.businessObject as { name?: string }).name).toBe("Firstly");
    expect(screen.getByText("Identifier")).toBeInTheDocument();
    expect(screen.getByText("B")).toBeInTheDocument();
    expect(screen.getByRole("textbox")).toHaveValue("Second");
  });
});
