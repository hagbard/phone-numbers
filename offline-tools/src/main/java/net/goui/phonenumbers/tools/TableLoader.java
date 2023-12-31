/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.tools;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.FileVisitOption.FOLLOW_LINKS;

import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.i18n.phonenumbers.metadata.table.CsvParser;
import com.google.i18n.phonenumbers.metadata.table.CsvSchema;
import com.google.i18n.phonenumbers.metadata.table.CsvTable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Encapsulates loading a metadata CSV file, either from the main zip file or as an "overlay". */
final class TableLoader implements AutoCloseable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final Pattern IS_OVERLAP_PATH =
      Pattern.compile("(metadata|[0-9]{1,3}/[^/]+)\\.csv");

  @Nullable private final ZipFile zip;
  private final ImmutableMap<String, Path> overlayMap;
  private final CsvParser csvParser;

  /**
   * Creates a loader for CSV tables from the following sources in order of preference:
   * <ol>
   *   <li>Overlay files in a directory indicated by {@code overlayDirPath}.
   *   <li>Overlay resources packaged into the library.
   *   <li>Metadata zip file entries indicated by {@code zipPath}.
   * </ol>
   *
   * @param zipPath path to the primary zip file (this is optional only if all files are found .
   * @param overlayDirPath path to the root of an overlay directory.
   * @param overlaySeparator CSV separator for loading overlay files.
   */
  TableLoader(String zipPath, String overlayDirPath, char overlaySeparator) throws IOException {
    this.zip = !zipPath.isEmpty() ? new ZipFile(zipPath) : null;
    this.overlayMap = readOverlayFiles(overlayDirPath);
    this.csvParser = CsvParser.withSeparator(overlaySeparator).trimWhitespace();
  }

  /**
   * Returns a CSV table for the specified schema from the root relative metadata path (e.g. {@code
   * "44/ranges.csv"}).
   */
  <T> CsvTable<T> load(String rootRelativePath, CsvSchema<T> schema) throws IOException {
    // Load from any explicitly specified overlay directory first.
    Path overlayPath = overlayMap.get(rootRelativePath);
    if (overlayPath != null) {
      return loadFromFile(overlayPath, schema, csvParser);
    }
    // If a resource exists for a CSV file, load that in preference to the zip.
    // Unlike overlay files, resources MUST be semicolon separated (like the zip file).
    // Resources are intended for shipping with the library, not adding by users.
    Optional<CsvTable<T>> overrideTable = loadFromResource(rootRelativePath, schema);
    if (overrideTable.isPresent()) {
      return overrideTable.get();
    }
    checkState(
        zip != null,
        "cannot find metadata path in overlay directory, and no zip file was specified: %s",
        rootRelativePath);
    return loadFromZipEntry(zip, rootRelativePath, schema);
  }

  public static <T> Optional<CsvTable<T>> loadFromResource(
      String rootRelativePath, CsvSchema<T> schema) throws IOException {
    try (InputStream is = TableLoader.class.getResourceAsStream("/" + rootRelativePath)) {
      if (is != null) {
        logger.atInfo().log("Resource overlay: /%s", rootRelativePath);
        try (Reader reader = new InputStreamReader(is, UTF_8)) {
          return Optional.of(CsvTable.importCsv(schema, reader));
        } catch (IOException e) {
          throw new IOException("error loading resource: " + rootRelativePath, e);
        }
      }
    }
    return Optional.empty();
  }

  public <T> CsvTable<T> loadFromZipFile(String rootRelativePath, CsvSchema<T> schema)
      throws IOException {
    return loadFromZipEntry(
        checkNotNull(zip, "No metadata zip file was provided"), rootRelativePath, schema);
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

  private static <T> CsvTable<T> loadFromFile(Path path, CsvSchema<T> schema, CsvParser csvParser)
      throws IOException {
    logger.atInfo().log("File overlay: %s", path);
    try (Reader reader = Files.newBufferedReader(path, UTF_8)) {
      return CsvTable.importCsv(schema, reader, csvParser);
    } catch (IOException e) {
      throw new IOException("error loading file: " + path, e);
    }
  }

  private static <T> CsvTable<T> loadFromZipEntry(ZipFile zip, String path, CsvSchema<T> schema)
      throws IOException {
    ZipEntry zipEntry = zip.getEntry(path);
    if (zipEntry == null) {
      return CsvTable.builder(schema).build();
    }
    try (Reader reader = new InputStreamReader(zip.getInputStream(zipEntry), UTF_8)) {
      return CsvTable.importCsv(schema, reader);
    } catch (IOException e) {
      throw new IOException("error loading zip entry: " + path, e);
    }
  }

  @Override
  public void close() throws IOException {
    if (zip != null) {
      zip.close();
    }
  }
}
