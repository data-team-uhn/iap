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

import { Box, Stack, Typography } from "@mui/material";

import AnswerField from "../AnswerField";
import { isQuestion } from "../submissionForm";

import type { FieldState } from "../requirementComponents";
import type { FormItem, FormQuestion } from "../submissionForm";

// The questions of a form or a section, with sections drawn as their own headed block.
function Items({ items, disabled, states, onAnswered }: {
  items: FormItem[];
  disabled: boolean;
  states: Record<string, FieldState | undefined>;
  onAnswered: (question: FormQuestion, values: string[]) => void;
}) {
  return (
    <Stack spacing={2}>
      { items.map(item => isQuestion(item)
        ? (
          <AnswerField
            key={item.path}
            question={item}
            disabled={disabled}
            state={states[item.path]?.state ?? "idle"}
            error={states[item.path]?.error}
            onAnswered={values => onAnswered(item, values)}
          />
        )
        : (
          <Box key={item.name}>
            <Typography variant="subtitle1">{item.label || item.name}</Typography>
            { item.description && (
              <Typography variant="body2" color="text.secondary">{item.description}</Typography>
            ) }
            <Box sx={{ pl: 2, pt: 1 }}>
              <Items items={item.items} disabled={disabled} states={states} onAnswered={onAnswered} />
            </Box>
          </Box>
        )) }
    </Stack>
  );
}

export default Items;
