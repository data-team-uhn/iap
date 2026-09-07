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

import { createElement, useState } from "react";

import type { AnswerComponent } from "@iap/submissions/answerComponents";
import type { FormQuestion } from "@iap/submissions/submissionForm";

interface ControlledAnswerProps {
  component: AnswerComponent;
  question: FormQuestion;
  initial?: string[];
  onAnswered: (values: string[]) => void;
}

/**
 * An answer component with something holding its answer, which is what AnswerField does in the
 * application. The components are controlled: without a caller feeding `onChange` back in as
 * `values`, typing into one has nowhere to land, and a test would be asserting against an input
 * that never changed rather than against the component.
 */
export function ControlledAnswer({ component, question, initial = [], onAnswered }: ControlledAnswerProps) {
  const [ values, setValues ] = useState(initial);
  return createElement(component, { question, values, disabled: false, onChange: setValues, onAnswered });
}
