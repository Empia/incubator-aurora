/*
 * Copyright 2013 Twitter, Inc.
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
package com.twitter.aurora.scheduler.configuration;

import java.util.Set;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

import com.twitter.aurora.gen.JobConfiguration;
import com.twitter.aurora.gen.TaskConfig;
import com.twitter.aurora.scheduler.configuration.ConfigurationManager.TaskDescriptionException;

/**
 * Wrapper for a configuration that has been fully-parsed and populated with defaults.
 * TODO(wfarner): Rename this to SanitizedConfiguration.
 */
public final class ParsedConfiguration {

  private final JobConfiguration parsed;
  private final Set<TaskConfig> tasks;

  /**
   * Constructs a ParsedConfiguration object and populates the set of {@link TaskConfig}s for
   * the provided config.
   *
   * @param parsed A parsed {@link JobConfiguration}.
   */
  @VisibleForTesting
  public ParsedConfiguration(JobConfiguration parsed) {
    this.parsed = parsed;
    ImmutableSet.Builder<TaskConfig> builder = ImmutableSet.builder();
    for (int i = 0; i < parsed.getShardCount(); i++) {
      builder.add(parsed.getTaskConfig().deepCopy().setShardId(i));
    }
    this.tasks = builder.build();
  }

  /**
   * Wraps an unparsed job configuration.
   *
   * @param unparsed Unparsed configuration to parse/populate and wrap.
   * @return A wrapper containing the parsed configuration.
   * @throws TaskDescriptionException If the configuration is invalid.
   */
  public static ParsedConfiguration fromUnparsed(JobConfiguration unparsed)
      throws TaskDescriptionException {

    Preconditions.checkNotNull(unparsed);
    return new ParsedConfiguration(ConfigurationManager.validateAndPopulate(unparsed));
  }

  public JobConfiguration getJobConfig() {
    return parsed;
  }

  public Set<TaskConfig> getTaskConfigs() {
    return tasks;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ParsedConfiguration)) {
      return false;
    }

    ParsedConfiguration other = (ParsedConfiguration) o;

    return Objects.equal(parsed, other.parsed);
  }

  @Override
  public int hashCode() {
    return parsed.hashCode();
  }

  @Override
  public String toString() {
    return parsed.toString();
  }
}
