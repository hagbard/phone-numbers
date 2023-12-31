/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

  This program and the accompanying materials are made available under the terms of the
  Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
  Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

  SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.service.proto;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import net.goui.phonenumbers.DigitSequence;
import net.goui.phonenumbers.metadata.ParserData;
import net.goui.phonenumbers.proto.Metadata.CallingCodeProto;
import net.goui.phonenumbers.proto.Metadata.NationalNumberDataProto;

final class CallingCodeClassifier {

  static CallingCodeClassifier from(
      CallingCodeProto callingCodeProto, int typeCount, IntFunction<String> tokenDecoder) {
    ImmutableList<MatcherFunction> matchers =
        callingCodeProto.getMatcherDataList().stream()
            .map(MatcherFunction::fromProto)
            .collect(toImmutableList());
    // For now, assume that if there are no validity matcher indices, we just use 0.
    Function<List<Integer>, MatcherFunction> matcherFactory =
        indices -> indices.isEmpty() ? matchers.get(0) : combinedMatcherOf(matchers, indices);

    MatcherFunction validityMatcher =
        matcherFactory.apply(callingCodeProto.getValidityMatcherIndexList());

    List<NationalNumberDataProto> nnd = callingCodeProto.getNationalNumberDataList();
    checkState(
        nnd.size() == typeCount,
        "invalid phone number metadata (unexpected national number data): %s",
        callingCodeProto);
    ImmutableList<TypeClassifier> typeClassifiers =
        IntStream.range(0, typeCount)
            .mapToObj(i -> TypeClassifier.create(nnd.get(i), tokenDecoder, matcherFactory))
            .collect(toImmutableList());

    // An unset region count implies 1 region.
    int regionCount = Math.max(callingCodeProto.getRegionCount(), 1);
    int mainRegionIndex = callingCodeProto.getMainRegion();
    ImmutableSet<String> regions =
        IntStream.range(mainRegionIndex, mainRegionIndex + regionCount)
            .mapToObj(tokenDecoder)
            .collect(toImmutableSet());
    ImmutableList<DigitSequence> exampleNumbers =
        callingCodeProto.getExampleNumberList().stream()
            .map(DigitSequence::parse)
            .collect(toImmutableList());
    ImmutableSet<DigitSequence> nationalPrefixes =
        callingCodeProto.getNationalPrefixList().stream()
            .map(tokenDecoder::apply)
            .map(DigitSequence::parse)
            .collect(toImmutableSet());
    ParserData parserData =
        ParserData.create(
            regions,
            exampleNumbers,
            nationalPrefixes,
            callingCodeProto.getNationalPrefixOptional());

    return new CallingCodeClassifier(validityMatcher, typeClassifiers, parserData);
  }

  private static MatcherFunction combinedMatcherOf(
      ImmutableList<MatcherFunction> matchers, List<Integer> indices) {
    return MatcherFunction.combine(indices.stream().map(matchers::get).collect(toImmutableList()));
  }

  private final MatcherFunction validityMatcher;
  private final ImmutableList<TypeClassifier> typeClassifiers;
  private final ParserData parserData;

  private CallingCodeClassifier(
      MatcherFunction validityMatcher,
      ImmutableList<TypeClassifier> typeClassifiers,
      ParserData parserData) {
    this.validityMatcher = validityMatcher;
    this.typeClassifiers = typeClassifiers;
    this.parserData = parserData;
  }

  public MatcherFunction getValidityMatcher() {
    return validityMatcher;
  }

  TypeClassifier getTypeClassifier(int typeIndex) {
    return typeClassifiers.get(typeIndex);
  }

  public ParserData getParserData() {
    return parserData;
  }
}
