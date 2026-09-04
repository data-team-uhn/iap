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

import FileAnswer from "@iap/submissions/answers/FileAnswer";
import { QUESTION, type FormQuestion } from "@iap/submissions/submissionForm";

describe("FileAnswer", () => {
  // Saying so beats both alternatives: leaving the question out reads as a complete form, and a text
  // box in its place stores a filename that nothing points at
  it("says a file cannot be attached yet, and offers nothing that would pretend otherwise", () => {
    const question: FormQuestion = {
      name: "note", type: QUESTION, path: "details/note", text: "Attach the note",
      dataType: "file", minAnswers: 0, maxAnswers: 1, options: [], value: [],
    };

    render(<FileAnswer question={question} values={[]} disabled={false}
      onChange={vi.fn()} onAnswered={vi.fn()} />);

    expect(screen.getByText("Attach the note")).toBeInTheDocument();
    expect(screen.getByText(/not available yet/)).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });
});
