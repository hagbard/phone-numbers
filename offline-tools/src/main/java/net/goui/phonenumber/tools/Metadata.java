/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.FileVisitOption.FOLLOW_LINKS;
import static net.goui.phonenumber.tools.ClassifierType.VALIDITY;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.i18n.phonenumbers.metadata.DigitSequence;
import com.google.i18n.phonenumbers.metadata.RangeTree;
import com.google.i18n.phonenumbers.metadata.model.MetadataTableSchema;
import com.google.i18n.phonenumbers.metadata.model.RangesTableSchema;
import com.google.i18n.phonenumbers.metadata.table.CsvParser;
import com.google.i18n.phonenumbers.metadata.table.CsvSchema;
import com.google.i18n.phonenumbers.metadata.table.CsvTable;
import com.google.i18n.phonenumbers.metadata.table.RangeTable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.checkerframework.checker.nullness.qual.Nullable;

@AutoValue
abstract class Metadata {
  private static final Pattern IS_OVERLAP_PATH =
      Pattern.compile("(metadata|[0-9]{1,3}/[^/]+)\\.csv");

  public static final ImmutableSet<ClassifierType> DEFAULT_BASE_TYPES = ImmutableSet.of(
      ClassifierType.TYPE,
      ClassifierType.AREA_CODE_LENGTH,
      ClassifierType.TARIFF,
      ClassifierType.REGION);

  static final class Builder {
    private final CsvTable<DigitSequence> root;
    private final ImmutableMap.Builder<DigitSequence, RangeMap> callingCodeMap =
        ImmutableMap.builder();

    private Builder(CsvTable<DigitSequence> root) {
      this.root = root;
    }

    @CanIgnoreReturnValue
    Builder put(DigitSequence cc, RangeMap rangeMap) {
      callingCodeMap.put(cc, rangeMap);
      return this;
    }

    Metadata build() {
      return create(root, callingCodeMap.buildOrThrow());
    }
  }

  static Builder builder(CsvTable<DigitSequence> root) {
    return new Builder(root);
  }

  private static Metadata create(
      CsvTable<DigitSequence> root, ImmutableMap<DigitSequence, RangeMap> callingCodeMap) {
    checkArgument(!callingCodeMap.isEmpty(), "must have at least one calling code: %s", root);
    RangeMap firstMap = callingCodeMap.values().asList().get(0);
    // All maps must share the same types in the same order.
    ImmutableList<ClassifierType> expectedTypesList = firstMap.getTypes().asList();
    for (RangeMap map : callingCodeMap.values()) {
      checkArgument(map.getTypes().asList().equals(expectedTypesList));
    }
    return new AutoValue_Metadata(root, expectedTypesList, callingCodeMap);
  }

  private static class TableLoader implements AutoCloseable {
    @Nullable private final ZipFile zip;
    private final ImmutableMap<String, Path> overlayMap;
    private final CsvParser csvParser;

    private TableLoader(String zipPath, String overlayDirPath, char overlaySeparator)
        throws IOException {
      this.zip = !zipPath.isEmpty() ? new ZipFile(zipPath) : null;
      this.overlayMap = readOverlayFiles(overlayDirPath);
      this.csvParser = CsvParser.withSeparator(overlaySeparator).trimWhitespace();
    }

    <T> CsvTable<T> load(String path, CsvSchema<T> schema) throws IOException {
      Path overlayPath = overlayMap.get(path);
      if (overlayPath != null) {
        return loadTableFromFile(Paths.get(path), schema, csvParser);
      }
      checkState(
          zip != null,
          "cannot find metadata path in overlay directory, and no zip file was specified: %s",
          path);
      return loadTableFromZipEntry(zip, path, schema);
    }

    private static ImmutableMap<String, Path> readOverlayFiles(String overlayDirPath)
        throws IOException {
      ImmutableMap<String, Path> overlayMap = ImmutableMap.of();
      if (!overlayDirPath.isEmpty()) {
        Path overlayDir = Paths.get(overlayDirPath);
        try (Stream<Path> files = Files.walk(overlayDir, 2, FOLLOW_LINKS)) {
          // Files are resolved against the root directory. Key is relative path string.
          overlayMap =
              files
                  .filter(f -> IS_OVERLAP_PATH.matcher(f.toString()).matches())
                  .collect(toImmutableMap(Object::toString, Path::toAbsolutePath));
        }
      }
      return overlayMap;
    }

