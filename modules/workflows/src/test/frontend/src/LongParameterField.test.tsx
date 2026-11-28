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
  it("shows the number it holds, offering a keyboard of digits", () => {
    renderField(LongParameterField, { dataType: "long" }, [ "3" ]);

    const input = screen.getByRole("textbox");
    expect(input).toHaveValue("3");
    expect(input).toHaveAttribute("inputmode", "numeric");
  });

  it("flags a decimal where a whole number is stored", () => {
    renderField(LongParameterField, { dataType: "long" }, [ "3" ]);

    fireEvent.change(screen.getByRole("textbox"), { target: { value: "1.5" } });

    expect(screen.getByText(/Enter a whole number\./)).toBeInTheDocument();
  });

  it("does not hand a decimal on to be written when left", () => {
    const { onCommit } = renderField(LongParameterField, { dataType: "long" }, [ "3" ]);
    const input = screen.getByRole("textbox");

    fireEvent.change(input, { target: { value: "1.5" } });
    fireEvent.blur(input);

    expect(onCommit).not.toHaveBeenCalled();
  });

  it("takes a sign either way while it is typed", () => {
    const { onChange } = renderField(LongParameterField, { dataType: "long" }, [ "3" ]);
    const input = screen.getByRole("textbox");

    fireEvent.change(input, { target: { value: "-12" } });
    fireEvent.change(input, { target: { value: "+" } });

    expect(onChange.mock.calls).toEqual([ [ [ "-12" ] ], [ [ "+" ] ] ]);
  });

  it("takes a number written with a plus sign, as the server does", () => {
    renderField(LongParameterField, { dataType: "long", minValues: 1 }, [ "+5" ]);

    expect(screen.getByRole("textbox")).toHaveValue("+5");
    expect(screen.queryByText(/will be ignored/)).not.toBeInTheDocument();
  });

  it("flags a sign with no digits after it", () => {
    renderField(LongParameterField, { dataType: "long" }, [ "-" ]);

    expect(screen.getByText('"-" is not a whole number, so it will be ignored.')).toBeInTheDocument();
  });

  it("hands a changed number on to be written once, on Enter or when left", async () => {
    const { onCommit, user } = renderField(LongParameterField, { dataType: "long" }, [ "3" ]);

    await user.type(screen.getByRole("textbox"), "4{enter}");
    await user.tab();

    expect(onCommit).toHaveBeenCalledExactlyOnceWith([ "34" ]);
  });

  it("reports a cleared field as holding nothing at all", () => {
    const { onChange } = renderField(LongParameterField, { dataType: "long" }, [ "3" ]);

    fireEvent.change(screen.getByRole("textbox"), { target: { value: "" } });

    expect(onChange).toHaveBeenCalledWith([]);
  });

  it("is marked when a value is required", () => {
    renderField(LongParameterField, { dataType: "long", minValues: 1 }, [ "3" ]);

    expect(screen.getByRole("textbox")).toBeRequired();
  });

  it("starts empty when it holds nothing", () => {
    renderField(LongParameterField, { dataType: "long" });

    expect(screen.getByRole("textbox")).toHaveValue("");
  });

  it("is shown as text when read only", () => {
    renderField(LongParameterField, { dataType: "long" }, [ "3" ], { readOnly: true });

    expect(screen.getByText("To state")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
  });

  it("takes several values as chips, the way text does", () => {
    renderField(LongParameterField, { dataType: "long", maxValues: 0 }, [ "1", "2" ]);

    expect(screen.getAllByRole("button", { name: /^(1|2)$/ })).toHaveLength(2);
  });

  it("offers the entry's choices when it constrains them", () => {
    renderField(LongParameterField, { dataType: "long", options: STATES }, [ "DRAFT" ]);

    expect(screen.getByRole("combobox")).toHaveTextContent("Draft");
  });

  it("shows a value that is no number as written, and says it will be ignored", () => {
    renderField(LongParameterField, { dataType: "long", minValues: 1 }, [ "abc" ]);

    expect(screen.getByRole("textbox")).toHaveValue("abc");
    expect(screen.getByText('"abc" is not a whole number, so it will be ignored. Enter a whole number.'))
      .toBeInTheDocument();
  });

  it("keeps flagging a value that is no number as it is edited and left", async () => {
    const { onChange, user } = renderField(LongParameterField, { dataType: "long", minValues: 1 }, [ "abc" ]);
    const input = screen.getByRole("textbox");

    fireEvent.change(input, { target: { value: "ab" } });
    await user.click(input);
    await user.tab();

    expect(onChange).toHaveBeenCalledWith([ "ab" ]);
    expect(screen.getByText('"ab" is not a whole number, so it will be ignored. Enter a whole number.'))
      .toBeInTheDocument();
  });

  it("stays a text field while a value that is no number is typed over, and flags what is no number", async () => {
    const { user } = renderField(LongParameterField, { dataType: "long" }, [ "abc" ]);
    const input = screen.getByRole("textbox");

    await user.clear(input);
    expect(input).not.toHaveAttribute("type", "number");
    await user.type(input, "12x");

    expect(input).toHaveValue("12x");
    expect(input).toHaveFocus();
    expect(screen.getByText(/Enter a whole number\./)).toBeInTheDocument();
  });

  it("flags digits of another script, which the server does not read", () => {
    renderField(LongParameterField, { dataType: "long" }, [ "\uFF11\uFF12" ]);

    expect(screen.getByText('"\uFF11\uFF12" is not a whole number, so it will be ignored. Enter a whole number.'))
      .toBeInTheDocument();
  });

  it("flags a number too large to be stored", () => {
    renderField(LongParameterField, { dataType: "long" }, [ "9223372036854775808" ]);

    expect(screen.getByText('"9223372036854775808" is not a whole number, so it will be ignored. Enter a whole number.'))
      .toBeInTheDocument();
  });

  it("asks for a required value once it has been left empty", async () => {
    const { user } = renderField(LongParameterField, { dataType: "long", minValues: 1 });

    await user.click(screen.getByRole("textbox"));
    await user.tab();

    expect(screen.getByText("A value is required.")).toBeInTheDocument();
  });

  it("asks for nothing when an optional one is left empty", async () => {
    const { user } = renderField(LongParameterField, { dataType: "long" });

    await user.click(screen.getByRole("textbox"));
    await user.tab();

    expect(screen.queryByText("A value is required.")).not.toBeInTheDocument();
  });

  it("takes the extremes of what can be stored", () => {
    renderField(LongParameterField, { dataType: "long", maxValues: 0 },
      [ "9223372036854775807", "-9223372036854775808", "007" ]);

    expect(screen.queryByText(/will be ignored/)).not.toBeInTheDocument();
  });
});
