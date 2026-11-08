package org.demo.customerservice.export.jobs;

/** Sanitized codes only; never persist an upstream response, token, URL or exception message. */
public final class ExportFailure extends RuntimeException {
    public final String code;
    public ExportFailure(String code){super(code);this.code=code;}
}