    @Override
    public void close() throws IOException {
      if (zip != null) {
        zip.close();
      }
    }
  }

  public static Metadata load(
      String zipFilePath,
      String overlayDirPath,
      String csvSeparator,
      Set<ClassifierType> classifierTypes,
      Function<RangeMap, RangeMap> transformer)
      throws IOException {

    checkArgument(
        !zipFilePath.isEmpty() || !overlayDirPath.isEmpty(),
        "Must specify a zip file path or an overlay directory (or both)");
    checkArgument(
        csvSeparator.length() == 1,
        "bad CSV separator for overlay files (must be single char): %s",
        csvSeparator);

    CsvTable<DigitSequence> root;
    ImmutableMap.Builder<DigitSequence, RangeMap> callingCodeMap = ImmutableMap.builder();
    try (TableLoader loader =
        new TableLoader(zipFilePath, overlayDirPath, csvSeparator.charAt(0))) {
      root = loader.load("metadata/metadata.csv", MetadataTableSchema.SCHEMA);
      for (DigitSequence cc : root.getKeys()) {
        String name = "metadata/" + cc + "/ranges.csv";
        RangeTable rangeTable =
            RangesTableSchema.toRangeTable(loader.load(name, RangesTableSchema.SCHEMA));
        RangeMap rawRangeMap = getRangeMapForTable(rangeTable, classifierTypes);
        callingCodeMap.put(cc, transformer.apply(rawRangeMap));
      }
    }
    return create(root, callingCodeMap.buildOrThrow());
  }

  private static <T> CsvTable<T> loadTableFromZipEntry(
      ZipFile zip, String name, CsvSchema<T> schema) throws IOException {
    try (Reader reader = new InputStreamReader(zip.getInputStream(zip.getEntry(name)))) {
      return CsvTable.importCsv(schema, reader);
    }
  }

  private static <T> CsvTable<T> loadTableFromFile(
      Path path, CsvSchema<T> schema, CsvParser csvParser) throws IOException {
    try (Reader reader = Files.newBufferedReader(path, UTF_8)) {
      return CsvTable.importCsv(schema, reader, csvParser);
    }
  }

  private static RangeMap getRangeMapForTable(
      RangeTable table, Set<ClassifierType> classifierTypes) {
    RangeMap.Builder builder = RangeMap.builder();
    for (ClassifierType type : classifierTypes) {
      // NOTE: We could permit users to reduce built in types to "classifier only" if requested,
      // but for now assume all built in types get built with partial matching support.
      builder.put(
          type,
          RangeClassifier.builder()
              .putAll(type.extractRangesFrom(table))
              .setSingleValued(ClassifierType.isSingleValued(type))
              .build());
    }
    return builder.build(table.getAllRanges());
  }

  abstract CsvTable<DigitSequence> root();

  public abstract ImmutableList<ClassifierType> getTypes();

  abstract ImmutableMap<DigitSequence, RangeMap> rangeData();

  public final ImmutableSet<DigitSequence> getAvailableCallingCodes() {
    return rangeData().keySet();
  }

  public final RangeMap getRangeMap(DigitSequence cc) {
    RangeMap rangeTable = rangeData().get(cc);
    checkArgument(rangeTable != null, "no data for calling code: %s", cc);
    return rangeTable;
  }

  public final Metadata trimValidRanges() {
    if (!getTypes().contains(VALIDITY)) {
      return this;
    }
    Metadata.Builder trimmedMetadata = Metadata.builder(root());
    for (DigitSequence cc : getAvailableCallingCodes()) {
      trimmedMetadata.put(cc, getRangeMap(cc).trimValidRanges());
    }
    return trimmedMetadata.build();
  }

  @AutoValue
  abstract static class RangeClassifier {
    static final class Builder {
      private final ImmutableMap.Builder<String, RangeTree> map = ImmutableMap.builder();
      private boolean isSingleValued = false;
      private boolean isClassifierOnly = false;

      private Builder() {}

      @CanIgnoreReturnValue
      public Builder setSingleValued(boolean isSingleValued) {
        this.isSingleValued = isSingleValued;
        return this;
      }

      @CanIgnoreReturnValue
      public Builder setClassifierOnly(boolean isClassifierOnly) {
        this.isClassifierOnly = isClassifierOnly;
        return this;
      }

