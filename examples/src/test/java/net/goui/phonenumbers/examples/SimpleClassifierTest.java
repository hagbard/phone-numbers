/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.examples;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static net.goui.phonenumber.MatchResult.INVALID;
import static net.goui.phonenumber.MatchResult.MATCHED;
import static net.goui.phonenumber.MatchResult.PARTIAL_MATCH;

import com.google.common.io.CharStreams;
import com.google.common.truth.Expect;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Optional;
import net.goui.phonenumber.DigitSequence;
import net.goui.phonenumber.MatchResult;
import net.goui.phonenumber.PhoneNumber;
import net.goui.phonenumber.PhoneNumberRegions;
import net.goui.phonenumber.PhoneNumbers;
import net.goui.phonenumber.testing.RegressionTester;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SimpleClassifierTest {
  @Rule public final Expect expect = Expect.create();

  private static final SimpleClassifier SIMPLE_CLASSIFIER = SimpleClassifier.getInstance();

  @Test
  public void testValidation() {
    // BBC enquiries.
    assertThat(SIMPLE_CLASSIFIER.match(e164("+442087438000"))).isEqualTo(MATCHED);
    assertThat(SIMPLE_CLASSIFIER.match(e164("+442087438"))).isEqualTo(PARTIAL_MATCH);
    assertThat(SIMPLE_CLASSIFIER.match(e164("+442087438000000")))
        .isEqualTo(MatchResult.EXCESS_DIGITS);

    assertThat(SIMPLE_CLASSIFIER.match(e164("+440000"))).isEqualTo(INVALID);
  }

  @Test
  public void testExampleNumber() {
    Optional<PhoneNumber> exampleNumber = SIMPLE_CLASSIFIER.getExampleNumber(seq("44"));
    assertThat(exampleNumber).hasValue(e164("+447400123456"));
    // Check the example number is considered valid.
    assertThat(SIMPLE_CLASSIFIER.match(exampleNumber.get())).isEqualTo(MATCHED);
  }

  @Test
  public void testRegionMatcher() {
    //A GB (U.K.) and JE (Jersey) number with the same prefix.
    assertThat(SIMPLE_CLASSIFIER.forRegion().match(e164("+447700112345"), "GB")).isEqualTo(MATCHED);
    assertThat(SIMPLE_CLASSIFIER.forRegion().match(e164("+447700312345"), "JE")).isEqualTo(MATCHED);
    // Both regions partially match on the prefix.
    assertThat(SIMPLE_CLASSIFIER.forRegion().match(e164("+447700"), "GB")).isEqualTo(PARTIAL_MATCH);
    assertThat(SIMPLE_CLASSIFIER.forRegion().match(e164("+447700"), "JE")).isEqualTo(PARTIAL_MATCH);
    // And both regions are the possible values for the prefix.
    assertThat(SIMPLE_CLASSIFIER.forRegion().getPossibleValues(e164("+447700")))
        .containsExactly("GB", "JE");
  }

  @Test
  public void testRegionInfo() {
    PhoneNumberRegions<String> regionInfo = SIMPLE_CLASSIFIER.getRegionInfo();
    assertThat(regionInfo.getRegions(seq("44"))).containsExactly("GB", "GG", "IM", "JE").inOrder();
    assertThat(regionInfo.getRegions(seq("41"))).containsExactly("CH");
    assertThat(regionInfo.getRegions(seq("888"))).containsExactly("001");

    assertThat(regionInfo.getCallingCode("IM")).hasValue(seq("44"));
    assertThat(regionInfo.getCallingCode("CH")).hasValue(seq("41"));
    // Special case since there are several calling codes associated with "001".
    assertThat(regionInfo.getCallingCode("001")).isEmpty();
  }

  @Test
  public void testFormatting() {
    assertThat(SIMPLE_CLASSIFIER.national().format(e164("+442087438000")))
        .isEqualTo("020 8743 8000");
    assertThat(SIMPLE_CLASSIFIER.international().format(e164("+442087438000")))
        .isEqualTo("+44 20 8743 8000");
  }

  @Test
  public void testGoldenData() throws IOException {
    RegressionTester regressionTester =
        RegressionTester.forClassifier(SIMPLE_CLASSIFIER.rawClassifierForTesting(), expect);
    try (Reader goldenData =
        new InputStreamReader(
            checkNotNull(
                SimpleClassifierTest.class.getResourceAsStream("/simple_golden_data.json")),
            UTF_8)) {
      regressionTester.assertGoldenData(CharStreams.toString(goldenData));
    }
  }

  private static DigitSequence seq(String seq) {
    return DigitSequence.parse(seq);
  }

  private static PhoneNumber e164(String e164) {
    return PhoneNumbers.fromE164(e164);
  }
}