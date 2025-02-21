package io.gearpump.integrationtest.checklist

import io.gearpump.integrationtest.Docker._
import io.gearpump.integrationtest.hadoop.HadoopCluster._
import io.gearpump.integrationtest.{Util, TestSpecBase}
import io.gearpump.integrationtest.kafka.{ResultVerifier, SimpleKafkaReader, MessageLossDetector, NumericalDataProducer}
import io.gearpump.integrationtest.kafka.KafkaCluster._
import org.apache.log4j.Logger

class MessageDeliverySpec extends TestSpecBase {

  private val LOG = Logger.getLogger(getClass)

  override def beforeAll(): Unit = {
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }

  "Gearpump" should {
    "support exactly-once message delivery" in {
      withKafkaCluster(cluster) { kafkaCluster =>
        // setup
        val sourcePartitionNum = 1
        val sourceTopic = "topic1"
        val sinkTopic = "topic2"

        // Generate number sequence (1, 2, 3, ...) to the topic
        kafkaCluster.createTopic(sourceTopic, sourcePartitionNum)

        withDataProducer(sourceTopic, kafkaCluster.getBrokerListConnectString) { producer =>

          withHadoopCluster { hadoopCluster =>
            // exercise
            val args = Array("io.gearpump.streaming.examples.state.MessageCountApp",
              "-defaultFS", hadoopCluster.getDefaultFS,
              "-zookeeperConnect", kafkaCluster.getZookeeperConnectString,
              "-brokerList", kafkaCluster.getBrokerListConnectString,
              "-sourceTopic", sourceTopic,
              "-sinkTopic", sinkTopic,
              "-sourceTask", sourcePartitionNum).mkString(" ")
            val appId = restClient.getNextAvailableAppId()

            val stateJar = cluster.queryBuiltInExampleJars("state-").head
            val success = restClient.submitApp(stateJar, executorNum = 1, args = args)
            success shouldBe true

            // verify #1
            expectAppIsRunning(appId, "MessageCount")
            Util.retryUntil(()=>restClient.queryStreamingAppDetail(appId).clock > 0, "app is running")

            // wait for checkpoint to take place
            Thread.sleep(1000)

            LOG.info("Trigger message replay by kill and restart the executors")
            val executorToKill = restClient.queryExecutorBrief(appId).map(_.executorId).max
            restClient.killExecutor(appId, executorToKill) shouldBe true
            Util.retryUntil(()=> restClient.queryExecutorBrief(appId).map(_.executorId).max > executorToKill,
              s"executor $executorToKill killed")

            producer.stop()
            val producedNumbers = producer.producedNumbers
            LOG.info(s"In total, numbers in range[${producedNumbers.start}, ${producedNumbers.end - 1}] have been written to Kafka")

            // verify #3
            val kafkaSourceOffset = kafkaCluster.getLatestOffset(sourceTopic)

            assert(producedNumbers.size == kafkaSourceOffset, "produced message should match Kafka queue size")

            LOG.info(s"The Kafka source topic $sourceTopic offset is " + kafkaSourceOffset)

            // The sink processor of this job (MessageCountApp) writes total message
            // count to Kafka Sink periodically (once every checkpoint interval).
            // The detector keep record of latest message count.
            val detector = new ResultVerifier {
              var latestMessageCount: Int = 0
              override def onNext(messageCount: Int): Unit = {
                this.latestMessageCount = messageCount
              }
            }

            val kafkaReader = new SimpleKafkaReader(detector, sinkTopic,
              host = kafkaCluster.advertisedHost, port = kafkaCluster.advertisedPort)
            Util.retryUntil(()=>{
              kafkaReader.read()
              LOG.info(s"Received message count: ${detector.latestMessageCount}, expect: ${producedNumbers.size}")
              detector.latestMessageCount == producedNumbers.size
            }, "MessageCountApp calculated message count matches expected in case of message replay")
          }
        }
      }
    }
  }
}
