package org.demo.customerservice.export;

import java.time.Instant;
import java.util.List;

public record SecurityDataExportPage<T>(int schemaVersion,String scope,boolean complete,Instant generatedAt,
        List<T> items,boolean hasMore,String nextCursor,List<SecurityDataExport.Omission> omissions) {}
