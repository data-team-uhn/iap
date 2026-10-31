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

import { fireEvent, render, screen } from "@testing-library/react";

import DateInput from "@iap/frontend-commons/inputs/DateInput";
import { REQUIRED_PROBLEM } from "@iap/frontend-commons/inputs/valueInput";

const renderInput = (props: Partial<Parameters<typeof DateInput>[0]> = {}) => {
  const onChange = vi.fn();
  const onCommit = vi.fn();
  render(<DateInput label="Due" values={[ "2026-09-29" ]} onChange={onChange} onCommit={onCommit} {...props} />);
  return { onChange, onCommit, input: screen.queryByLabelText(/Due/) };
};

describe("DateInput", () => {
  it("reports the day picked, and nothing once cleared", () => {
    const { onChange, input } = renderInput();

    expect(input).toHaveAttribute("type", "date");
    fireEvent.change(input!, { target: { value: "2026-10-01" } });
    fireEvent.change(input!, { target: { value: "" } });

    expect(onChange.mock.calls).toEqual([ [ [ "2026-10-01" ] ], [ [] ] ]);
  });

  it("commits when left or on Enter", () => {
    const { onCommit, input } = renderInput();

    fireEvent.keyDown(input!, { key: "Enter" });
    fireEvent.blur(input!);

    expect(onCommit.mock.calls).toEqual([ [ [ "2026-09-29" ] ], [ [ "2026-09-29" ] ] ]);
  });

  it("does not commit a half-typed day", () => {
    const { onCommit, input } = renderInput({ values: [] });
    Object.defineProperty(input, "validity", { value: { badInput: true } });

    fireEvent.blur(input!);

    expect(onCommit).not.toHaveBeenCalled();
  });

  it("finishes a value with no one listening", () => {
    const { input } = renderInput({ onCommit: undefined });

    fireEvent.blur(input!);

    expect(input).toHaveValue("2026-09-29");
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
