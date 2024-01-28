package org.example.clickanalysis;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
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

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class ClickEventAnalyzer {
  public static final String CHECKPOINTING_OPTION = "checkpointing";
  public static final String EVENT_TIME_OPTION = "event-time";
  public static final String OPERATOR_CHAINING_OPTION = "chaining";
  public static final Time WINDOW_SIZE = Time.of(15, TimeUnit.SECONDS);

  public static void main(String[] args) throws Exception {
    final ParameterTool params = ParameterTool.fromArgs(args);

    final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

    configureEnvironment(params, env);

    String inputTopic = params.get("input-topic", "input");
    String outputTopic = params.get("output-topic", "output");
    String brokers = params.get("bootstrap.servers", "localhost:9094");
    Properties kafkaProps = new Properties();
    kafkaProps.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
    kafkaProps.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "click-event-count");

    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

    KafkaSource<ClickEvent> source = KafkaSource.<ClickEvent>builder()
      .setProperties(kafkaProps)
      .setTopics(inputTopic)
      .setValueOnlyDeserializer(new ClickEventDeserializationSchema())
      .setStartingOffsets(OffsetsInitializer.earliest())
      .build();

    WatermarkStrategy<ClickEvent> watermarkStrategy = WatermarkStrategy
      .<ClickEvent>forBoundedOutOfOrderness(Duration.ofMillis(200))
      .withIdleness(Duration.ofSeconds(5))
      .withTimestampAssigner((clickEvent, l) -> clickEvent.getTimestamp().getTime());

    DataStream<ClickEvent> clicks = env.fromSource(source, watermarkStrategy, "ClickEvent Source");

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
      .name("ClickEventStatistics Kafka Sink");

    statistics.addSink(
      JdbcSink.sink(
        "INSERT INTO click_event_report (window_start, window_end, page, count) VALUES (?, ?, ?, ?)",
        (statement, stat) -> {
          statement.setString(1, sdf.format(stat.getWindowStart()));
          statement.setString(2, sdf.format(stat.getWindowEnd()));
          statement.setString(3, stat.getPage());
          statement.setLong(4, stat.getCount());
        },
        JdbcExecutionOptions.builder()
          .withBatchSize(1000)
          .withBatchIntervalMs(15000)
          .withMaxRetries(5)
          .build(),
        new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
          .withUrl("jdbc:mysql://localhost:3306/sql-demo")
          .withDriverName("com.mysql.jdbc.Driver")
          .withUsername("sql-demo")
          .withPassword("demo-sql")
          .build()
      ))
      .name("ClickEventStatistics MySQL Sink");

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
