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
package io.gearpump.cluster.worker

import akka.actor.ActorRef
import io.gearpump.util.HistoryMetricsService.HistoryMetricsConfig

case class WorkerSummary(
    workerId: Int,
    state: String,
    actorPath: String,
    aliveFor: Long,
    logFile: String,
    executors: Array[ExecutorSlots],
    totalSlots: Int,
    availableSlots: Int,
    homeDirectory: String,
    jvmName: String,
    historyMetricsConfig: HistoryMetricsConfig = null)

object WorkerSummary{
  def empty = WorkerSummary(-1, "", "", 0L, "", Array.empty[ExecutorSlots], 0, 0, "", jvmName = "")
}

case class ExecutorSlots(appId: Int, executorId: Int, slots: Int)