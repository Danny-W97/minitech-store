package org.demo.platformsecurity;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

public final class RequestBudgetFilter extends OncePerRequestFilter {
    private final long milliseconds;
    public RequestBudgetFilter(long milliseconds) {
        if(milliseconds<1 || milliseconds>3600000) throw new IllegalArgumentException("Invalid request budget");
        this.milliseconds=milliseconds;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        long budget=request.getRequestURI().endsWith("/invoices/export/zip") ? 300000 : milliseconds;
        try (var ignored=RequestBudget.open(budget,request.getHeader(RequestBudget.HEADER))) {
            RequestBudget.check(); chain.doFilter(request,response);
        } catch (NumberFormatException invalid) { reject(response,400,"Invalid deadline"); }
        catch (RequestBudget.Exceeded expired) {
            if (response.isCommitted()) throw expired;
            reject(response,504,"Request time budget exhausted");
        }
    }
    private void reject(HttpServletResponse response,int status,String message) throws IOException {
        response.setHeader("Cache-Control","no-store");
        response.setStatus(status); response.setContentType("application/json");
        response.getWriter().write("{\"status\":"+status+",\"message\":\""+message+"\"}");
    }
}
