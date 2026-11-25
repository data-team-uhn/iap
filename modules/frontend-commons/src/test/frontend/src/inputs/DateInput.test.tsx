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

// Covers entering a day: what is reported and committed, and a day only partly entered.

import { fireEvent, screen } from "@testing-library/react";

import DateInput from "@iap/frontend-commons/inputs/DateInput";
import { renderValueInput } from "@iap/frontend-commons/inputs/valueInput.fixture";
import { DATE_PROBLEM, REQUIRED_PROBLEM } from "@iap/frontend-commons/inputs/values";

const renderInput = (props: Partial<Parameters<typeof DateInput>[0]> = {}) =>
  renderValueInput(DateInput, { label: "Due", values: [ "2026-09-29" ] }, props);

// The browser reports a day it cannot read yet as empty, with `badInput` set, and each edit as an input event
const reportValidity = (input: HTMLElement, badInput: boolean) => {
  Object.defineProperty(input, "validity", { value: { badInput }, configurable: true });
  fireEvent.input(input);
};
const typePartOfDay = (input: HTMLElement) => {
  fireEvent.change(input, { target: { value: "" } });
  reportValidity(input, true);
};
const emptyField = (input: HTMLElement) => reportValidity(input, false);

describe("DateInput", () => {
  it("reports the day picked, and nothing once cleared", () => {
    const { onChange, input } = renderInput();

    expect(input).toHaveAttribute("type", "date");
    fireEvent.change(input!, { target: { value: "2026-10-01" } });
    fireEvent.change(input!, { target: { value: "" } });

    expect(onChange.mock.calls).toEqual([ [ [ "2026-10-01" ] ], [ [] ] ]);
  });

  it("commits a changed day once, on Enter or when left", () => {
    const { onCommit, input, retype } = renderInput();

    fireEvent.blur(input!);
    retype([ "2026-09-30" ]);
    fireEvent.keyDown(input!, { key: "Enter" });
    fireEvent.blur(input!);

    expect(onCommit.mock.calls).toEqual([ [ [ "2026-09-30" ] ] ]);
  });

  it("holds no value while a day is partly entered, and flags that once the field is left", () => {
    const { onChange, input, retype } = renderInput();

    typePartOfDay(input!);
    expect(onChange).toHaveBeenCalledWith([]);
    retype([]);
    expect(screen.queryByText(DATE_PROBLEM)).not.toBeInTheDocument();
    fireEvent.blur(input!);

    expect(screen.getByText(DATE_PROBLEM)).toBeInTheDocument();
  });

  it("does not commit a partly entered day", () => {
    const { onCommit, input, retype } = renderInput();

    typePartOfDay(input!);
    retype([]);
    fireEvent.blur(input!);

    expect(onCommit).not.toHaveBeenCalled();
  });

  it("commits a day cleared over two visits to the field", () => {
    const { onCommit, input, retype } = renderInput();

    typePartOfDay(input!);
    retype([]);
    fireEvent.blur(input!);
    fireEvent.focus(input!);
    emptyField(input!);
    fireEvent.blur(input!);

    expect(onCommit.mock.calls).toEqual([ [ [] ] ]);
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
    expect(screen.getByText("Due: 2026-09-29")).toBeInTheDocument();
  });
});
