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
import { describe, expect, it, vi } from "vitest";

import { QuestionSet } from "@iap/submissions/requirements";
import { FORM_REQUIREMENT, QUESTION, type FormRequirement } from "@iap/submissions/submissionForm";

function requirement(overrides: Partial<FormRequirement> = {}): FormRequirement {
  return {
    name: "details",
    type: FORM_REQUIREMENT,
    label: "Request details",
    items: [
      {
        name: "duration",
        type: QUESTION,
        path: "details/duration",
        text: "How long?",
        dataType: "text",
        minAnswers: 0,
        maxAnswers: 1,
        options: [],
        value: [],
      },
    ],
    ...overrides,
  };
}

function draw(overrides: Partial<FormRequirement> = {}) {
  return render(
    <QuestionSet
      path="/Submissions/a/b/demo-1"
      requirement={requirement(overrides)}
      disabled={false}
      states={{}}
      onAnswered={vi.fn()}
      onChanged={vi.fn()}
    />
  );
}

describe("the questions a form requirement asks", () => {
  it("draws each of them", () => {
    draw();

    // Queried in the plural because a text field renders its label twice, once for the outline it
    // animates into
    expect(screen.getAllByText("How long?").length).toBeGreaterThan(0);
  });

  // The registry only offers this component for a requirement that has items, so this is the case
  // that arises from being rendered directly. Nothing to ask is not nothing to draw: an empty list
  // is a form that asks nothing yet, which is what the projection would be saying
  it("draws nothing rather than failing when there are none to draw", () => {
    const { container } = draw({ items: undefined });

    expect(screen.queryAllByText("How long?")).toHaveLength(0);
    expect(container).not.toBeEmptyDOMElement();
  });
});
