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
package com.twitter.aurora.scheduler.quota;

import javax.inject.Singleton;

import com.google.inject.AbstractModule;

import com.twitter.aurora.scheduler.quota.QuotaManager.QuotaManagerImpl;
import com.twitter.aurora.scheduler.state.JobFilter;
import com.twitter.aurora.scheduler.storage.Storage;

/**
 * Guice module for the quota package.
 */
public class QuotaModule extends AbstractModule {

  @Override
  protected void configure() {
    requireBinding(Storage.class);

    bind(QuotaManager.class).to(QuotaManagerImpl.class);
    bind(QuotaManagerImpl.class).in(Singleton.class);

    bind(JobFilter.class).to(QuotaFilter.class);
    bind(QuotaFilter.class).in(Singleton.class);
  }
}
