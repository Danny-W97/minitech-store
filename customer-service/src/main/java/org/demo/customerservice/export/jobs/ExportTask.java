package org.demo.customerservice.export.jobs;

import java.time.Instant;
import java.util.UUID;

public record ExportTask(UUID id,UUID jobId,ExportKind kind,long parent,long detail,long cursor,UUID token) {
    public record Plan(ExportKind kind,long parent,long detail,long cursor){}
    public record Result(String data,Instant started,Instant finished,boolean missing){}
}
