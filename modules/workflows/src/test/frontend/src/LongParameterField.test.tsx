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

import { fireEvent, screen } from "@testing-library/react";

import LongParameterField from "@iap/workflows/LongParameterField";

import { renderField, STATES } from "./parameterField.fixture";

describe("a parameter holding a whole number", () => {
  it("takes whole numbers only when the value is stored as one", () => {
    renderField(LongParameterField, { dataType: "long" }, [ "3" ]);

    const input = screen.getByRole("textbox");
    expect(input).toHaveValue("3");
    // The keyboard a phone offers
    expect(input).toHaveAttribute("inputmode", "numeric");
  });

  it("refuses a decimal where a whole number is stored", () => {
    const { onChange } = renderField(LongParameterField, { dataType: "long" }, [ "3" ]);
    const input = screen.getByRole("textbox");

    fireEvent.change(input, { target: { value: "1.5" } });
    expect(onChange).not.toHaveBeenCalled();

    fireEvent.change(input, { target: { value: "-12" } });
    expect(onChange).toHaveBeenCalledWith([ "-12" ]);
  });

  it("hands what it shows on to be written once it is left, or when Enter is pressed", async () => {
    const { onCommit, user } = renderField(LongParameterField, { dataType: "long" }, [ "3" ]);

    await user.type(screen.getByRole("textbox"), "{enter}");
    await user.tab();

    expect(onCommit).toHaveBeenCalledTimes(2);
    expect(onCommit).toHaveBeenLastCalledWith([ "3" ]);
  });

  it("reports a cleared field as holding nothing at all, and is marked when a value is required", () => {
    const { onChange } = renderField(LongParameterField, { dataType: "long", minValues: 1 }, [ "3" ]);
    const input = screen.getByRole("textbox");

    expect(input).toBeRequired();
    fireEvent.change(input, { target: { value: "" } });

    expect(onChange).toHaveBeenCalledWith([]);
  });

  it("starts empty when it holds nothing", () => {
    renderField(LongParameterField, { dataType: "long" });

    expect(screen.getByRole("textbox")).toHaveValue("");
  });

  it("is shown as text when read only", () => {
    renderField(LongParameterField, { dataType: "long" }, [ "3" ], true);

    expect(screen.getByText("To state: 3")).toBeInTheDocument();
  });

  it("takes several values as chips, the way text does", () => {
    renderField(LongParameterField, { dataType: "long", maxValues: 0 }, [ "1", "2" ]);

    expect(screen.getAllByRole("button", { name: /^(1|2)$/ })).toHaveLength(2);
  });

  it("offers the entry's choices when it constrains them", () => {
    renderField(LongParameterField, { dataType: "long", options: STATES }, [ "DRAFT" ]);

    expect(screen.getByRole("combobox")).toHaveTextContent("Draft");
  });
});
