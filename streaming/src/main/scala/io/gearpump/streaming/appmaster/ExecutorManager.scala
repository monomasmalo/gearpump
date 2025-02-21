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

package io.gearpump.streaming.appmaster

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.remote.RemoteScope
import com.typesafe.config.Config
import io.gearpump.WorkerId
import io.gearpump.cluster.AppMasterToWorker.ChangeExecutorResource
import io.gearpump.cluster.appmaster.ExecutorSystemScheduler.{ExecutorSystemJvmConfig, ExecutorSystemStarted, StartExecutorSystemTimeout, StartExecutorSystems}
import io.gearpump.cluster.appmaster.WorkerInfo
import io.gearpump.cluster.scheduler.{Resource, ResourceRequest}
import io.gearpump.cluster.{AppJar, AppMasterContext, ExecutorContext, UserConfig}
import io.gearpump.streaming.ExecutorId
import io.gearpump.streaming.ExecutorToAppMaster.RegisterExecutor
import io.gearpump.streaming.appmaster.ExecutorManager._
import io.gearpump.streaming.executor.Executor
import io.gearpump.util.{LogUtil, Util}
import org.apache.commons.lang.exception.ExceptionUtils

import scala.concurrent.duration._
import scala.util.Try

/**
 * ExecutorManager manage all executors.
 *
 * ExecutorManager will launch Executor when asked. It hide the details like starting
 * a new ExecutorSystem from user.
 *
 * Please use ExecutorManager.props() to construct this actor
 *
 */
private[appmaster] class ExecutorManager(
    userConfig: UserConfig,
    appContext: AppMasterContext,
    executorFactory: (ExecutorContext, UserConfig, Address, ExecutorId) => Props,
    clusterConfig: Config,
    appName: String)
  extends Actor {

  private val LOG = LogUtil.getLogger(getClass)

  import appContext.{appId, masterProxy, username}

  private var taskManager: ActorRef = null
  implicit val actorSystem = context.system
  private val systemConfig = context.system.settings.config

  private var executors =  Map.empty[Int, ExecutorInfo]

  def receive: Receive = waitForTaskManager

  def waitForTaskManager: Receive = {
    case SetTaskManager(taskManager) =>
      this.taskManager = taskManager
      context.become(service orElse terminationWatch)
  }

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
    case ex: Throwable =>
      val executorId = Try(sender.path.name.toInt)
      executorId match {
        case scala.util.Success(id) => {
          executors -= id
          LOG.error(s"Executor $id throws exception, stop it...\n" + ExceptionUtils.getStackTrace(ex))
        }
      }
      Stop
  }

  def service: Receive = {
    case StartExecutors(resources, jar) =>
      masterProxy ! StartExecutorSystems(resources, getExecutorJvmConfig(Some(jar)))
    case ExecutorSystemStarted(executorSystem, boundedJar) =>
      import executorSystem.{address, executorSystemId, resource => executorResource, worker}

      val executorId = executorSystemId
      val executorContext = ExecutorContext(executorId, worker, appId, appName, appMaster = context.parent, executorResource)
      executors += executorId -> ExecutorInfo(executorId, null, worker, boundedJar)

      //start executor
      val executor = context.actorOf(executorFactory(executorContext, userConfig, address, executorId),
        executorId.toString)
      executorSystem.bindLifeCycleWith(executor)
    case StartExecutorSystemTimeout =>
      taskManager ! StartExecutorsTimeOut

    case RegisterExecutor(executor, executorId, resource, worker) =>
      LOG.info(s"executor $executorId has been launched")
      //watch for executor termination
      context.watch(executor)
      val executorInfo = executors.get(executorId).get
      executors += executorId -> executorInfo.copy(executor = executor)
      taskManager ! ExecutorStarted(executorId, resource, worker.workerId, executorInfo.boundedJar)

    // broadcast message to all executors
    case BroadCast(msg) =>
      LOG.info(s"Broadcast ${msg.getClass.getSimpleName} to all executors")
      context.children.foreach(_ forward  msg)

    // unicast message to single executor
    case UniCast(executorId, msg) =>
      LOG.info(s"Unicast ${msg.getClass.getSimpleName} to executor $executorId")
      val executor = executors.get(executorId)
      executor.foreach(_.executor forward msg)

    case GetExecutorInfo =>
      sender ! executors

    // update resource usage, so that reclaim unused resource.
    case ExecutorResourceUsageSummary(resources) =>
      executors.foreach { pair =>
        val (executorId, executor) = pair
        val resource = resources.get(executorId)
        val worker = executor.worker.ref
        // notify the worker the actual resource used by this application.
        resource match {
          case Some(resource) =>
            worker ! ChangeExecutorResource(appId, executorId, resource)
          case None =>
            worker ! ChangeExecutorResource(appId, executorId, Resource(0))
        }
      }
    }

  def terminationWatch : Receive = {
    case Terminated(actor) =>
      val executorId = Try(actor.path.name.toInt)
      executorId match {
        case scala.util.Success(id) => {
          executors -= id
          LOG.error(s"Executor $id is down")
          taskManager ! ExecutorStopped(id)
        }
        case scala.util.Failure(ex) => LOG.error(s"failed to get the executor Id from path string ${actor.path}" , ex)
      }
  }

  private def getExecutorJvmConfig(jar: Option[AppJar]): ExecutorSystemJvmConfig = {
    val executorAkkaConfig = clusterConfig
    val jvmSetting = Util.resolveJvmSetting(executorAkkaConfig.withFallback(systemConfig)).executor
    ExecutorSystemJvmConfig(jvmSetting.classPath, jvmSetting.vmargs, jar, username, executorAkkaConfig)
  }
}

private[appmaster] object ExecutorManager {
  case class StartExecutors(resources: Array[ResourceRequest], jar: AppJar)
  case class BroadCast(msg: Any)

  case class UniCast(executorId: Int, msg: Any)

  case object GetExecutorInfo

  case class ExecutorStarted(executorId: Int, resource: Resource, workerId: WorkerId, boundedJar: Option[AppJar])
  case class ExecutorStopped(executorId: Int)

  case class SetTaskManager(taskManager: ActorRef)

  case object StartExecutorsTimeOut

  def props(userConfig: UserConfig, appContext: AppMasterContext, clusterConfig: Config, appName: String): Props = {
    val executorFactory =
        (executorContext: ExecutorContext,
         userConfig: UserConfig,
         address: Address,
         executorId: ExecutorId) =>
      Props(classOf[Executor], executorContext, userConfig)
        .withDeploy(Deploy(scope = RemoteScope(address)))

    Props(new ExecutorManager(userConfig, appContext, executorFactory, clusterConfig, appName))
  }

  case class ExecutorResourceUsageSummary(resources: Map[ExecutorId, Resource])

  case class ExecutorInfo(executorId: ExecutorId, executor: ActorRef, worker: WorkerInfo, boundedJar: Option[AppJar])
}
