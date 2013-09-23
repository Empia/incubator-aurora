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
package com.twitter.aurora.scheduler.http;

import com.google.common.base.Function;
import com.google.common.collect.Ordering;

import com.twitter.aurora.gen.JobConfiguration;
import com.twitter.aurora.scheduler.MesosTaskFactory.MesosTaskFactoryImpl;
import com.twitter.aurora.scheduler.http.SchedulerzHome.Role;
import com.twitter.aurora.scheduler.http.SchedulerzRole.Job;
import com.twitter.common.args.Arg;
import com.twitter.common.args.CmdLine;

/**
 * Utility class to hold common display helper functions.
 */
public final class DisplayUtils {

  @CmdLine(name = "viz_job_url_prefix", help = "URL prefix for job container stats.")
  private static final Arg<String> VIZ_JOB_URL_PREFIX = Arg.create("");

  private DisplayUtils() {
    // Utility class.
  }

  static final Ordering<Role> ROLE_ORDERING = Ordering.natural().onResultOf(
      new Function<Role, String>() {
        @Override public String apply(Role role) {
          return role.getRole();
        }
      });

  static final Ordering<Job> JOB_ORDERING = Ordering.natural().onResultOf(
      new Function<Job, String>() {
        @Override public String apply(Job job) {
          return job.getName();
        }
      });

  static final Ordering<JobConfiguration> JOB_CONFIG_ORDERING = Ordering.natural().onResultOf(
      new Function<JobConfiguration, String>() {
        @Override public String apply(JobConfiguration job) {
          return job.getName();
        }
      });

  static String getJobDashboardUrl(String role, String env, String jobName) {
    return VIZ_JOB_URL_PREFIX.get() + MesosTaskFactoryImpl.getJobSourceName(role, env, jobName);
  }
}
