package com.ticketflow.apigateway;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** A stale discovery address must fail explicitly, without retrying a command. */
@Component
public class UpstreamAvailabilityFilter implements GlobalFilter, Ordered {
    @Override public int getOrder() { return -1; }
    @Override public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).onErrorResume(error -> {
            boolean unavailable=false;
            Throwable cause=error;
            for(int depth=0;cause!=null && depth<16;depth++,cause=cause.getCause()) {
                if(cause instanceof ConnectException || cause instanceof UnknownHostException) { unavailable=true;break; }
            }
            if(!unavailable || exchange.getResponse().isCommitted()) return Mono.error(error);
            var response=exchange.getResponse();
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            var body=response.bufferFactory().wrap("{\"code\":\"UPSTREAM_UNAVAILABLE\"}".getBytes(StandardCharsets.UTF_8));
            return response.writeWith(Mono.just(body));
        });
    }
}
