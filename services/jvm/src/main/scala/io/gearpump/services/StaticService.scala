/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gearpump.services

import akka.actor.{ActorSystem}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.{Materializer}
import io.gearpump.util.{Constants, Util}
import io.gearpump.services.util.UpickleUtil._

/**
 * static resource files.
 */
class StaticService(override val system: ActorSystem, supervisorPath: String)
  extends BasicService {

  private val version = Util.version

  override def prefix = Neutral

  override def cache = true

  override def doRoute(implicit mat: Materializer) = {
    path("version") {
      get {ctx =>
        ctx.complete(version)
      }
    } ~
    // For YARN usage, we need to make sure supervisor-path
    // can be accessed without authentication.
    path("supervisor-actor-path") {
      get {
        complete(supervisorPath)
      }
    } ~
    pathEndOrSingleSlash {
      getFromResource("index.html")
    } ~
    path("favicon.ico") {
      complete(StatusCodes.NotFound)
    } ~
    pathPrefix("webjars") {
      get {
        getFromResourceDirectory("META-INF/resources/webjars")
      }
    } ~
    path(Rest) { path =>
      getFromResource("%s" format path)
    }
  }
}
