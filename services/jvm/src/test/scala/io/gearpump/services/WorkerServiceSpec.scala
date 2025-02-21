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

import akka.actor.ActorRef
import akka.http.scaladsl.model.headers.`Cache-Control`
import akka.testkit.TestActor.{AutoPilot, KeepRunning}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.{Config, ConfigFactory}
import io.gearpump.WorkerId
import io.gearpump.cluster.AppMasterToMaster.{GetWorkerData, WorkerData}
import io.gearpump.cluster.ClientToMaster.{QueryHistoryMetrics, QueryWorkerConfig, ResolveWorkerId}
import io.gearpump.cluster.MasterToClient.{HistoryMetrics, HistoryMetricsItem, ResolveWorkerIdResult, WorkerConfig}
import io.gearpump.cluster.TestUtil
import io.gearpump.cluster.worker.WorkerSummary
import io.gearpump.jarstore.JarStoreService
import io.gearpump.services.util.UpickleUtil._
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.duration._
import scala.util.{Success, Try}
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}

class WorkerServiceSpec extends FlatSpec with ScalatestRouteTest  with Matchers with BeforeAndAfterAll {

  override def testConfig: Config = TestUtil.DEFAULT_CONFIG

  def actorRefFactory = system

  val mockWorker = TestProbe()

  def master = mockMaster.ref

  lazy val jarStoreService = JarStoreService.get(system.settings.config)

  def workerRoute = new WorkerService(master, system).route

  mockWorker.setAutoPilot {
    new AutoPilot {
      def run(sender: ActorRef, msg: Any) = msg match {
        case GetWorkerData(workerId) =>
          sender ! WorkerData(WorkerSummary.empty)
          KeepRunning
        case QueryWorkerConfig(workerId) =>
          sender ! WorkerConfig(null)
          KeepRunning
        case QueryHistoryMetrics(path, _, _, _) =>
          sender ! HistoryMetrics(path, List.empty[HistoryMetricsItem])
          KeepRunning
      }
    }
  }

  val mockMaster = TestProbe()
  mockMaster.setAutoPilot {
    new AutoPilot {
      def run(sender: ActorRef, msg: Any) = msg match {
        case ResolveWorkerId(workerId) =>
          sender ! ResolveWorkerIdResult(Success(mockWorker.ref))
          KeepRunning
      }
    }
  }


  "ConfigQueryService" should "return config for worker" in {
    implicit val customTimeout = RouteTestTimeout(15.seconds)
    (Get(s"/api/$REST_VERSION/worker/${WorkerId.render(WorkerId(0, 0L))}/config") ~> workerRoute) ~> check{
      val responseBody = responseAs[String]
      val config = Try(ConfigFactory.parseString(responseBody))
      assert(config.isSuccess)
    }
  }

  it should "return WorkerData" in {
    implicit val customTimeout = RouteTestTimeout(15.seconds)
    (Get(s"/api/$REST_VERSION/worker/${WorkerId.render(WorkerId(1, 0L))}") ~> workerRoute) ~> check{
      val responseBody = responseAs[String]
      val config = Try(ConfigFactory.parseString(responseBody))
      assert(config.isSuccess)

      // check the header, should contains no-cache header.
      // Cache-Control:no-cache, max-age=0
      val noCache = header[`Cache-Control`].get.value()
      assert(noCache == "no-cache, max-age=0")
    }
  }

  "MetricsQueryService" should "return history metrics" in {
    implicit val customTimeout = RouteTestTimeout(15.seconds)
    (Get(s"/api/$REST_VERSION/worker/${WorkerId.render(WorkerId(0, 0L))}/metrics/worker") ~> workerRoute) ~> check {
      val responseBody = responseAs[String]
      val config = Try(ConfigFactory.parseString(responseBody))
      assert(config.isSuccess)
    }
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
}
