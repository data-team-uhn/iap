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

import type { Element } from "bpmn-js/lib/model/Types";
import type Modeler from "bpmn-js/lib/Modeler";

// Renaming goes through the modeler's `modeling` service, the only part of bpmn-js this component
// touches.
const createModeler = () => {
  const updateLabel = vi.fn();
  const modeler = { get: vi.fn(() => ({ updateLabel })) } as unknown as Modeler;
  return { modeler, updateLabel };
};

const renderProperties = (element: Element, modeler: Modeler) => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <ElementProperties element={element} modeler={modeler} />
  </ThemeProvider>
);

describe("ElementProperties", () => {
  it("shows the element's identifier and name", () => {
    const { modeler } = createModeler();

    renderProperties({ id: "Task_1", businessObject: { name: "Review" } } as unknown as Element, modeler);

    expect(screen.getByText("Identifier: Task_1")).toBeInTheDocument();
    expect(screen.getByRole("textbox")).toHaveValue("Review");
  });

  it("leaves the name empty for an element that has none", () => {
    const { modeler } = createModeler();

    renderProperties({ id: "Task_1", businessObject: {} } as unknown as Element, modeler);

    expect(screen.getByRole("textbox")).toHaveValue("");
  });

  it("leaves the name empty for an element with no business object at all", () => {
    const { modeler } = createModeler();

    renderProperties({ id: "Task_1" } as unknown as Element, modeler);

    expect(screen.getByRole("textbox")).toHaveValue("");
  });

  it("renames the element through the modeling service as the name is typed", async () => {
    const user = userEvent.setup();
    const { modeler, updateLabel } = createModeler();
    const element = { id: "Task_1", businessObject: {} } as unknown as Element;
    renderProperties(element, modeler);

    await user.type(screen.getByRole("textbox"), "Hi");

    expect(modeler.get).toHaveBeenCalledWith("modeling");
    // The field is controlled by the element's own name, which only the modeler updates, so each
    // keystroke is reported against the unchanged value rather than accumulating
    expect(updateLabel).toHaveBeenCalledTimes(2);
    expect(updateLabel).toHaveBeenNthCalledWith(1, element, "H");
    expect(updateLabel).toHaveBeenNthCalledWith(2, element, "i");
  });
});
