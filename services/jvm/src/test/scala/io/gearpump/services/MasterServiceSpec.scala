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

import java.io.File

import akka.actor.ActorRef
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{`Cache-Control`, `Set-Cookie`}
import akka.stream.scaladsl.Source
import akka.testkit.TestActor.{AutoPilot, KeepRunning}
import akka.testkit.TestProbe
import com.typesafe.config.{Config, ConfigFactory}
import io.gearpump.WorkerId
import io.gearpump.cluster.AppMasterToMaster.{GetAllWorkers, GetMasterData, GetWorkerData, MasterData, WorkerData}
import io.gearpump.cluster.ClientToMaster.{QueryHistoryMetrics, QueryMasterConfig, ResolveWorkerId, SubmitApplication}
import io.gearpump.cluster.MasterToAppMaster.{AppMasterData, AppMastersData, AppMastersDataRequest, WorkerList}
import io.gearpump.cluster.MasterToClient._
import io.gearpump.cluster.TestUtil
import io.gearpump.cluster.worker.WorkerSummary
import io.gearpump.services.MasterService.{SubmitApplicationRequest, BuiltinPartitioners}
import io.gearpump.jarstore.JarStoreService
import io.gearpump.streaming.ProcessorDescription
import io.gearpump.util.{Constants, Graph}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Success, Try}
import akka.stream.scaladsl.FileIO
import io.gearpump.services.util.UpickleUtil._

class MasterServiceSpec extends FlatSpec with ScalatestRouteTest with
  Matchers with BeforeAndAfterAll {
  import upickle.default.{read, write}

  override def testConfig: Config = TestUtil.UI_CONFIG

  def actorRefFactory = system
  val workerId = 0
  val mockWorker = TestProbe()

  lazy val jarStoreService = JarStoreService.get(system.settings.config)

  def master = mockMaster.ref

  def jarStore: JarStoreService = jarStoreService

  def masterRoute = new MasterService(master, jarStore, system).route

  mockWorker.setAutoPilot {
    new AutoPilot {
      def run(sender: ActorRef, msg: Any) = msg match {
        case GetWorkerData(workerId) =>
          sender ! WorkerData(WorkerSummary.empty.copy(state = "active", workerId = workerId))
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
        case GetMasterData =>
          sender ! MasterData(null)
          KeepRunning
        case  AppMastersDataRequest =>
          sender ! AppMastersData(List.empty[AppMasterData])
          KeepRunning
        case GetAllWorkers =>
          sender ! WorkerList(List(WorkerId(0, 0L)))
          KeepRunning
        case ResolveWorkerId(WorkerId(0, 0L)) =>
          sender ! ResolveWorkerIdResult(Success(mockWorker.ref))
          KeepRunning
        case QueryHistoryMetrics(path, _, _, _) =>
          sender ! HistoryMetrics(path, List.empty[HistoryMetricsItem])
          KeepRunning
        case QueryMasterConfig =>
          sender ! MasterConfig(null)
          KeepRunning
        case submit: SubmitApplication =>
          sender ! SubmitApplicationResult(Success(0))
          KeepRunning
      }
    }
  }


  it should "return master info when asked" in {
    implicit val customTimeout = RouteTestTimeout(15.seconds)
    (Get(s"/api/$REST_VERSION/master") ~> masterRoute) ~> check {
      // check the type
      val content = responseAs[String]
      read[MasterData](content)

      // check the header, should contains no-cache header.
      // Cache-Control:no-cache, max-age=0
      val noCache = header[`Cache-Control`].get.value()
      assert(noCache == "no-cache, max-age=0")
    }

    mockMaster.expectMsg(GetMasterData)
  }

  it should "return a json structure of appMastersData for GET request" in {
    implicit val customTimeout = RouteTestTimeout(15.seconds)
    (Get(s"/api/$REST_VERSION/master/applist") ~> masterRoute) ~> check {
      //check the type
      read[AppMastersData](responseAs[String])
    }
    mockMaster.expectMsg(AppMastersDataRequest)
  }

  it should "return a json structure of worker data for GET request" in {
    implicit val customTimeout = RouteTestTimeout(25.seconds)
    Get(s"/api/$REST_VERSION/master/workerlist") ~> masterRoute ~> check {
      //check the type
      val workerListJson = responseAs[String]
      val workers = read[List[WorkerSummary]](workerListJson)
      assert(workers.size > 0)
      workers.foreach { worker =>
        worker.state shouldBe "active"
      }
    }
    mockMaster.expectMsg(GetAllWorkers)
    mockMaster.expectMsgType[ResolveWorkerId]
    mockWorker.expectMsgType[GetWorkerData]
  }

  it should "return config for master" in {
    implicit val customTimeout = RouteTestTimeout(15.seconds)
    (Get(s"/api/$REST_VERSION/master/config") ~> masterRoute) ~> check{
      val responseBody = responseAs[String]
      val config = Try(ConfigFactory.parseString(responseBody))
      assert(config.isSuccess)
    }
    mockMaster.expectMsg(QueryMasterConfig)
  }

  "submit invalid application" should "return an error" in {
    implicit val routeTestTimeout = RouteTestTimeout(30.second)
    val tempfile = new File("foo")
    val request = entity(tempfile)

    Post(s"/api/$REST_VERSION/master/submitapp", request) ~> masterRoute ~> check {
      assert(response.status.intValue == 500)
    }
  }

  private def entity(file: File)(implicit ec: ExecutionContext): Future[RequestEntity] = {
    val entity =  HttpEntity(MediaTypes.`application/octet-stream`, file.length(),
      FileIO.fromFile(file, chunkSize = 100000))

    val body = Source.single(
      Multipart.FormData.BodyPart(
        "file",
        entity,
        Map("filename" -> file.getName)))
    val form = Multipart.FormData(body)

    Marshal(form).to[RequestEntity]
  }

  "MetricsQueryService" should "return history metrics" in {
    implicit val customTimeout = RouteTestTimeout(15.seconds)
    (Get(s"/api/$REST_VERSION/master/metrics/master") ~> masterRoute)~> check {
      val responseBody = responseAs[String]
      val config = Try(ConfigFactory.parseString(responseBody))
      assert(config.isSuccess)
    }
  }

  "submitDag" should "submit a SubmitApplicationRequest and get an appId > 0" in {
    import io.gearpump.util.Graph._
    val processors = Map(
      0 -> ProcessorDescription(0, "A", parallelism = 1),
      1 -> ProcessorDescription(1, "B", parallelism = 1)
    )
    val dag = Graph(0 ~ "partitioner" ~> 1)
    val jsonValue = write(SubmitApplicationRequest("complexdag", processors, dag, null))
    Post(s"/api/$REST_VERSION/master/submitdag", HttpEntity(ContentTypes.`application/json`, jsonValue)) ~> masterRoute ~> check {
      val responseBody = responseAs[String]
      val submitApplicationResultValue = read[SubmitApplicationResultValue](responseBody)
      assert(submitApplicationResultValue.appId >= 0, "invalid appid")
    }
  }

  "MasterService" should "return Gearpump built-in partitioner list" in {
    (Get(s"/api/$REST_VERSION/master/partitioners") ~> masterRoute) ~> check {
      val responseBody = responseAs[String]
      val partitioners = read[BuiltinPartitioners](responseBody)
      assert(partitioners.partitioners.length > 0, "invalid response")
    }
  }
}
