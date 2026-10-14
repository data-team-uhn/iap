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

import AnswerRow from "@iap/submissions/answers/AnswerRow";
import TextAnswer from "@iap/submissions/answers/TextAnswer";
import { QUESTION, type FormQuestion } from "@iap/submissions/submissionForm";

describe("AnswerRow", () => {
  it("adds no wrapper when nothing goes beside the answer", () => {
    const { container } = render(<AnswerRow><input aria-label="answer" /></AnswerRow>);

    expect(container.firstElementChild).toBe(screen.getByLabelText("answer"));
  });

  it("shows what goes beside the answer next to it", () => {
    const question: FormQuestion = {
      name: "reason", type: QUESTION, path: "details/reason", text: "Why?",
      dataType: "text", required: false, multiple: false, options: [], value: [],
    };
    render(<TextAnswer question={question} values={[]} disabled={false} onChange={vi.fn()} onAnswered={vi.fn()}
      aside={<button type="button">Check</button>} />);

    expect(screen.getByLabelText(/Why/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Check" })).toBeInTheDocument();
  });
});
