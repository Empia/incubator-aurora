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

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.google.common.base.Preconditions;
import com.google.inject.Inject;

import com.twitter.aurora.scheduler.state.MaintenanceController;

/**
 * Servlet that exposes state of {@link MaintenanceController}.
 */
@Path("/maintenance")
public class Maintenance {
  private final MaintenanceController maintenance;

  @Inject
  Maintenance(MaintenanceController maintenance) {
    this.maintenance = Preconditions.checkNotNull(maintenance);
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getOffers() {
    return Response.ok(maintenance.getDrainingTasks().asMap()).build();
  }
}
