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

import QuestionText, { getQuestionTextId } from "@iap/submissions/answers/QuestionText";
import { QUESTION, type FormQuestion } from "@iap/submissions/submissionForm";

const question: FormQuestion = {
  name: "reason", type: QUESTION, path: "details/reason", text: "Why?",
  dataType: "text", required: true, multiple: false, options: [], value: [],
};

describe("QuestionText", () => {
  it("carries the id an input names as its label, and marks a required question", () => {
    render(<QuestionText question={question} />);

    const text = screen.getByText("Why? *");
    expect(text).toHaveAttribute("id", getQuestionTextId(question));
  });

  it("takes no id and no required mark when there is no input to label", () => {
    render(<QuestionText question={question} labelOnly />);

    expect(screen.getByText("Why?")).not.toHaveAttribute("id");
  });
});
