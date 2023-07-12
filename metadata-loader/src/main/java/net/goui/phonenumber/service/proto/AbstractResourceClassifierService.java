/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.service.proto;

import static com.google.common.base.Preconditions.checkState;

import java.io.IOException;
import java.io.InputStream;
import net.goui.phonenumber.metadata.ClassifierService;
import net.goui.phonenumber.metadata.RawClassifier;
import net.goui.phonenumber.metadata.VersionInfo;
import net.goui.phonenumber.proto.Metadata.MetadataProto;

public abstract class AbstractResourceClassifierService extends ClassifierService {
  private final String resourceName;

  protected AbstractResourceClassifierService(VersionInfo version, String resourceName) {
    super(version);
    this.resourceName = resourceName;
  }

  protected final RawClassifier load() throws IOException {
    MetadataProto proto;
    try (InputStream is = getClass().getResourceAsStream(resourceName)) {
      proto = MetadataProto.parseFrom(is);
    }
    ProtoBasedNumberClassifier classifier = new ProtoBasedNumberClassifier(proto);
    checkState(
        classifier.getVersion().satisfies(getStatedVersion()),
        "loaded metadata version (%s) does not satisfy the state version (%s)",
        classifier.getVersion(),
        getStatedVersion());
    return classifier;
  }
}