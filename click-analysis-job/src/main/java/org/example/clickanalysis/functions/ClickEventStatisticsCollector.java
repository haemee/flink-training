package org.example.clickanalysis.functions;

import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.example.clickanalysis.records.ClickEvent;
import org.example.clickanalysis.records.ClickEventStatistics;

import java.util.Date;

public class ClickEventStatisticsCollector
  extends ProcessWindowFunction<Long, ClickEventStatistics, String, TimeWindow> {

  @Override
  public void process(
    String s,
    ProcessWindowFunction<Long, ClickEventStatistics, String, TimeWindow>.Context context,
    Iterable<Long> iterable,
    Collector<ClickEventStatistics> collector) throws Exception {

    Long count = iterable.iterator().next();

    collector.collect(new ClickEventStatistics(new Date(context.window().getStart()), new Date(context.window().getEnd()), s, count));
  }
}
