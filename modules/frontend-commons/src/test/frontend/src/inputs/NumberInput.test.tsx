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

// Covers typing a number: which text is flagged and when, and what is reported and committed.

import { fireEvent, screen } from "@testing-library/react";

import NumberInput from "@iap/frontend-commons/inputs/NumberInput";
import { renderValueInput } from "@iap/frontend-commons/inputs/valueInput.fixture";
import { REQUIRED_PROBLEM } from "@iap/frontend-commons/inputs/values";

const renderInput = (props: Partial<Parameters<typeof NumberInput>[0]> = {}) =>
  renderValueInput(NumberInput, { label: "Age", kind: "whole", values: [ "12" ] }, props);

describe("NumberInput", () => {
  it("takes a whole number from a numeric keypad, as it is typed", () => {
    const { onChange, input } = renderInput({ values: [] });

    expect(input).toHaveAttribute("inputmode", "numeric");
    fireEvent.change(input!, { target: { value: "-" } });
    fireEvent.change(input!, { target: { value: "-4" } });

    expect(onChange.mock.calls).toEqual([ [ [ "-" ] ], [ [ "-4" ] ] ]);
  });

  it("passes on every keystroke, and flags at once text that cannot become a number", () => {
    const { onChange, input, retype } = renderInput();

    fireEvent.change(input!, { target: { value: "12x" } });
    expect(onChange.mock.calls).toEqual([ [ [ "12x" ] ] ]);
    expect(screen.queryByText("Enter a whole number.")).not.toBeInTheDocument();
    retype([ "12x" ]);

    expect(screen.getByText("Enter a whole number.")).toBeInTheDocument();
    fireEvent.blur(input!);
    expect(screen.getByText("Enter a whole number.")).toBeInTheDocument();
  });

  it("flags at once a number too large to hold", () => {
    renderInput({ values: [ "12345678901234567890" ] });

    expect(screen.getByText("Enter a whole number.")).toBeInTheDocument();
  });

  it("flags a decimal comma rather than dropping it", () => {
    renderInput({ kind: "decimal", values: [ "2," ] });

    expect(screen.getByText("Enter a number.")).toBeInTheDocument();
  });

  it("takes a number pasted with spaces around it", () => {
    const { onCommit, input } = renderInput({ values: [ " 42 " ] });

    fireEvent.blur(input!);

    expect(screen.queryByText("Enter a whole number.")).not.toBeInTheDocument();
    expect(onCommit).not.toHaveBeenCalled();
  });

  it("takes a decimal from a decimal keypad, and flags one left unfinished only once the field is left", () => {
    const { onCommit, input, retype } = renderInput({ kind: "decimal" });

    expect(input).toHaveAttribute("inputmode", "decimal");
    retype([ "12.5e" ]);
    expect(screen.queryByText("Enter a number.")).not.toBeInTheDocument();
    fireEvent.blur(input!);

    expect(screen.getByText("Enter a number.")).toBeInTheDocument();
    expect(onCommit).not.toHaveBeenCalled();
  });

  it("shows a stored value that is no number as written, and lets it be typed over", () => {
    const { onChange, input } = renderInput({ values: [ "many" ] });

    expect(input).toHaveValue("many");
    expect(screen.getByText("Enter a whole number.")).toBeInTheDocument();
    fireEvent.change(input!, { target: { value: "man" } });

    expect(onChange).toHaveBeenCalledWith([ "man" ]);
  });

  it("reports nothing once emptied", () => {
    const { onChange, input } = renderInput();

    fireEvent.change(input!, { target: { value: "" } });

    expect(onChange).toHaveBeenCalledWith([]);
  });

  it("commits a changed number once, on Enter or when left", () => {
    const { onCommit, input, retype } = renderInput();

    fireEvent.blur(input!);
    retype([ "13" ]);
    fireEvent.keyDown(input!, { key: "Enter" });
    fireEvent.blur(input!);

    expect(onCommit.mock.calls).toEqual([ [ [ "13" ] ] ]);
  });

  it("flags a missing value only once the field is left", () => {
    const { input } = renderInput({ values: [], required: true });

    expect(screen.queryByText(REQUIRED_PROBLEM)).not.toBeInTheDocument();
    fireEvent.blur(input!);
    expect(screen.getByText(REQUIRED_PROBLEM)).toBeInTheDocument();
  });

  it("shows the value as text when read-only", () => {
    const { input } = renderInput({ readOnly: true });

    expect(input).toBeNull();
    expect(screen.getByText("Age: 12")).toBeInTheDocument();
  });
});
