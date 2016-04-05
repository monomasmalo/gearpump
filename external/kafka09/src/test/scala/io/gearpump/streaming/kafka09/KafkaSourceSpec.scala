package io.gearpump.streaming.kafka09

import io.gearpump.streaming.kafka09.lib.KafkaSourceConfig
import io.gearpump.streaming.transaction.api.OffsetStorageFactory
import org.scalatest.{MustMatchers, WordSpec}
import org.scalatest.mock.MockitoSugar

class KafkaSourceSpec extends WordSpec with MustMatchers with MockitoSugar {

  "KafkaSource" must {

    "construct valid instance" in {
      val config = mock[KafkaSourceConfig]
      val offsetStorageFactory = mock[OffsetStorageFactory]
      val kafkaSource = new KafkaSource(config, offsetStorageFactory)
      kafkaSource must not be null
    }



  }

}
