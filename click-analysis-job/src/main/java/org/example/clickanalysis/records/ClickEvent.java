package org.example.clickanalysis.records;

import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;
import java.util.Objects;

public class ClickEvent {
  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd-MM-yyyy HH:mm:ss:SSS")
  private Date timestamp;
  private String page;

  public ClickEvent() {
  }

  public ClickEvent(final Date timestamp, final String page) {
    this.timestamp = timestamp;
    this.page = page;
  }

  public Date getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(final Date timestamp) {
    this.timestamp = timestamp;
  }

  public String getPage() {
    return page;
  }

  public void setPage(final String page) {
    this.page = page;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final ClickEvent that = (ClickEvent) o;
    return Objects.equals(timestamp, that.timestamp) && Objects.equals(page, that.page);
  }

  @Override
  public int hashCode() {
    return Objects.hash(timestamp, page);
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("ClickEvent{");
    sb.append("timestamp=").append(timestamp);
    sb.append(", page='").append(page).append('\'');
    sb.append('}');
    return sb.toString();
  }
}