      @CanIgnoreReturnValue
      public Builder put(String key, RangeTree ranges) {
        if (!ranges.isEmpty()) {
          map.put(key, ranges);
        }
        return this;
      }

      @CanIgnoreReturnValue
      public Builder putAll(Map<String, RangeTree> rangeMap) {
        rangeMap.forEach(this::put);
        return this;
      }

      public RangeClassifier build() {
        return new AutoValue_Metadata_RangeClassifier(
            isSingleValued, isClassifierOnly, map.build());
      }
    }

    public static RangeClassifier.Builder builder() {
      return new RangeClassifier.Builder();
    }

    public abstract boolean isSingleValued();

    public abstract boolean isClassifierOnly();

    abstract ImmutableMap<String, RangeTree> map();

    public final ImmutableSet<String> orderedKeys() {
      return map().keySet();
    }

    public final ImmutableSet<Map.Entry<String, RangeTree>> orderedEntries() {
      return map().entrySet();
    }

    public final RangeTree getRanges(String key) {
      return map().getOrDefault(key, RangeTree.empty());
    }

    public final ImmutableSet<String> classify(DigitSequence seq) {
      Stream<String> matches =
          orderedEntries().stream().filter(e -> e.getValue().contains(seq)).map(Map.Entry::getKey);
      if (isSingleValued()) {
        return matches.findFirst().map(ImmutableSet::of).orElse(ImmutableSet.of());
      } else {
        return matches.collect(toImmutableSet());
      }
    }

    public final RangeClassifier intersect(RangeTree bound) {
      Builder trimmed =
          builder().setSingleValued(isSingleValued()).setClassifierOnly(isClassifierOnly());
      for (Map.Entry<String, RangeTree> e : orderedEntries()) {
        trimmed.put(e.getKey(), e.getValue().intersect(bound));
      }
      return trimmed.build();
    }
  }

  @AutoValue
  abstract static class RangeMap {

    static final class Builder {
      private final Map<ClassifierType, RangeClassifier> map = new LinkedHashMap<>();

      Builder() {}

      @CanIgnoreReturnValue
      Builder put(ClassifierType type, RangeClassifier classifier) {
        map.put(type, classifier);
        return this;
      }

      RangeMap build(RangeTree allRanges) {
        // Bound all classifiers by the outer range to ensure that classifiers don't classify
        // invalid sequences.
        ImmutableMap<ClassifierType, RangeClassifier> trimmedMap =
            map.entrySet().stream()
                .collect(toImmutableMap(Map.Entry::getKey, e -> e.getValue().intersect(allRanges)));
        return new AutoValue_Metadata_RangeMap(allRanges, trimmedMap);
      }
    }

    static RangeMap.Builder builder() {
      return new RangeMap.Builder();
    }

    abstract RangeTree getAllRanges();

    abstract ImmutableMap<ClassifierType, RangeClassifier> classifiers();

    public final ImmutableSet<ClassifierType> getTypes() {
      return classifiers().keySet();
    }

    public final RangeClassifier getClassifier(ClassifierType type) {
      return checkNotNull(classifiers().get(type), "no such type: %s", type.id());
    }

    public final RangeTree getRanges(ClassifierType type, String key) {
      RangeClassifier classifier = getClassifier(type);
      return classifier != null ? classifier.getRanges(key) : RangeTree.empty();
    }

    public final RangeMap trimValidRanges() {
      RangeClassifier validityClassifier = classifiers().get(VALIDITY);
      if (validityClassifier == null) {
        return this;
      }
      RangeTree validRanges = validityClassifier.getRanges("VALID");
      RangeMap.Builder trimmedMap = RangeMap.builder();
      for (Map.Entry<ClassifierType, RangeClassifier> e : classifiers().entrySet()) {
        if (e.getKey().equals(VALIDITY)) {
          // Don't add the validity classifier (there's no point).
          continue;
        }
        RangeClassifier classifier = e.getValue();
        RangeClassifier.Builder trimmedClassifier =
            RangeClassifier.builder()
                .setSingleValued(classifier.isSingleValued())
                .setClassifierOnly(classifier.isClassifierOnly());
        for (String key : classifier.orderedKeys()) {
          // The result is ignored if empty.
          trimmedClassifier.put(key, classifier.getRanges(key).intersect(validRanges));
        }
        trimmedMap.put(e.getKey(), trimmedClassifier.build());
      }
      return trimmedMap.build(validRanges);
    }
  }
}