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

import { createElement, useContext, useEffect, useRef, useState } from "react";

import AutoAwesomeOutlined from "@mui/icons-material/AutoAwesomeOutlined";
import { Box, Button, CircularProgress, Stack, Typography } from "@mui/material";

import { getAnswerComponent } from "./answerComponents";
import { useAnswerComponents } from "./answers";
import { questionLabel } from "./answers/label";
import { type FindingFocus, FindingFocusContext, FindingSeverity, FindingText } from "./PreSendDialog";
import SaveStatus, { type SaveState } from "./SaveStatus";

import type { FormQuestion } from "./submissionForm";

interface AnswerFieldProps {
  question: FormQuestion;
  state: SaveState;
  error?: string;
  disabled?: boolean;
  // Called when the answer is finished, not on each keystroke
  onAnswered: (values: string[]) => void;
}

// One question and its answer. The input is drawn by the registered answer component that fits the
// question best. This keeps what every kind of answer shares: following the saved answer, noticing a
// change, and reporting what the save does.
function AnswerField({ question, state, error, disabled, onAnswered }: AnswerFieldProps) {
  const ready = useAnswerComponents();
  const [ draft, setDraft ] = useState(question.value);
  const [ focused, setFocused ] = useState(false);
  // Follows the saved answer, which changes each time the editor reads the form again. It is adjusted
  // during render, which is how React follows a prop without an effect.
  //
  // Compared by content, since each read returns fresh arrays. Compared by identity, every save would
  // reset every field, including one somebody is typing in.
  // Joined on a character an answer cannot contain, so that ["a", "b"] and ["a b"] stay distinct.
  const answered = question.value.join("\u0000");
  const [ seen, setSeen ] = useState(answered);
  // Held back while the field has focus. Otherwise a submitter who empties a field and types again
  // would lose the new text when the read after the clearing lands. `seen` is left alone too, so the
  // new value is applied on the first render after they leave.
  if (seen !== answered && !focused) {
    setSeen(answered);
    setDraft(question.value);
  }

  const changed = (values: string[]) =>
    values.length !== question.value.length || values.some((value, index) => value !== question.value[index]);
  const submit = (values: string[]) => {
    setDraft(values);
    if (changed(values)) {
      onAnswered(values);
    }
  };

  // PRETOTYPE: the AI finding the team came to fix, when it is about this question
  const focus = useContext(FindingFocusContext);
  const pointed = focus?.question === question.path ? focus : undefined;
  const here = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!pointed) {
      return undefined;
    }
    // Again once the form around it has settled, since the parts loading after it move it down
    const show = () => here.current?.scrollIntoView({ block: "start", behavior: "smooth" });
    const soon = setTimeout(show, 300);
    const settled = setTimeout(show, 1500);
    return () => {
      clearTimeout(soon);
      clearTimeout(settled);
    };
  }, [ pointed ]);

  if (!ready) {
    // Not the "cannot be answered here" message below. Until the components have loaded, none is
    // registered, and the question may yet have one.
    return (
      <Box>
        <Typography variant="subtitle2">{questionLabel(question)}</Typography>
        <CircularProgress size={16} aria-label="Loading the answer field" />
      </Box>
    );
  }

  const Answer = getAnswerComponent(question);
  if (!Answer) {
    // Shown rather than skipped, so that the form does not look complete when it is not
    return (
      <Box>
        <Typography variant="subtitle2">{questionLabel(question)}</Typography>
        <Typography variant="placeholder">
          {`This question asks for ${question.dataType}, which cannot be answered here.`}
        </Typography>
      </Box>
    );
  }

  return (
    <Box ref={here} sx={{ scrollMarginTop: theme => `calc(var(--iap-frame-top, 0px) + ${theme.spacing(2)})` }}>
      {pointed && <FindingCallout focus={pointed} />}
      <Box
        sx={{ display: "flex", alignItems: "flex-start", gap: 1 }}
        onFocusCapture={() => setFocused(true)}
        onBlurCapture={() => setFocused(false)}
      >
        {/* react-hooks/static-components refuses <Answer/> for a component chosen during render */}
        <Box sx={{ flexGrow: 1 }}>
          {createElement(Answer, {
            question,
            values: draft,
            disabled: Boolean(disabled),
            onChange: setDraft,
            onAnswered: submit,
          })}
        </Box>
        <Box sx={{ pt: 2 }}><SaveStatus state={state} error={error} /></Box>
      </Box>
    </Box>
  );
}

// PRETOTYPE: the AI finding the team came to fix, above the question it is about
function FindingCallout({ focus }: { focus: FindingFocus }) {
  return (
    <Stack spacing={1} sx={{ mb: 1.5, p: 1.5, borderRadius: 1, bgcolor: "action.hover",
      borderInlineStart: "3px solid", borderInlineStartColor: "assistant.main" }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
        <AutoAwesomeOutlined sx={{ fontSize: "1rem", color: "assistant.main" }} />
        <Typography variant="body2" sx={{ color: "assistant.main", fontWeight: "fontWeightMedium", flex: 1 }}>
          The AI pre-review
        </Typography>
        <FindingSeverity severity={focus.finding.severity} />
      </Stack>
      <FindingText finding={focus.finding} />
      <Box>
        <Button size="small" onClick={focus.onBack}>Back to the findings</Button>
      </Box>
    </Stack>
  );
}

export default AnswerField;
