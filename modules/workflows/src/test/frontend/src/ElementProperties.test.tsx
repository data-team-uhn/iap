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
import ElementProperties from "@iap/workflows/ElementProperties";

import type BaseViewer from "bpmn-js/lib/BaseViewer";
import type { Element } from "bpmn-js/lib/model/Types";

// Renaming goes through the canvas's `modeling` service, the only part of bpmn-js this component
// touches — and the reason renaming is only offered when the canvas is a modeler.
const createViewer = () => {
  const updateLabel = vi.fn();
  const viewer = { get: vi.fn(() => ({ updateLabel })) } as unknown as BaseViewer;
  return { viewer, updateLabel };
};

const renderProperties = (element: Element, viewer: BaseViewer, readOnly = false) => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <ElementProperties element={element} viewer={viewer} readOnly={readOnly} />
  </ThemeProvider>
);

describe("ElementProperties", () => {
  it("shows the element's identifier and name", () => {
    const { viewer } = createViewer();

    renderProperties({ id: "Task_1", businessObject: { name: "Review" } } as unknown as Element, viewer);

    expect(screen.getByText("Identifier: Task_1")).toBeInTheDocument();
    expect(screen.getByRole("textbox")).toHaveValue("Review");
  });

  it("leaves the name empty for an element that has none", () => {
    const { viewer } = createViewer();

    renderProperties({ id: "Task_1", businessObject: {} } as unknown as Element, viewer);

    expect(screen.getByRole("textbox")).toHaveValue("");
  });

  it("leaves the name empty for an element with no business object at all", () => {
    const { viewer } = createViewer();

    renderProperties({ id: "Task_1" } as unknown as Element, viewer);

    expect(screen.getByRole("textbox")).toHaveValue("");
  });

  it("shows the name as text, with nothing to type into, when read-only", () => {
    const { viewer } = createViewer();

    renderProperties({ id: "Task_1", businessObject: { name: "Review" } } as unknown as Element, viewer, true);

    expect(screen.getByText("Identifier: Task_1")).toBeInTheDocument();
    expect(screen.getByText("Name: Review")).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("renames the element through the modeling service as the name is typed", async () => {
    const user = userEvent.setup();
    const { viewer, updateLabel } = createViewer();
    const element = { id: "Task_1", businessObject: {} } as unknown as Element;
    renderProperties(element, viewer);

    await user.type(screen.getByRole("textbox"), "Hi");

    expect(viewer.get).toHaveBeenCalledWith("modeling");
    // updateLabel replaces the whole name, so every keystroke reports the field's full current value,
    // not just what changed -- and the field shows that same value back immediately.
    expect(updateLabel).toHaveBeenCalledTimes(2);
    expect(updateLabel).toHaveBeenNthCalledWith(1, element, "H");
    expect(updateLabel).toHaveBeenNthCalledWith(2, element, "Hi");
    expect(screen.getByRole("textbox")).toHaveValue("Hi");
  });
});
