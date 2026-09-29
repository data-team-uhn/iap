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

import AppliesWhenLine from "@iap/conditions/AppliesWhenLine";

describe("AppliesWhenLine", () => {
  it("says when something applies", () => {
    render(<AppliesWhenLine>Only when it rains</AppliesWhenLine>);

    expect(screen.getByText("Only when it rains")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("is pressed to change it, where it can be changed", () => {
    const onEdit = vi.fn();
    render(<AppliesWhenLine onEdit={onEdit}>Only when it rains</AppliesWhenLine>);

    const line = screen.getByRole("button", { name: "Change when it applies" });
    expect(line).toHaveAccessibleDescription("Only when it rains");
    fireEvent.click(line);
    expect(onEdit).toHaveBeenCalled();
  });
});
