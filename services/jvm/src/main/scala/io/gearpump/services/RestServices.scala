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

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Route, _}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import io.gearpump.jarstore.JarStoreService
import io.gearpump.util.{Constants, LogUtil}
import org.apache.commons.lang.exception.ExceptionUtils

import scala.concurrent.Await
import scala.concurrent.duration._

class RestServices(master: ActorRef, mat: ActorMaterializer, system: ActorSystem) extends RouteService {

  implicit val timeout = Constants.FUTURE_TIMEOUT

  private val config = system.settings.config

  private val jarStoreService = JarStoreService.get(config)
  jarStoreService.init(config, system)

  private val LOG = LogUtil.getLogger(getClass)

  private val securityEnabled = config.getBoolean(Constants.GEARPUMP_UI_SECURITY_AUTHENTICATION_ENABLED)

  private val supervisorPath = system.settings.config.getString(Constants.GEARPUMP_SERVICE_SUPERVISOR_PATH)

  private val myExceptionHandler: ExceptionHandler = ExceptionHandler {
    case ex: Throwable => {
      extractUri { uri =>
        LOG.error(s"Request to $uri could not be handled normally", ex)
        complete(InternalServerError, ExceptionUtils.getStackTrace(ex))
      }
    }
  }

  // make sure staticRoute is the final one, as it will try to lookup resource in local path
  // if there is no match in previous routes
  private val static = new StaticService(system, supervisorPath).route

  def supervisor: ActorRef = {
    if (supervisorPath == null || supervisorPath.isEmpty()) {
      null
    } else {
      val actorRef = system.actorSelection(supervisorPath).resolveOne()
      Await.result(actorRef, new Timeout(Duration.create(5, "seconds")).duration)
    }
  }

  override def route: Route = {
    if (securityEnabled) {
      val security = new SecurityService(services, system)
      handleExceptions(myExceptionHandler) {
        security.route ~ static
      }
    } else {
      handleExceptions(myExceptionHandler) {
        services.route ~ static
      }
    }
  }

  private def services: RouteService = {

    val admin = new AdminService(system)
    val masterService = new MasterService(master, jarStoreService, system)
    val worker = new WorkerService(master, system)
    val app = new AppMasterService(master, jarStoreService, system)
    val sup = new SupervisorService(master, supervisor, system)

    new RouteService {
      override def route: Route = {
        admin.route ~ sup.route ~ masterService.route ~ worker.route ~ app.route
      }
    }
  }
}