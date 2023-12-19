package org.example.clickanalysis.functions;

import org.apache.flink.api.common.functions.MapFunction;

import org.apache.flink.api.common.functions.MapFunction;
import org.example.clickanalysis.records.ClickEvent;

import java.time.LocalTime;

public class BackpressureMap implements MapFunction<ClickEvent, ClickEvent> {
  private boolean causeBackpressure() {
    return ((LocalTime.now().getMinute() % 2) == 0);
  }

  @Override
  public ClickEvent map(ClickEvent event) throws Exception {
    if (causeBackpressure()) {
      Thread.sleep(100);
    }

    return event;
  }
}
