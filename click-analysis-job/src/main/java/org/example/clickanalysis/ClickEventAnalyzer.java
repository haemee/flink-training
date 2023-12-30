package org.example.clickanalysis;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.example.clickanalysis.functions.BackpressureMap;
import org.example.clickanalysis.functions.ClickEventStatisticsCollector;
import org.example.clickanalysis.functions.CountingAggregator;
import org.example.clickanalysis.records.ClickEvent;
import org.example.clickanalysis.records.ClickEventDeserializationSchema;
import org.example.clickanalysis.records.ClickEventStatistics;
import org.example.clickanalysis.records.ClickEventStatisticsSerializationSchema;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.WindowAssigner;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class ClickEventAnalyzer {
  public static final String CHECKPOINTING_OPTION = "checkpointing";
  public static final String EVENT_TIME_OPTION = "event-time";
  public static final String BACKPRESSURE_OPTION = "backpressure";
  public static final String OPERATOR_CHAINING_OPTION = "chaining";

  public static final Time WINDOW_SIZE = Time.of(15, TimeUnit.SECONDS);

  public static void main(String[] args) throws Exception {
    final ParameterTool params = ParameterTool.fromArgs(args);

    final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

    configureEnvironment(params, env);

    boolean inflictBackpressure = params.has(BACKPRESSURE_OPTION);

    String inputTopic = params.get("input-topic", "input");
    String outputTopic = params.get("output-topic", "output");
    String brokers = params.get("bootstrap.servers", "localhost:9094");
    Properties kafkaProps = new Properties();
    kafkaProps.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
    kafkaProps.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "click-event-count");

    KafkaSource<ClickEvent> source = KafkaSource.<ClickEvent>builder()
      .setTopics(inputTopic)
      .setValueOnlyDeserializer(new ClickEventDeserializationSchema())
      .setProperties(kafkaProps)
      .build();

    WatermarkStrategy<ClickEvent> watermarkStrategy = WatermarkStrategy
      .<ClickEvent>forBoundedOutOfOrderness(Duration.ofMillis(200))
      .withIdleness(Duration.ofSeconds(5))
      .withTimestampAssigner((clickEvent, l) -> clickEvent.getTimestamp().getTime());

    DataStream<ClickEvent> clicks = env.fromSource(source, watermarkStrategy, "ClickEvent Source");

    if (inflictBackpressure) {
      // Force a network shuffle so that the backpressure will affect the buffer pools
      clicks = clicks
        .keyBy(ClickEvent::getPage)
        .map(new BackpressureMap())
        .name("Backpressure");
    }

    WindowAssigner<Object, TimeWindow> assigner = params.has(EVENT_TIME_OPTION) ?
      TumblingEventTimeWindows.of(WINDOW_SIZE) :
      TumblingProcessingTimeWindows.of(WINDOW_SIZE);

    DataStream<ClickEventStatistics> statistics = clicks
      .keyBy(ClickEvent::getPage)
      .window(assigner)
      .aggregate(new CountingAggregator(),
        new ClickEventStatisticsCollector())
      .name("ClickEvent Counter");

    statistics.sinkTo(
        KafkaSink.<ClickEventStatistics>builder()
          .setBootstrapServers(kafkaProps.getProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG))
          .setKafkaProducerConfig(kafkaProps)
          .setRecordSerializer(
            KafkaRecordSerializationSchema.builder()
              .setTopic(outputTopic)
              .setValueSerializationSchema(new ClickEventStatisticsSerializationSchema())
              .build())
          .setDeliverGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
          .build())
      .name("ClickEventStatistics Sink");

    env.execute("Click Event Count");
  }

  private static void configureEnvironment(
    final ParameterTool params,
    final StreamExecutionEnvironment env) {

    boolean checkpointingEnabled = params.has(CHECKPOINTING_OPTION);
    boolean enableChaining = params.has(OPERATOR_CHAINING_OPTION);

    if (checkpointingEnabled) {
      env.enableCheckpointing(1000);
    }

    if(!enableChaining){
      //disabling Operator chaining to make it easier to follow the Job in the WebUI
      env.disableOperatorChaining();
    }
  }
}
