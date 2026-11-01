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
package io.uhndata.iap.submissions.internal;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.workflows.api.InvalidPayloadException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AnswerType}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class AnswerTypeTest
{
    @Test
    void readsTheTypeOffTheQuestionsDataType()
    {
        assertEquals(AnswerType.WHOLE_NUMBER, AnswerType.of(question("long")));
        assertEquals(AnswerType.NUMBER, AnswerType.of(question("double")));
        assertEquals(AnswerType.YES_OR_NO, AnswerType.of(question("boolean")));
        assertEquals(AnswerType.TEXT, AnswerType.of(question("date")));
        assertEquals(AnswerType.TEXT, AnswerType.of(question("text")));
        assertEquals(AnswerType.TEXT, AnswerType.of(question("something new")));
    }

    @Test
    void storesWholeNumbersInEveryWayTheFormWritesThem() throws InvalidPayloadException
    {
        final Object[] stored =
            AnswerType.WHOLE_NUMBER.store(question("long"), new String[] {"3", "3.0", "1e3", " 4 "});

        assertEquals(Long[].class, stored.getClass());
        assertArrayEquals(new Long[] {3L, 3L, 1000L, 4L}, stored);
    }

    @Test
    void refusesWhatIsNotAWholeNumber()
    {
        for (final String notWhole : new String[] {"3.5", "three", "99999999999999999999"}) {
            final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
                () -> AnswerType.WHOLE_NUMBER.store(question("long"), new String[] {notWhole}), notWhole);
            assertEquals("The answer to \"How many?\" must be a whole number.", refusal.getMessage());
        }
    }

    @Test
    void storesFiniteNumbersAndRefusesTheRest() throws InvalidPayloadException
    {
        assertArrayEquals(new Double[] {2.5, 1000.0},
            AnswerType.NUMBER.store(question("double"), new String[] {"2.50", "1e3"}));
        for (final String notANumber : new String[] {"NaN", "Infinity", "half"}) {
            final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
                () -> AnswerType.NUMBER.store(question("double"), new String[] {notANumber}), notANumber);
            assertEquals("The answer to \"How many?\" must be a number.", refusal.getMessage());
        }
    }

    @Test
    void storesYesOrNoAndRefusesAnythingElse() throws InvalidPayloadException
    {
        assertArrayEquals(new Boolean[] {true, false},
            AnswerType.YES_OR_NO.store(question("boolean"), new String[] {"true", "false"}));
        for (final String notYesOrNo : new String[] {"maybe", "True", "1"}) {
            assertThrows(InvalidPayloadException.class,
                () -> AnswerType.YES_OR_NO.store(question("boolean"), new String[] {notYesOrNo}), notYesOrNo);
        }
    }

    @Test
    void keepsTextAndDatesAsSubmitted() throws InvalidPayloadException
    {
        final Object[] stored = AnswerType.TEXT.store(question("date"), new String[] {"2026-11-02"});

        assertEquals(String[].class, stored.getClass());
        assertArrayEquals(new String[] {"2026-11-02"}, stored);
    }

    @Test
    void clearingStoresNoValuesOfTheType() throws InvalidPayloadException
    {
        assertArrayEquals(new Long[0], AnswerType.WHOLE_NUMBER.store(question("long"), new String[0]));
    }

    @Test
    void numbersMatchHoweverTheyAreWritten()
    {
        assertTrue(AnswerType.NUMBER.matches("2.50", "2.5"));
        assertTrue(AnswerType.WHOLE_NUMBER.matches("007", "7"));
        assertFalse(AnswerType.NUMBER.matches("2.5", "3"));
        assertFalse(AnswerType.NUMBER.matches("half", "0.5"));
    }

    @Test
    void textAndYesOrNoMatchOnlyAsWritten()
    {
        assertTrue(AnswerType.TEXT.matches("Laptop", "Laptop"));
        assertFalse(AnswerType.TEXT.matches("Laptop", "laptop"));
        assertFalse(AnswerType.YES_OR_NO.matches("true", "false"));
    }

    private static Question question(final String dataType)
    {
        final Question question = Mockito.mock(Question.class);
        Mockito.when(question.getDataType()).thenReturn(dataType);
        Mockito.when(question.getText()).thenReturn("How many?");
        return question;
    }
}
