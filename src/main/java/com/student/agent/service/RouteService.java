package com.student.agent.service;

import com.student.agent.mcp.RouteMcpClient;
import org.springframework.stereotype.Service;

@Service
public class RouteService {

    private final RouteMcpClient routeMcpClient;

    public RouteService(RouteMcpClient routeMcpClient) {
        this.routeMcpClient = routeMcpClient;
    }

    public String queryRoute(String query) {
        return routeMcpClient.queryRoute(query);
    }
}
